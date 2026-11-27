package app.fluxshare.transfer

import app.fluxshare.core.FluxShareError
import app.fluxshare.core.FluxShareResult
import app.fluxshare.core.PeerDevice
import app.fluxshare.core.TransferId
import app.fluxshare.protocol.FluxFrame
import app.fluxshare.protocol.FluxFrameStream
import app.fluxshare.protocol.IncrementalSha256
import app.fluxshare.protocol.ManifestEntry
import app.fluxshare.protocol.ManifestValidator
import app.fluxshare.storage.FileSource
import app.fluxshare.storage.StorageProvider
import app.fluxshare.storage.TransferFile
import app.fluxshare.transport.Transport
import app.fluxshare.transport.TransportSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends files with the FluxShare frame protocol over any [Transport].
 *
 * The engine streams each file from a [FileSource] into a reusable buffer, so a
 * file is never held in memory, and hashes it while sending. One
 * `FILE_DIGEST` frame closes every file; the receiver verifies it and answers
 * with `TRANSFER_COMPLETE` once all files are verified.
 *
 * Lifecycle: [execute] emits `CONNECTING`, `AWAITING_APPROVAL`,
 * `TRANSFERRING` and finally `COMPLETED`, `FAILED` or `CANCELLED`. A failed
 * transfer emits its final `FAILED` sample and then throws the
 * [FluxShareError]; a cancelled one only emits `CANCELLED`, so callers can
 * distinguish the two without treating cancellation as an error.
 *
 * Waiting is bounded: [approvalTimeoutMillis] covers the receiver's decision
 * and [dataIdleTimeoutMillis] covers the final acknowledgement. On expiry the
 * session is closed (a blocking socket read ignores cancellation) and the
 * transfer fails with [FluxShareError.PeerUnavailable].
 *
 * **Security:** the protocol and the TCP transport are neither encrypted nor
 * authenticated. Do not use them on untrusted networks.
 */
