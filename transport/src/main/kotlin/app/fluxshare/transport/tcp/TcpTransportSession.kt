package app.fluxshare.transport.tcp

import app.fluxshare.core.FluxShareError
import app.fluxshare.transport.TransportEndpoint
import app.fluxshare.transport.TransportSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [TransportSession] backed by a connected TCP socket.
 *
 * Reads and writes go straight into caller buffers through a small output
 * buffer, so file content is never copied into intermediate arrays. One reader
 * and one writer coroutine may use a session concurrently; multiple concurrent
 * readers are not supported by the underlying stream.
 *
 * I/O errors surface as [FluxShareError.PeerUnavailable] so callers never see
 * platform socket exceptions.
 *
 * Cancellation note: blocking socket I/O cannot be interrupted by coroutine
 * cancellation alone. Callers that need to cancel a pending read or write must
 * call [close] from another coroutine; the pending call then ends with `-1`.
 */
internal class TcpTransportSession(
    private val socket: Socket,
    override val endpoint: TransportEndpoint,
    private val ioDispatcher: CoroutineDispatcher,
) : TransportSession {

    private val input = socket.getInputStream()
    private val output = BufferedOutputStream(socket.getOutputStream(), OUTPUT_BUFFER_BYTES)
    private val closed = AtomicBoolean(false)

    override val isOpen: Boolean
        get() = !closed.get() && !socket.isClosed

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size) {
            "Range [$offset, ${offset + length}) is outside a ${buffer.size}-byte buffer"
        }
        if (length == 0) return 0
        if (!isOpen) return -1

        return try {
            val read = withContext(ioDispatcher) { input.read(buffer, offset, length) }
            if (read < 0) {
                // The peer closed its write side; this session can no longer be read.
                closed.set(true)
            }
            read
        } catch (e: IOException) {
            closed.set(true)
            // A local close() can make a blocked read fail; report it as end of stream.
            if (socket.isClosed) return -1
            throw FluxShareError.PeerUnavailable
        }
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size) {
            "Range [$offset, ${offset + length}) is outside a ${buffer.size}-byte buffer"
        }
        if (length == 0) return
        if (!isOpen) {
            throw FluxShareError.Unexpected("The TCP session is closed.")
        }

        try {
            withContext(ioDispatcher) {
                output.write(buffer, offset, length)
                output.flush()
            }
        } catch (e: IOException) {
            closed.set(true)
            throw FluxShareError.PeerUnavailable
        }
    }

    override suspend fun close() {
        // Always release the socket: `closed` may already have been set by EOF or
        // an I/O error, and returning early there would leak the socket.
        closed.set(true)
        withContext(ioDispatcher) {
            runCatching { output.flush() }
            socket.closeQuietly()
        }
    }

    companion object {
        /** 64 KiB keeps syscall overhead low without delaying small frames noticeably. */
        private const val OUTPUT_BUFFER_BYTES = 64 * 1024
    }
}
