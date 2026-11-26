package app.fluxshare.protocol

import app.fluxshare.core.DeviceId
import app.fluxshare.core.DevicePlatform
import app.fluxshare.core.FluxShareError
import app.fluxshare.core.PeerDevice
import app.fluxshare.transport.TransportEndpoint
import app.fluxshare.transport.TransportSession
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FluxFrameStreamTest {
    private val hello = FluxFrame.Hello(
        PeerDevice(DeviceId("device-2"), "Laptop", DevicePlatform.WINDOWS),
    )

    @Test
    fun `reads consecutive frames across partial reads`() = runBlocking {
        val second = FluxFrame.Data(0, 512L, ByteArray(64) { it.toByte() })
        val bytes = FluxFrameCodec.encode(hello) + FluxFrameCodec.encode(second)
        val stream = FluxFrameStream(ReadOnlySession(bytes, maxChunk = 3))

        val first = stream.read() as FluxFrame.Hello
        assertEquals("Laptop", first.peer.displayName)
        assertEquals(DevicePlatform.WINDOWS, first.peer.platform)

        val data = stream.read() as FluxFrame.Data
        assertEquals(512L, data.offset)
        assertEquals(64, data.bytes.size)

        assertNull(stream.read())
    }

    @Test
    fun `reports a clean end of stream`() = runBlocking {
        assertNull(FluxFrameStream(ReadOnlySession(ByteArray(0))).read())
    }

    @Test
    fun `rejects a truncated frame`() = runBlocking<Unit> {
        val bytes = FluxFrameCodec.encode(hello)
        val truncated = bytes.copyOfRange(0, bytes.size - 1)
        assertFailsWith<FluxShareError.ProtocolViolation> {
            FluxFrameStream(ReadOnlySession(truncated)).read()
        }
    }

    @Test
    fun `rejects a bad magic on the wire`() = runBlocking<Unit> {
        val bytes = FluxFrameCodec.encode(hello)
        bytes[0] = 'Z'.code.toByte()
        assertFailsWith<FluxShareError.ProtocolViolation> {
            FluxFrameStream(ReadOnlySession(bytes)).read()
        }
    }

    @Test
    fun `writes one complete encoded frame`() = runBlocking {
        val session = CaptureSession()
        FluxFrameStream(session).write(hello)

        val decoded = FluxFrameCodec.decode(session.captured()) as FluxFrame.Hello
        assertEquals("device-2", decoded.peer.id.value)
        assertTrue(session.captured().size > ProtocolLimits.FRAME_HEADER_BYTES)
    }

    @Test
    fun `rejects a session that stops making progress`() = runBlocking<Unit> {
        val bytes = FluxFrameCodec.encode(hello)
        assertFailsWith<FluxShareError.ProtocolViolation> {
            FluxFrameStream(StallingSession(bytes, firstBytes = 1)).read()
        }
    }

    private class StallingSession(
        private val prefix: ByteArray,
        private val firstBytes: Int,
    ) : TransportSession {
        private var delivered = 0

        override val endpoint: TransportEndpoint = TransportEndpoint("test.local", 3)
        override val isOpen: Boolean = true

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (delivered >= prefix.size) return -1
            if (delivered >= firstBytes) return 0
            buffer[offset] = prefix[delivered]
            delivered++
            return 1
        }

        override suspend fun write(buffer: ByteArray, offset: Int, length: Int) = Unit

        override suspend fun close() = Unit
    }

    private class ReadOnlySession(
        private val bytes: ByteArray,
        private val maxChunk: Int = Int.MAX_VALUE,
    ) : TransportSession {
        private var position = 0

        override val endpoint: TransportEndpoint = TransportEndpoint("test.local", 1)
        override val isOpen: Boolean = true

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, maxChunk, bytes.size - position)
            bytes.copyInto(
                destination = buffer,
                destinationOffset = offset,
                startIndex = position,
                endIndex = position + count,
            )
            position += count
            return count
        }

        override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
            throw UnsupportedOperationException("read-only session")
        }

        override suspend fun close() = Unit
    }

    private class CaptureSession : TransportSession {
        private val output = ByteArrayOutputStream()

        override val endpoint: TransportEndpoint = TransportEndpoint("test.local", 2)
        override val isOpen: Boolean = true

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1

        override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
            output.write(buffer, offset, length)
        }

        override suspend fun close() = Unit

        fun captured(): ByteArray = output.toByteArray()
    }
}
