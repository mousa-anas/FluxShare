package app.fluxshare.transfer

import app.fluxshare.core.FluxShareError
import app.fluxshare.core.FluxShareResult
import app.fluxshare.protocol.FluxFrame
import app.fluxshare.protocol.FluxFrameStream
import app.fluxshare.protocol.ProtocolErrorCode
import app.fluxshare.protocol.ProtocolLimits
import app.fluxshare.transport.TransportSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Returns the value of a successful result, or throws its error. */
internal fun <T> FluxShareResult<T>.orThrow(): T = when (this) {
    is FluxShareResult.Success -> value
    is FluxShareResult.Failure -> throw error
}

/** True for the states that end a transfer. */
internal val TransferState.isTerminal: Boolean
    get() = this == TransferState.COMPLETED ||
        this == TransferState.FAILED ||
        this == TransferState.CANCELLED

/**
 * Emits [TransferProgress] samples for one transfer and keeps the byte counter.
 *
 * A single coroutine owns the counter; [emitTerminal] may be called from a
 * cancelled coroutine because it uses [NonCancellable] and never throws.
 */
internal class TransferProgressReporter(
    private val out: SendChannel<TransferProgress>,
    private val transferId: app.fluxshare.core.TransferId,
    private val totalBytes: Long,
) {
    var bytesTransferred: Long = 0L
        private set

    @Volatile
    var terminalState: TransferState? = null
        private set

    suspend fun emit(state: TransferState) {
        out.send(TransferProgress(transferId, state, bytesTransferred, totalBytes))
        if (state.isTerminal) terminalState = state
    }

    /** Adds [delta] transferred bytes and reports a `TRANSFERRING` sample. */
    suspend fun advance(delta: Long) {
        bytesTransferred += delta
        out.send(
            TransferProgress(
                transferId = transferId,
                state = TransferState.TRANSFERRING,
                bytesTransferred = bytesTransferred,
                totalBytes = totalBytes,
            ),
        )
    }

    /** Reports a terminal state even while the calling coroutine is cancelled. */
    suspend fun emitTerminal(state: TransferState) {
        terminalState = state
        withContext(NonCancellable) {
            runCatching {
                out.send(TransferProgress(transferId, state, bytesTransferred, totalBytes))
            }
        }
    }
}

/** A transfer that an external caller may cancel by id. */
internal class ActiveTransfer {
    @Volatile
    var job: Job? = null

    @Volatile
    var session: TransportSession? = null

    /** Set by [TransferEngine.cancel] so a closed session is reported as CANCELLED. */
    @Volatile
    var cancelled: Boolean = false
}

/**
 * A failure detected by the receiving side, with the error frame to send back
 * when the session still allows it (`null` when the peer already knows).
 */
internal class ReceiverFailure(
    val error: FluxShareError,
    val reply: ProtocolErrorCode?,
) : Exception(error.message, error)

/** Maps an error frame sent by the peer onto a local failure. */
internal fun FluxFrame.Error.toTransferError(): FluxShareError {
    val detail = message?.toWireText() ?: "no detail"
    return when (code) {
        ProtocolErrorCode.MANIFEST_REJECTED -> FluxShareError.TransferRejected
        ProtocolErrorCode.CHECKSUM_MISMATCH -> FluxShareError.Unexpected(
            "The peer reported a checksum mismatch ($detail).",
        )

        ProtocolErrorCode.PROTOCOL_VIOLATION, ProtocolErrorCode.UNSUPPORTED_FRAME ->
            FluxShareError.ProtocolViolation("The peer reported $detail.")

        ProtocolErrorCode.TRANSFER_FAILED -> FluxShareError.Unexpected(
            "The peer could not store the files ($detail).",
        )

        ProtocolErrorCode.INTERNAL_ERROR, ProtocolErrorCode.UNKNOWN -> FluxShareError.Unexpected(
            "The peer reported ${code.name} ($detail).",
        )
    }
}

/** Readable frame kind for error messages. */
internal fun frameName(frame: FluxFrame): String = frame::class.simpleName ?: "frame"

/**
 * Fits free-form text into a protocol text field: control characters are
 * removed and the value is cut at a UTF-8 byte budget.
 */
