package app.fluxshare.security

import app.fluxshare.core.FluxShareError
import app.fluxshare.transport.TransportSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLException

/**
 * TLS record layer over a [TransportSession], driven by [SSLEngine].
 *
 * The channel never blocks while holding its engine lock: blocking reads and
 * writes on the transport happen outside it, so one coroutine may read while
 * another writes. Only one reader and one writer are supported, matching the
 * [TransportSession] contract.
 *
 * Application data is never handed out before [handshake] returned and the
 * caller authenticated the peer, because plaintext stays in [appIn] until
 * [read] is called.
 */
internal class TlsChannel(
    private val session: TransportSession,
    private val engine: SSLEngine,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val engineLock = Mutex()
    private val writeLock = Mutex()
    private val closed = AtomicBoolean(false)

    /** Ciphertext waiting to be unwrapped; always in fill mode. */
    private var netIn: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)

    /** Plaintext waiting to be delivered; always in drain mode. */
    private var appIn: ByteBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize)
        .apply { limit(0) }

    /** Reused by the single writer only. */
    private var netOut: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)

    /** Reused by the single reader only; filled outside [engineLock]. */
    private val readBuffer = ByteArray(engine.session.packetBufferSize)

    val protocol: String get() = engine.session.protocol

    val cipherSuite: String get() = engine.session.cipherSuite

    val isOpen: Boolean get() = !closed.get() && session.isOpen

    /**
     * Runs the TLS handshake to completion.
     *
     * @return the peer certificate, whose identity the caller must authenticate
     * before reading or writing application data.
     * @throws FluxShareError.SecurityFailure when the handshake fails, is
     * truncated or cannot make progress.
     */
    suspend fun handshake(): X509Certificate {
        engine.beginHandshake()
        var steps = 0
        while (true) {
            when (engine.handshakeStatus) {
                HandshakeStatus.FINISHED, HandshakeStatus.NOT_HANDSHAKING -> break
                HandshakeStatus.NEED_TASK -> runDelegatedTasks()
                HandshakeStatus.NEED_WRAP -> wrapHandshakeFlight()
                HandshakeStatus.NEED_UNWRAP -> unwrapHandshakeFlight()
                else -> throw FluxShareError.SecurityFailure
            }
            if (++steps > MAX_HANDSHAKE_STEPS) throw FluxShareError.SecurityFailure
        }
        return peerCertificate()
    }

    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        requireRange(buffer, offset, length)
        if (length == 0) return 0

        var emptyNetworkReads = 0
        while (true) {
            val state = engineLock.withLock { decryptLocked() }
            when (state) {
                TlsReadState.DATA -> return engineLock.withLock {
                    val count = minOf(length, appIn.remaining())
                    appIn.get(buffer, offset, count)
                    count
                }

                TlsReadState.CLOSED -> return -1

                TlsReadState.NEED_INPUT -> {
                    val read = withContext(ioDispatcher) {
                        session.read(readBuffer, 0, readBuffer.size)
                    }
                    if (read < 0) {
                        engineLock.withLock { closed.set(true) }
                        return -1
                    }
                    if (read == 0) {
                        if (++emptyNetworkReads > MAX_EMPTY_NETWORK_READS) {
                            throw FluxShareError.PeerUnavailable
                        }
                        continue
                    }
                    emptyNetworkReads = 0
                    engineLock.withLock {
                        ensureNetInFree(read)
                        netIn.put(readBuffer, 0, read)
                    }
                }
            }
        }
    }

    suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        requireRange(buffer, offset, length)
        if (length == 0) return

        writeLock.withLock {
            if (closed.get()) {
                throw FluxShareError.Unexpected("The secure session is closed.")
            }

            engineLock.withLock {
                val source = ByteBuffer.wrap(buffer, offset, length)
                val records = length / MAX_TLS_PLAINTEXT_BYTES + 1
                val required = length + records * MAX_TLS_RECORD_OVERHEAD_BYTES + netOut.capacity()
                if (netOut.capacity() < required) netOut = ByteBuffer.allocate(required)
                netOut.clear()

                var stalledRounds = 0
                while (source.hasRemaining()) {
                    val result = try {
                        engine.wrap(source, netOut)
                    } catch (e: SSLException) {
                        closed.set(true)
                        throw FluxShareError.SecurityFailure
                    }

                    if (result.handshakeStatus == HandshakeStatus.NEED_TASK) {
                        runDelegatedTasks()
                    }
                    if (result.handshakeStatus == HandshakeStatus.NEED_WRAP) {
                        // A post-handshake message (for example a KeyUpdate
                        // answer) is due. Wrap again before touching app data.
                        stalledRounds++
                    }

                    when (result.status) {
                        Status.CLOSED -> {
                            closed.set(true)
                            throw FluxShareError.Unexpected("The secure session is closed.")
                        }

                        Status.BUFFER_OVERFLOW -> {
                            netOut = growFillBuffer(netOut)
                            continue
                        }

                        else -> Unit
                    }

                    if (result.bytesConsumed() == 0 && result.bytesProduced() == 0) {
                        if (++stalledRounds > MAX_WRAP_ROUNDS) throw FluxShareError.SecurityFailure
                    } else if (result.bytesConsumed() > 0) {
                        stalledRounds = 0
                    }
                }

                netOut.flip()
            }

            // Held under writeLock, so netOut cannot be reused while it is being
            // sent; the blocking transport write never holds the engine lock.
            if (netOut.hasRemaining()) {
                withContext(ioDispatcher) {
                    session.write(
                        netOut.array(),
                        netOut.arrayOffset() + netOut.position(),
                        netOut.remaining(),
                    )
                }
            }
        }
    }

    /**
     * Closes the session.
     *
     * A close_notify is sent when the writer is idle, but a blocked transport
     * write must never keep shutdown (or a blocked reader) waiting: the
     * underlying session is closed unconditionally, which unblocks any pending
     * read.
     */
    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            if (writeLock.tryLock()) {
                try {
                    coroutineScope {
                        val watchdog = launch {
                            delay(CLOSE_NOTIFY_TIMEOUT_MILLIS)
                            runCatching { session.close() }
                        }
                        try {
                            sendCloseNotify()
                        } finally {
                            watchdog.cancel()
                        }
                    }
                } finally {
                    writeLock.unlock()
                }
            }
        } catch (_: Exception) {
            // Best effort: a failed close_notify never fails a close.
        } finally {
            runCatching { session.close() }
        }
    }

    private suspend fun sendCloseNotify() {
        val record = engineLock.withLock {
            engine.closeOutbound()
            netOut.clear()
            val result = try {
                engine.wrap(EMPTY_BUFFER, netOut)
            } catch (e: SSLException) {
                return
            }
            if (result.status == Status.CLOSED || netOut.position() == 0) return
            netOut.flip()
            ByteArray(netOut.remaining()).also { netOut.get(it) }
        }
        withContext(ioDispatcher) { session.write(record) }
    }

    private fun decryptLocked(): TlsReadState {
        if (appIn.hasRemaining()) return TlsReadState.DATA
        if (closed.get()) return TlsReadState.CLOSED

        var rounds = 0
        while (rounds++ < MAX_UNWRAP_ROUNDS) {
            val result = try {
                unwrapOnce()
            } catch (e: SSLException) {
                if (closed.get()) return TlsReadState.CLOSED
                throw FluxShareError.SecurityFailure
            }

            when (result.status) {
                Status.OK -> {
                    if (appIn.hasRemaining()) return TlsReadState.DATA
                    // Post-handshake messages (NewSessionTicket, KeyUpdate) are
                    // consumed without producing application data; keep going.
                    if (result.bytesConsumed() == 0) return TlsReadState.NEED_INPUT
                }

                Status.BUFFER_OVERFLOW -> {
                    if (appIn.hasRemaining()) return TlsReadState.DATA
                    appIn = ByteBuffer.allocate(maxOf(appIn.capacity() * 2, MIN_APP_BUFFER_BYTES))
                        .apply { limit(0) }
                }

                Status.BUFFER_UNDERFLOW -> return TlsReadState.NEED_INPUT

                Status.CLOSED -> {
                    closed.set(true)
                    return TlsReadState.CLOSED
                }
            }
        }
        throw FluxShareError.SecurityFailure
    }

    private fun unwrapOnce(): SSLEngineResult {
        appIn.compact()
        netIn.flip()
        val result = engine.unwrap(netIn, appIn)
        netIn.compact()
        appIn.flip()
        return result
    }

    private suspend fun wrapHandshakeFlight() {
        var rounds = 0
        while (engine.handshakeStatus == HandshakeStatus.NEED_WRAP) {
            netOut.clear()
            val result = try {
                engine.wrap(EMPTY_BUFFER, netOut)
            } catch (e: SSLException) {
                throw FluxShareError.SecurityFailure
            }

            when (result.status) {
                Status.CLOSED -> throw FluxShareError.SecurityFailure
                Status.BUFFER_OVERFLOW -> {
                    netOut = growFillBuffer(netOut)
                    continue
                }

                else -> Unit
            }

            netOut.flip()
            if (netOut.hasRemaining()) {
                withContext(ioDispatcher) {
                    session.write(
                        netOut.array(),
                        netOut.arrayOffset() + netOut.position(),
                        netOut.remaining(),
                    )
                }
            }
            if (result.bytesProduced() == 0) return
            if (++rounds > MAX_HANDSHAKE_STEPS) throw FluxShareError.SecurityFailure
        }
    }

    private suspend fun unwrapHandshakeFlight() {
        var rounds = 0
        while (engine.handshakeStatus == HandshakeStatus.NEED_UNWRAP) {
            val result = try {
                unwrapOnce()
            } catch (e: SSLException) {
                throw FluxShareError.SecurityFailure
            }

            when (result.status) {
                Status.OK -> Unit
                Status.BUFFER_OVERFLOW -> growAppIn()
                Status.BUFFER_UNDERFLOW -> if (!fillNetIn()) throw FluxShareError.SecurityFailure
                Status.CLOSED -> throw FluxShareError.SecurityFailure
            }
            if (++rounds > MAX_HANDSHAKE_STEPS) throw FluxShareError.SecurityFailure
        }
    }

    /** Reads ciphertext from the transport into [netIn]. @return false at end of stream. */
    private suspend fun fillNetIn(): Boolean {
        if (!netIn.hasRemaining()) netIn = growFillBuffer(netIn)
        val read = withContext(ioDispatcher) { session.read(readBuffer, 0, readBuffer.size) }
        if (read < 0) return false
        if (read > 0) {
            ensureNetInFree(read)
            netIn.put(readBuffer, 0, read)
        }
        return true
    }

    private fun ensureNetInFree(required: Int) {
        if (netIn.remaining() < required) {
            netIn = growFillBuffer(netIn, required)
        }
    }

    /**
     * Grows a buffer that is in fill mode (position = end of data).
     *
     * @param required additional free space that must exist afterwards.
     */
    private fun growFillBuffer(buffer: ByteBuffer, required: Int = 0): ByteBuffer {
        val needed = buffer.position() + maxOf(required, buffer.capacity())
        val bigger = ByteBuffer.allocate(maxOf(buffer.capacity() * 2, needed))
        val position = buffer.position()
        buffer.flip()
        bigger.put(buffer)
        bigger.position(position)
        return bigger
    }

    /** Grows [appIn] while keeping plaintext that has not been delivered yet. */
    private fun growAppIn() {
        val bigger = ByteBuffer.allocate(appIn.capacity() * 2)
        bigger.put(appIn) // appIn is in drain mode: copies the unread bytes
        bigger.flip()
        appIn = bigger
    }

    private suspend fun runDelegatedTasks() {
        while (true) {
            val task = engine.delegatedTask ?: return
            withContext(ioDispatcher) { task.run() }
        }
    }

    private fun peerCertificate(): X509Certificate {
        val peer = runCatching { engine.session.peerCertificates }
            .getOrElse { throw FluxShareError.SecurityFailure }
        return peer.firstOrNull() as? X509Certificate
            ?: throw FluxShareError.SecurityFailure
    }

    private fun requireRange(buffer: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size) {
            "Range [$offset, ${offset + length}) is outside a ${buffer.size}-byte buffer"
        }
    }

    private enum class TlsReadState { DATA, NEED_INPUT, CLOSED }

    private companion object {
        val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocate(0)

        /** Largest TLS record payload. */
        const val MAX_TLS_PLAINTEXT_BYTES: Int = 16 * 1024

        /** Record header, inner content type and AEAD tag, generously rounded. */
        const val MAX_TLS_RECORD_OVERHEAD_BYTES: Int = 64

        const val MIN_APP_BUFFER_BYTES: Int = 32 * 1024

        const val MAX_HANDSHAKE_STEPS: Int = 512

        const val MAX_UNWRAP_ROUNDS: Int = 64

        const val MAX_WRAP_ROUNDS: Int = 64

        const val MAX_EMPTY_NETWORK_READS: Int = 1_024

        const val CLOSE_NOTIFY_TIMEOUT_MILLIS: Long = 250
    }
}
