package app.fluxshare.transport.tcp

import app.fluxshare.core.FluxShareError
import app.fluxshare.core.FluxShareResult
import app.fluxshare.transport.TransportEndpoint
import app.fluxshare.transport.TransportSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class TcpTransportTest {

    @Test
    fun `streams a large payload over loopback`() = runBlocking {
        withTimeout(30_000) {
            val transport = TcpTransport()
            val listener = transport.listen(0).requireSuccess()
            try {
                val serverDeferred = async(Dispatchers.IO) { listener.accept().requireSuccess() }
                val client = transport.connect(
                    TransportEndpoint("127.0.0.1", listener.endpoint.port),
                ).requireSuccess()
                val server = serverDeferred.await()

                val payload = ByteArray(1 shl 20) { index -> (index % 251).toByte() }
                val writer = launch(Dispatchers.IO) { client.write(payload) }

                val received = readExactly(server, payload.size)
                writer.join()

                assertContentEquals(payload, received)
                assertTrue(client.isOpen)

                client.close()
                assertEquals(-1, server.read(ByteArray(1)))
                assertFalse(client.isOpen)

                server.close()
            } finally {
                listener.close()
            }
        }
    }

    @Test
    fun `accept fails after the listener is closed`() = runBlocking {
        withTimeout(10_000) {
            val transport = TcpTransport()
            val listener = transport.listen(0).requireSuccess()
            listener.close()

            val result = listener.accept()
            assertTrue(result is FluxShareResult.Failure, "Expected failure but was $result")
        }
    }

    @Test
    fun `connect fails when nothing is listening`() = runBlocking {
        withTimeout(10_000) {
            val port = ServerSocket(0).use { it.localPort }
            val result = TcpTransport().connect(TransportEndpoint("127.0.0.1", port))
            assertTrue(result is FluxShareResult.Failure, "Expected failure but was $result")
        }
    }

    @Test
    fun `closing a session unblocks a pending read`() = runBlocking<Unit> {
        withTimeout(15_000) {
            val transport = TcpTransport()
            val listener = transport.listen(0).requireSuccess()
            try {
                val serverDeferred = async(Dispatchers.IO) { listener.accept().requireSuccess() }
                val client = transport.connect(
                    TransportEndpoint("127.0.0.1", listener.endpoint.port),
                ).requireSuccess()
                val server = serverDeferred.await()

                val pendingRead = async(Dispatchers.IO) { server.read(ByteArray(8)) }
                delay(200)
                server.close()

                assertEquals(-1, pendingRead.await())
                client.close()
            } finally {
                listener.close()
            }
        }
    }

    @Test
    fun `writes after close are rejected and close is idempotent`() = runBlocking<Unit> {
        withTimeout(15_000) {
            val transport = TcpTransport()
            val listener = transport.listen(0).requireSuccess()
            try {
                val serverDeferred = async(Dispatchers.IO) { listener.accept().requireSuccess() }
                val client = transport.connect(
                    TransportEndpoint("127.0.0.1", listener.endpoint.port),
                ).requireSuccess()
                val server = serverDeferred.await()

                client.close()
                client.close()
                assertFalse(client.isOpen)
                assertFailsWith<FluxShareError.Unexpected> { client.write(byteArrayOf(1)) }

                server.close()
            } finally {
                listener.close()
            }
        }
    }

    private suspend fun readExactly(session: TransportSession, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = session.read(buffer, read, size - read)
            if (count < 0) fail("Peer closed after $read of $size bytes")
            read += count
        }
        return buffer
    }

    private fun <T> FluxShareResult<T>.requireSuccess(): T = when (this) {
        is FluxShareResult.Success -> value
        is FluxShareResult.Failure -> fail("Expected success but got $error")
    }
}
