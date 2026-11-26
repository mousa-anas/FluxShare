package app.fluxshare.transport.tcp

import app.fluxshare.core.FluxShareError
import app.fluxshare.core.FluxShareResult
import app.fluxshare.transport.Transport
import app.fluxshare.transport.TransportEndpoint
import app.fluxshare.transport.TransportListener
import app.fluxshare.transport.TransportSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * TCP implementation of [Transport].
 *
 * TCP was chosen for Phase 1 because it is universally available on Android,
 * Windows and test JVMs, gives ordered reliable delivery and has predictable
 * throughput on a LAN. Socket options are tuned for bulk transfer: Nagle's
 * algorithm is disabled and larger kernel buffers are requested when the
 * platform allows it.
 *
 * This transport only moves a byte stream. Framing and validation live in the
 * transport-independent `:protocol` module.
 *
 * **Security:** Phase 1 TCP sessions are neither encrypted nor authenticated.
 * Do not expose the port to untrusted networks.
 */
class TcpTransport(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val bindHost: String = DEFAULT_BIND_HOST,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val socketBufferBytes: Int = DEFAULT_SOCKET_BUFFER_BYTES,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
) : Transport {

    init {
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(socketBufferBytes > 0) { "socketBufferBytes must be positive" }
        require(readTimeoutMillis >= 0) { "readTimeoutMillis must not be negative" }
    }

    override val name: String = "tcp"

    override suspend fun connect(endpoint: TransportEndpoint): FluxShareResult<TransportSession> =
        withContext(ioDispatcher) {
            val socket = Socket()
            try {
                configureSocket(socket, socketBufferBytes, readTimeoutMillis)
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMillis)

                val remote = TransportEndpoint(
                    host = socket.inetAddress?.hostAddress ?: endpoint.host,
                    port = socket.port,
                )
                FluxShareResult.Success(TcpTransportSession(socket, remote, ioDispatcher))
            } catch (e: UnknownHostException) {
                socket.closeQuietly()
                FluxShareResult.Failure(FluxShareError.PeerUnavailable)
            } catch (e: SocketTimeoutException) {
                socket.closeQuietly()
                FluxShareResult.Failure(FluxShareError.PeerUnavailable)
            } catch (e: IOException) {
                socket.closeQuietly()
                FluxShareResult.Failure(FluxShareError.PeerUnavailable)
            }
        }

    override suspend fun listen(port: Int): FluxShareResult<TransportListener> =
        withContext(ioDispatcher) {
            require(port in 0..65535) { "Port must be in 0..65535, was $port" }
            val serverSocket = ServerSocket()
            try {
                serverSocket.reuseAddress = true
                serverSocket.bind(InetSocketAddress(bindHost, port), DEFAULT_ACCEPT_BACKLOG)
                FluxShareResult.Success(
                    TcpTransportListener(
                        serverSocket = serverSocket,
                        ioDispatcher = ioDispatcher,
                        socketBufferBytes = socketBufferBytes,
                        readTimeoutMillis = readTimeoutMillis,
                    ),
                )
            } catch (e: IOException) {
                serverSocket.closeQuietly()
                FluxShareResult.Failure(FluxShareError.NetworkUnavailable)
            }
        }

    companion object {
        /** Binds every interface so devices on the local network can connect. */
        const val DEFAULT_BIND_HOST: String = "0.0.0.0"

        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 5_000

        /** Requested kernel send/receive buffer size for bulk transfers. */
        const val DEFAULT_SOCKET_BUFFER_BYTES: Int = 256 * 1024

        /** `0` keeps reads blocking until the peer sends data or closes. */
        const val DEFAULT_READ_TIMEOUT_MILLIS: Int = 0

        private const val DEFAULT_ACCEPT_BACKLOG = 8
    }
}

/**
 * Applies bulk-transfer socket options.
 *
 * Buffer sizing is best-effort: some platforms cap or ignore the request, and
 * failing to grow a kernel buffer must never fail the transfer.
 */
internal fun configureSocket(socket: Socket, socketBufferBytes: Int, readTimeoutMillis: Int) {
    socket.tcpNoDelay = true
    socket.soTimeout = readTimeoutMillis
    runCatching { socket.receiveBufferSize = socketBufferBytes }
    runCatching { socket.sendBufferSize = socketBufferBytes }
}

internal fun java.io.Closeable.closeQuietly() {
    runCatching { close() }
}

/** Validated endpoint for an accepted socket. */
internal fun acceptedEndpoint(socket: Socket): TransportEndpoint = TransportEndpoint(
    host = socket.inetAddress?.hostAddress ?: "unknown",
    port = socket.port,
)