internal fun String?.toWireText(maxBytes: Int = ProtocolLimits.MAX_TEXT_BYTES): String? {
    if (this == null) return null
    val clean = filterNot { it.isISOControl() }.trim()
    if (clean.isEmpty()) return null

    val builder = StringBuilder()
    var bytes = 0
    var index = 0
    while (index < clean.length) {
        val codePoint = clean.codePointAt(index)
        val piece = String(Character.toChars(codePoint))
        val pieceBytes = piece.toByteArray(Charsets.UTF_8).size
        if (bytes + pieceBytes > maxBytes) break
        builder.append(piece)
        bytes += pieceBytes
        index += Character.charCount(codePoint)
    }
    return builder.toString().ifEmpty { null }
}

/** Writes an error frame, ignoring failures: the peer may already be gone. */
internal suspend fun FluxFrameStream.writeErrorQuietly(
    code: ProtocolErrorCode,
    message: String?,
) {
    runCatching { write(FluxFrame.Error(code, message.toWireText())) }
}

/**
 * Runs a storage call, mapping platform failures onto
 * [FluxShareError.StorageUnavailable].
 *
 * Real providers throw `IOException`, `SecurityException` and friends; the
 * transfer layer must never leak those, and cancellation must never be
 * swallowed.
 */
internal suspend fun <T> storageCall(block: suspend () -> T): T {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: FluxShareError) {
        throw e
    } catch (e: Exception) {
        throw FluxShareError.StorageUnavailable
    }
}

/**
 * Runs [block] and closes its source or sink afterwards.
 *
 * A close failure after a successful body is a storage failure; on the failure
 * path the original error wins and the close is best-effort.
 */
internal suspend fun <T> withStorageClose(
    close: suspend () -> Unit,
    block: suspend () -> T,
): T {
    var succeeded = false
    try {
        val result = block()
        succeeded = true
        return result
    } finally {
        withContext(NonCancellable) {
            if (succeeded) storageCall { close() } else runCatching { close() }
        }
    }
}

/** An engine-level timeout expired; the session has already been closed. */
internal class TransferTimeoutException : Exception("The peer did not respond in time.")

/**
 * Runs [block] under an application-level timeout.
 *
 * Socket reads block in native code and ignore coroutine cancellation, so the
 * watchdog closes the session on expiry; the blocked read then ends and the
 * failure surfaces as [TransferTimeoutException].
 */
internal suspend fun <T> withTransferTimeout(
    timeoutMillis: Long,
    session: TransportSession,
    block: suspend () -> T,
): T = coroutineScope {
    val expired = AtomicBoolean(false)
    val watchdog = launch {
        delay(timeoutMillis)
        expired.set(true)
        runCatching { session.close() }
    }

    val result = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (expired.get()) throw TransferTimeoutException() else throw e
    } finally {
        watchdog.cancel()
    }

    if (expired.get()) throw TransferTimeoutException()
    result
}

/** Maps a worker exception onto the failure the caller should see. */
internal fun Exception.toTransferFailure(): FluxShareError = when (this) {
    is FluxShareError -> this
    is TransferTimeoutException -> FluxShareError.PeerUnavailable
    else -> FluxShareError.Unexpected(message ?: this::class.simpleName ?: "Unexpected failure")
}

/** Maps a receiver exception onto its failure and the error frame to send back. */
internal fun Exception.toReceiverFailure(): Pair<FluxShareError, ProtocolErrorCode?> = when (this) {
    is ReceiverFailure -> error to reply
    is TransferTimeoutException -> FluxShareError.PeerUnavailable to null
    is FluxShareError -> this to when (this) {
        is FluxShareError.ProtocolViolation -> ProtocolErrorCode.PROTOCOL_VIOLATION
        else -> ProtocolErrorCode.TRANSFER_FAILED
    }

    else -> FluxShareError.Unexpected(message ?: this::class.simpleName ?: "Unexpected failure") to
        ProtocolErrorCode.TRANSFER_FAILED
}

/** Reads the next frame and requires it to be a [T]. */
internal suspend inline fun <reified T : FluxFrame> FluxFrameStream.readExpecting(what: String): T {
    val frame = read()
        ?: throw FluxShareError.Unexpected("The peer closed the session before sending $what.")
    if (frame !is T) {
        throw FluxShareError.ProtocolViolation("Expected $what but received ${frameName(frame)}.")
    }
    return frame
}
