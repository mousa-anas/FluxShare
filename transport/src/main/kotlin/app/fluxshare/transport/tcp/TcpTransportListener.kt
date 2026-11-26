package app.fluxshare.transport.tcp

import app.fluxshare.core.FluxShareError
import app.fluxshare.core.FluxShareResult
import app.fluxshare.transport.TransportEndpoint
import app.fluxshare.transport.TransportListener
import app.fluxshare.transport.TransportSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Accepts TCP sessions on a single [ServerSocket].
 *
 * `accept` is called repeatedly, one session at a time; callers that need
 * concurrent peers should run one accept loop per connection. Closing the
 * listener is idempotent and makes any in-flight or later `accept` fail instead
 * of blocking forever.
 */
internal class TcpTransportListener(
    private val serverSocket: ServerSocket,
    private val ioDispatcher: CoroutineDispatcher,
    private val socketBufferBytes: Int,
    private val readTimeoutMillis: Int,
) : TransportListener {

    private val closed = AtomicBoolean(false)

    override val endpoint: TransportEndpoint = TransportEndpoint(
        host = serverSocket.inetAddress?.hostAddress ?: TcpTransport.DEFAULT_BIND_HOST,
        port = serverSocket.localPort,
    )

    override suspend fun accept(): FluxShareResult<TransportSession> = withContext(ioDispatcher) {
        if (closed.get()) {
            return@withContext FluxShareResult.Failure(
                FluxShareError.Unexpected("The TCP listener is closed."),
            )
        }

        val socket = try {
            serverSocket.accept()
        } catch (e: SocketException) {
            return@withContext FluxShareResult.Failure(
                if (closed.get()) {
                    FluxShareError.Unexpected("The TCP listener is closed.")
                } else {
                    FluxShareError.NetworkUnavailable
                },
            )
        } catch (e: IOException) {
            return@withContext FluxShareResult.Failure(FluxShareError.NetworkUnavailable)
        }

        return@withContext try {
            configureSocket(socket, socketBufferBytes, readTimeoutMillis)
            FluxShareResult.Success(
                TcpTransportSession(socket, acceptedEndpoint(socket), ioDispatcher),
            )
        } catch (e: IOException) {
            // Never leak an accepted socket when session setup fails.
            socket.closeQuietly()
            FluxShareResult.Failure(FluxShareError.NetworkUnavailable)
        }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        withContext(ioDispatcher) {
            serverSocket.closeQuietly()
        }
    }
}
