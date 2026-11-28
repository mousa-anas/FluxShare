package app.fluxshare.security

import app.fluxshare.core.DeviceId
import app.fluxshare.core.DevicePlatform
import app.fluxshare.core.FluxShareError
import app.fluxshare.core.FluxShareResult
import app.fluxshare.core.PeerDevice
import app.fluxshare.protocol.FluxFrame
import app.fluxshare.protocol.FluxFrameStream
import app.fluxshare.transport.TransportEndpoint
import app.fluxshare.transport.tcp.TcpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TlsSessionTest {

    @Test
    fun `mutual handshake authenticates both devices with AEAD TLS`() = runBlocking {
        withTimeout(30_000) {
            val clientIdentity = testIdentity("client-device")
            val serverIdentity = testIdentity("server-device")

            val sessions = establish(clientIdentity, serverIdentity)
            val client = sessions.client as TlsSecureSession
            val server = sessions.server as TlsSecureSession

            assertEquals(serverIdentity.deviceId, client.remoteDeviceId)
            assertEquals(clientIdentity.deviceId, server.remoteDeviceId)
            assertTrue(client.negotiatedProtocol == "TLSv1.3" || client.negotiatedProtocol == "TLSv1.2")
            assertTrue(server.negotiatedProtocol == "TLSv1.3" || server.negotiatedProtocol == "TLSv1.2")
            assertTrue(
                client.negotiatedCipherSuite in TlsPolicy.AEAD_CIPHER_SUITES,
                "Unexpected suite ${client.negotiatedCipherSuite}",
            )
            assertTrue(
                server.negotiatedCipherSuite in TlsPolicy.AEAD_CIPHER_SUITES,
                "Unexpected suite ${server.negotiatedCipherSuite}",
            )

            client.close()
            server.close()
            assertFalse(client.isOpen)
        }
    }

    @Test
    fun `round trips multi mebibyte payloads in both directions`() = runBlocking {
        withTimeout(120_000) {
            val sessions = establish(testIdentity("client"), testIdentity("server"))
            val toServer = payload(3 shl 20, seed = 3)
            val toClient = payload((2 shl 20) + 12_345, seed = 9)

            val clientWrite = async(Dispatchers.Default) { sessions.client.write(toServer) }
            val serverRead = async(Dispatchers.Default) { sessions.server.readExactly(toServer.size) }
            clientWrite.await()
            assertContentEquals(toServer, serverRead.await())

            val serverWrite = async(Dispatchers.Default) { sessions.server.write(toClient) }
            val clientRead = async(Dispatchers.Default) { sessions.client.readExactly(toClient.size) }
            serverWrite.await()
            assertContentEquals(toClient, clientRead.await())

            sessions.client.close()
            sessions.server.close()
        }
    }

    @Test
    fun `writes all bytes for large tiny and empty buffers`() = runBlocking {
        withTimeout(60_000) {
            val sessions = establish(testIdentity("client"), testIdentity("server"))
            val large = payload(512 * 1024, seed = 1)

            sessions.client.write(large)
            assertContentEquals(large, sessions.server.readExactly(large.size))

            sessions.client.write(byteArrayOf(0x42))
            assertEquals(0x42, sessions.server.readExactly(1).single().toInt())

            sessions.client.write(ByteArray(0))
            val chunk = payload(5, seed = 2)
            sessions.client.write(chunk)
            assertContentEquals(chunk, sessions.server.readExactly(chunk.size))

            // A write split across caller buffers must arrive intact.
            val first = payload(70_000, seed = 4)
            val second = payload(9, seed = 5)
            sessions.client.write(first)
            sessions.client.write(second)
            assertContentEquals(first, sessions.server.readExactly(first.size))
            assertContentEquals(second, sessions.server.readExactly(second.size))

            sessions.client.close()
            sessions.server.close()
        }
    }

    @Test
    fun `concurrent read and write from different coroutines are safe`() = runBlocking {
        withTimeout(60_000) {
            val sessions = establish(testIdentity("client"), testIdentity("server"))
            val clientPayload = payload(1 shl 20, seed = 11)
            val serverPayload = payload(1 shl 20, seed = 29)

            val clientWrite = async(Dispatchers.Default) { sessions.client.write(clientPayload) }
            val serverWrite = async(Dispatchers.Default) { sessions.server.write(serverPayload) }
            val serverRead = async(Dispatchers.Default) { sessions.server.readExactly(clientPayload.size) }
            val clientRead = async(Dispatchers.Default) { sessions.client.readExactly(serverPayload.size) }

            assertContentEquals(clientPayload, serverRead.await())
            assertContentEquals(serverPayload, clientRead.await())
            clientWrite.await()
            serverWrite.await()

            sessions.client.close()
            sessions.server.close()
        }
    }

    @Test
    fun `close unblocks a blocked read`() = runBlocking {
        withTimeout(30_000) {
            val sessions = establish(testIdentity("client"), testIdentity("server"))

            val pending = async(Dispatchers.Default) { sessions.server.read(ByteArray(16)) }
            delay(200)
            sessions.client.close()

            assertEquals(-1, pending.await())
            sessions.server.close()
        }
    }

    @Test
    fun `handshake times out against a silent peer and closes it`() = runBlocking {
        withTimeout(30_000) {
            val identity = testIdentity("client")
            val silent = SilentSession()
            val security = DefaultSessionSecurity(
                identity = identity,
                trustStore = InMemoryPeerTrustStore(),
                confirmPairing = { _, _ -> true },
                handshakeTimeoutMillis = 200,
            )

            val result = security.secure(silent, DeviceId("silent-peer"))

            assertTrue(result is FluxShareResult.Failure, "Expected failure but was $result")
            assertTrue((result as FluxShareResult.Failure).error == FluxShareError.SecurityFailure)
            assertFalse(silent.isOpen, "The timed-out session must be closed")
        }
    }

    @Test
    fun `plaintext never appears on the wire`() = runBlocking {
        withTimeout(30_000) {
            val sessions = establish(testIdentity("client"), testIdentity("server"))
            val marker = "FLUXSHARE-PLAINTEXT-MARKER-0123456789"
            sessions.client.write(marker.toByteArray(StandardCharsets.UTF_8))
            assertContentEquals(
                marker.toByteArray(StandardCharsets.UTF_8),
                sessions.server.readExactly(marker.length),
            )

            val wire = sessions.wire.recordedBytes()
            assertTrue(wire.isNotEmpty())
            assertFalse(
                String(wire, StandardCharsets.ISO_8859_1).contains(marker),
                "The marker was found in the ciphertext stream",
            )
            assertFalse(String(wire, StandardCharsets.ISO_8859_1).contains("FluxShare"))

            sessions.client.close()
            sessions.server.close()
        }
    }

    @Test
    fun `flipping one ciphertext byte fails the read and delivers nothing`() = runBlocking {
        withTimeout(30_000) {
            val sessions = establish(testIdentity("client"), testIdentity("server"))
            // Skip the five-byte TLS record header and corrupt ciphertext.
            sessions.wire.tamperAt = sessions.wire.clientBytesWritten + 7

            sessions.client.write("tamper target payload".toByteArray(StandardCharsets.UTF_8))
            assertTrue(sessions.wire.tampered, "The test did not actually corrupt a byte")

            assertFailsWith<FluxShareError.SecurityFailure> {
                sessions.server.read(ByteArray(64))
            }
            sessions.client.close()
            sessions.server.close()
        }
    }

    @Test
    fun `carries a protocol frame over a secure session`() = runBlocking {
        withTimeout(30_000) {
            val sessions = establish(testIdentity("client"), testIdentity("server"))
            val frame = FluxFrame.Hello(
                PeerDevice(DeviceId("frame-peer"), "Frame peer", DevicePlatform.ANDROID),
            )

            FluxFrameStream(sessions.client).write(frame)
            val received = FluxFrameStream(sessions.server).read()

            assertEquals(frame, received)
            sessions.client.close()
            sessions.server.close()
        }
    }

    @Test
    fun `works over a loopback tcp transport`() = runBlocking {
        withTimeout(60_000) {
            val clientIdentity = testIdentity("tcp-client")
            val serverIdentity = testIdentity("tcp-server")
            val transport = TcpTransport()
            val listener = transport.listen(0).getOrFail()
            try {
                val accepted = async(Dispatchers.IO) { listener.accept().getOrFail() }
                val raw = transport
                    .connect(TransportEndpoint("127.0.0.1", listener.endpoint.port))
                    .getOrFail()
                val rawServer = accepted.await()

                val clientSecurity = DefaultSessionSecurity(
                    clientIdentity,
                    InMemoryPeerTrustStore(mapOf(serverIdentity.deviceId to serverIdentity.fingerprint)),
                    { _, _ -> true },
                )
                val serverSecurity = DefaultSessionSecurity(
                    serverIdentity,
                    InMemoryPeerTrustStore(mapOf(clientIdentity.deviceId to clientIdentity.fingerprint)),
                    { _, _ -> true },
                )

                val serverDeferred = async(Dispatchers.IO) { serverSecurity.acceptSecure(rawServer) }
                val client = clientSecurity.secure(raw, serverIdentity.deviceId).getOrFail()
                val server = serverDeferred.await().getOrFail()

                assertEquals(serverIdentity.deviceId, client.remoteDeviceId)
                assertEquals(clientIdentity.deviceId, server.remoteDeviceId)

                val toServer = payload(256 * 1024, seed = 6)
                val toClient = payload(128 * 1024, seed = 7)
                val serverRead = async(Dispatchers.IO) { server.readExactly(toServer.size) }
                client.write(toServer)
                assertContentEquals(toServer, serverRead.await())

                val clientRead = async(Dispatchers.IO) { client.readExactly(toClient.size) }
                server.write(toClient)
                assertContentEquals(toClient, clientRead.await())

                client.close()
                server.close()
            } finally {
                listener.close()
            }
        }
    }
}
