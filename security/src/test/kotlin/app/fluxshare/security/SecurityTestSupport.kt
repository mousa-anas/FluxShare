package app.fluxshare.security

import app.fluxshare.core.DeviceId
import app.fluxshare.core.FluxShareResult
import app.fluxshare.transport.TransportEndpoint
import app.fluxshare.transport.TransportSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream

internal suspend fun testIdentity(name: String): DeviceIdentity =
    when (val result = InMemoryIdentityStore(DeviceId(name)).loadOrCreate()) {
        is FluxShareResult.Success -> result.value
        is FluxShareResult.Failure -> throw AssertionError("Could not create identity: ${result.error}")
    }

internal fun <T> FluxShareResult<T>.getOrFail(): T = when (this) {
    is FluxShareResult.Success -> value
    is FluxShareResult.Failure -> throw AssertionError("Expected success but got $error")
}

internal fun payload(size: Int, seed: Int = 0): ByteArray =
    ByteArray(size) { index -> ((index * 31 + seed) % 251).toByte() }

internal suspend fun TransportSession.readExactly(size: Int): ByteArray {
    val buffer = ByteArray(size)
    var read = 0
    while (read < size) {
        val count = read(buffer, read, size - read)
        if (count < 0) throw AssertionError("Peer closed after $read of $size bytes")
        if (count == 0) continue
        read += count
    }
    return buffer
}

internal class EstablishedSessions(
    val client: SecureSession,
    val server: SecureSession,
    val wire: MemoryWire,
)

internal suspend fun establish(
    clientIdentity: DeviceIdentity,
    serverIdentity: DeviceIdentity,
    clientTrust: PeerTrustStore = InMemoryPeerTrustStore(
        mapOf(serverIdentity.deviceId to serverIdentity.fingerprint),
    ),
    serverTrust: PeerTrustStore = InMemoryPeerTrustStore(
        mapOf(clientIdentity.deviceId to clientIdentity.fingerprint),
    ),
    confirmClient: suspend (DeviceId, PairingCode) -> Boolean = { _, _ -> true },
    confirmServer: suspend (DeviceId, PairingCode) -> Boolean = { _, _ -> true },
    wire: MemoryWire = MemoryWire(),
    clientSecurity: DefaultSessionSecurity = DefaultSessionSecurity(
        clientIdentity,
        clientTrust,
        confirmClient,
    ),
    serverSecurity: DefaultSessionSecurity = DefaultSessionSecurity(
        serverIdentity,
        serverTrust,
        confirmServer,
    ),
): EstablishedSessions = coroutineScope {
    val serverDeferred = async { serverSecurity.acceptSecure(wire.server) }
    val client = clientSecurity.secure(wire.client, serverIdentity.deviceId).getOrFail()
    val server = serverDeferred.await().getOrFail()
    EstablishedSessions(client, server, wire)
}

/**
 * In-memory [TransportSession] pair.
 *
 * Every byte sent from the client to the server is recorded, so tests can prove
 * that plaintext never appears on the wire and can flip exactly one ciphertext
 * byte without touching anything else.
 */
internal class MemoryWire {

    private val aToB = Pipe()
    private val bToA = Pipe()

    private val recorded = ByteArrayOutputStream()

    /** Index of the next byte the client will write. */
    @Volatile
    var clientBytesWritten: Int = 0
        private set

    /** Absolute offset in the client -> server stream to corrupt, or -1. */
    @Volatile
    var tamperAt: Int = -1

    @Volatile
    var tampered: Boolean = false
        private set

    val client: TransportSession = Endpoint("client", outbound = aToB, inbound = bToA, records = true)
    val server: TransportSession = Endpoint("server", outbound = bToA, inbound = aToB, records = false)

    fun recordedBytes(): ByteArray = synchronized(recorded) { recorded.toByteArray() }

    private inner class Pipe {
        private val mutex = Mutex()
        private val chunks = ArrayDeque<ByteArray>()
        private var available = 0
        private var closed = false
        private val signal = Channel<Unit>(Channel.CONFLATED)

        suspend fun write(bytes: ByteArray) {
            mutex.withLock {
                chunks.addLast(bytes)
                available += bytes.size
            }
            signal.trySend(Unit)
        }

        suspend fun read(destination: ByteArray, offset: Int, length: Int): Int {
            while (true) {
                mutex.withLock {
                    if (available == 0) {
                        if (closed) return -1
                    } else {
                        var copied = 0
                        while (copied < length && chunks.isNotEmpty()) {
                            val head = chunks.first()
                            val take = minOf(length - copied, head.size)
                            System.arraycopy(head, 0, destination, offset + copied, take)
                            copied += take
                            if (take == head.size) {
                                chunks.removeFirst()
                            } else {
                                chunks[0] = head.copyOfRange(take, head.size)
                            }
                            available -= take
                        }
                        return copied
                    }
                }
                signal.receive()
            }
        }

        suspend fun close() {
            mutex.withLock { closed = true }
            signal.trySend(Unit)
        }
    }

    private inner class Endpoint(
        private val host: String,
        private val outbound: Pipe,
        private val inbound: Pipe,
        private val records: Boolean,
    ) : TransportSession {

        override val endpoint: TransportEndpoint = TransportEndpoint(host, 4242)

        @Volatile
        private var closed = false

        override val isOpen: Boolean get() = !closed

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            return inbound.read(buffer, offset, length)
        }

        override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (length == 0) return
            var bytes = buffer.copyOfRange(offset, offset + length)
            if (records) {
                val start = clientBytesWritten
                clientBytesWritten += bytes.size
                synchronized(recorded) { recorded.write(bytes) }
                val target = tamperAt
                if (target in start until start + bytes.size) {
                    bytes = bytes.copyOf()
                    val index = target - start
                    bytes[index] = (bytes[index].toInt() xor 0x01).toByte()
                    tampered = true
                }
            }
            outbound.write(bytes)
        }

        override suspend fun close() {
            closed = true
            outbound.close()
            inbound.close()
        }
    }
}

/** A peer that reads forever: it never sends a byte, so a handshake cannot finish. */
internal class SilentSession : TransportSession {
    override val endpoint: TransportEndpoint = TransportEndpoint("silent", 1)

    private val closed = CompletableDeferred<Unit>()

    override val isOpen: Boolean get() = !closed.isCompleted

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        closed.await()
        return -1
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) = Unit

    override suspend fun close() {
        closed.complete(Unit)
    }
}