class DefaultTransferEngine(
    private val transport: Transport,
    private val storage: StorageProvider,
    private val localDevice: PeerDevice,
    private val endpoints: PeerEndpointResolver,
    chunkSizeBytes: Int = TransferChunking.DEFAULT_CHUNK_SIZE_BYTES,
    private val approvalTimeoutMillis: Long = DEFAULT_APPROVAL_TIMEOUT_MILLIS,
    private val dataIdleTimeoutMillis: Long = DEFAULT_DATA_IDLE_TIMEOUT_MILLIS,
) : TransferEngine {

    private val chunkSizeBytes: Int = TransferChunking.requireValid(chunkSizeBytes)
    private val active = ConcurrentHashMap<TransferId, ActiveTransfer>()

    init {
        require(approvalTimeoutMillis > 0) {
            "approvalTimeoutMillis must be positive, was $approvalTimeoutMillis"
        }
        require(dataIdleTimeoutMillis > 0) {
            "dataIdleTimeoutMillis must be positive, was $dataIdleTimeoutMillis"
        }
    }

    override fun execute(request: TransferRequest): Flow<TransferProgress> {
        require(request.direction == TransferDirection.SEND) {
            "DefaultTransferEngine only sends; a ${request.direction} request belongs to TransferReceiver."
        }

        return channelFlow {
            val transferId = TransferId.new()
            val out = channel
            val reporter = TransferProgressReporter(
                out = out,
                transferId = transferId,
                totalBytes = request.files.sumOf { it.sizeBytes },
            )
            val entry = ActiveTransfer()
            active[transferId] = entry
            var failure: FluxShareError? = null

            val job = launch(start = CoroutineStart.LAZY) {
                var session: TransportSession? = null
                try {
                    val connected = connect(request, reporter, entry)
                    session = connected
                    val stream = FluxFrameStream(connected)

                    stream.write(FluxFrame.Hello(localDevice))

                    val manifest = ManifestValidator.validate(
                        transferId = transferId,
                        files = request.files.map { file ->
                            ManifestEntry(
                                name = file.name,
                                sizeBytes = file.sizeBytes,
                                mimeType = file.mimeType,
                            )
                        },
                    )
                    stream.write(FluxFrame.Manifest(manifest))

                    reporter.emit(TransferState.AWAITING_APPROVAL)
                    awaitApproval(stream, connected, transferId)

                    reporter.emit(TransferState.TRANSFERRING)
                    sendFiles(stream, request.files, reporter)

                    awaitOutcome(stream, connected, transferId)
                    // An explicit cancel() may have closed the session between the
                    // last read and here; that is a cancellation, not a success.
                    if (entry.cancelled) throw CancellationException("Transfer was cancelled")
                    reporter.emit(TransferState.COMPLETED)
                } catch (e: CancellationException) {
                    reporter.emitTerminal(TransferState.CANCELLED)
                } catch (e: Exception) {
                    if (entry.cancelled) {
                        reporter.emitTerminal(TransferState.CANCELLED)
                    } else {
                        failure = e.toTransferFailure()
                        reporter.emit(TransferState.FAILED)
                    }
                } finally {
                    withContext(NonCancellable) {
                        session?.let { session -> runCatching { session.close() } }
                    }
                }
            }

            entry.job = job
            try {
                job.start()
                try {
                    job.join()
                } finally {
                    // Closing here unblocks a read that external cancellation
                    // interrupted, then wait for the worker to release its files.
                    withContext(NonCancellable) {
                        entry.session?.let { session -> runCatching { session.close() } }
                        job.cancelAndJoin()
                    }
                }
            } finally {
                active.remove(transferId)
            }

            failure?.let { throw it }
        }
    }

    override suspend fun cancel(transferId: TransferId) {
        val entry = active[transferId] ?: return
        entry.cancelled = true
        entry.session?.let { session -> runCatching { session.close() } }
        entry.job?.cancel()
    }

    private suspend fun connect(
        request: TransferRequest,
        reporter: TransferProgressReporter,
        entry: ActiveTransfer,
    ): TransportSession {
        reporter.emit(TransferState.CONNECTING)

        val endpoint = when (val resolved = endpoints.resolve(request.peer)) {
            is FluxShareResult.Success -> resolved.value
            is FluxShareResult.Failure -> throw resolved.error
        }
        val session = when (val connected = transport.connect(endpoint)) {
            is FluxShareResult.Success -> connected.value
            is FluxShareResult.Failure -> throw connected.error
        }
        entry.session = session
        return session
    }

    private suspend fun awaitApproval(
        stream: FluxFrameStream,
        session: TransportSession,
        transferId: TransferId,
    ) {
        val response = withTransferTimeout(approvalTimeoutMillis, session) { stream.read() }
            ?: throw FluxShareError.Unexpected(
                "The receiver closed the session before answering the manifest.",
            )
        if (response !is FluxFrame.ManifestResponse) {
            throw FluxShareError.ProtocolViolation(
                "Expected a manifest response but received ${frameName(response)}.",
            )
        }
        if (response.transferId != transferId) {
            throw FluxShareError.ProtocolViolation(
                "Manifest response is for transfer ${response.transferId.value}, not ${transferId.value}.",
            )
        }
        if (!response.accepted) throw FluxShareError.TransferRejected
    }

    private suspend fun sendFiles(
        stream: FluxFrameStream,
        files: List<TransferFile>,
        reporter: TransferProgressReporter,
    ) {
        // One reusable buffer for the whole transfer: no file is ever buffered whole.
        val buffer = ByteArray(chunkSizeBytes)
        files.forEachIndexed { index, file ->
            val source = openSource(file)
            withStorageClose(close = { source.close() }) {
                val digest = IncrementalSha256()
                var offset = 0L
                while (offset < file.sizeBytes) {
                    val wanted = minOf(chunkSizeBytes.toLong(), file.sizeBytes - offset).toInt()
                    val read = storageCall { source.read(buffer, offset = 0, length = wanted) }
                    if (read < 0) {
                        throw FluxShareError.Unexpected(
                            "File \"${file.name}\" ended after $offset of ${file.sizeBytes} bytes.",
                        )
                    }
                    if (read == 0) {
                        throw FluxShareError.Unexpected(
                            "The storage provider made no progress while reading \"${file.name}\".",
                        )
                    }
                    if (read > wanted) {
                        throw FluxShareError.Unexpected(
                            "The storage provider returned $read bytes for a $wanted-byte read.",
                        )
                    }

                    stream.write(
                        FluxFrame.Data(
                            fileIndex = index,
                            offset = offset,
                            bytes = buffer.copyOf(read),
                        ),
                    )
                    digest.update(buffer, offset = 0, length = read)
                    offset += read
                    reporter.advance(read.toLong())
                }
                stream.write(FluxFrame.FileDigest(fileIndex = index, sha256 = digest.digest()))
            }
        }
    }

    /** Waits for the receiver's verification: only a matching confirmation is success. */
    private suspend fun awaitOutcome(
        stream: FluxFrameStream,
        session: TransportSession,
        transferId: TransferId,
    ) {
        val last = withTransferTimeout(dataIdleTimeoutMillis, session) { stream.read() }
            ?: throw FluxShareError.Unexpected(
                "The receiver closed the session without confirmation.",
            )
        when (last) {
            is FluxFrame.TransferComplete -> {
                if (last.transferId != transferId) {
                    throw FluxShareError.ProtocolViolation(
                        "The receiver confirmed transfer ${last.transferId.value}, not ${transferId.value}.",
                    )
                }
            }

            is FluxFrame.Error -> throw last.toTransferError()

            else -> throw FluxShareError.ProtocolViolation(
                "The receiver sent ${frameName(last)} instead of confirming the transfer.",
            )
        }
    }

    private suspend fun openSource(file: TransferFile): FileSource =
        storageCall {
            when (val opened = storage.openSource(file)) {
                is FluxShareResult.Success -> opened.value
                is FluxShareResult.Failure -> throw opened.error
            }
        }

    companion object {
        /** A human is deciding whether to accept, so this is generous. */
        const val DEFAULT_APPROVAL_TIMEOUT_MILLIS: Long = 5 * 60 * 1_000L

        /** Longest silence tolerated while waiting for the final confirmation. */
        const val DEFAULT_DATA_IDLE_TIMEOUT_MILLIS: Long = 30 * 1_000L
    }
}
