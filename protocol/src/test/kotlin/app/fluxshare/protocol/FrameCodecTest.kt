package app.fluxshare.protocol

import app.fluxshare.core.DeviceId
import app.fluxshare.core.DevicePlatform
import app.fluxshare.core.FluxShareError
import app.fluxshare.core.PeerDevice
import app.fluxshare.core.TransferId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FrameCodecTest {
    private val transferId = TransferId("transfer-1")

    private fun hello(): FluxFrame.Hello = FluxFrame.Hello(
        peer = PeerDevice(
            id = DeviceId("device-1"),
            displayName = "Pixel",
            platform = DevicePlatform.ANDROID,
        ),
    )

    private fun manifest(): FluxManifest = FluxManifest(
        transferId = transferId,
        files = listOf(
            ManifestEntry("photo.jpg", 1_024L, "image/jpeg"),
            ManifestEntry("notes.txt", 0L, sha256 = ByteArray(32) { it.toByte() }),
        ),
    )

    @Test
    fun `hello frame round trips`() {
        val decoded = FluxFrameCodec.decode(FluxFrameCodec.encode(hello()))
        val hello = decoded as FluxFrame.Hello
        assertEquals("device-1", hello.peer.id.value)
        assertEquals("Pixel", hello.peer.displayName)
        assertEquals(DevicePlatform.ANDROID, hello.peer.platform)
    }

    @Test
    fun `manifest frame round trips`() {
        val source = manifest()
        val decoded = FluxFrameCodec.decode(FluxFrameCodec.encode(FluxFrame.Manifest(source)))
        val manifest = (decoded as FluxFrame.Manifest).manifest

        assertEquals(transferId, manifest.transferId)
        assertEquals(2, manifest.files.size)
        assertEquals("photo.jpg", manifest.files[0].name)
        assertEquals("image/jpeg", manifest.files[0].mimeType)
        assertEquals(1_024L, manifest.totalBytes)
        assertContentEquals(source.files[1].sha256, manifest.files[1].sha256)
    }

    @Test
    fun `data frame round trips`() {
        val bytes = ByteArray(4_096) { (it % 251).toByte() }
        val decoded = FluxFrameCodec.decode(
            FluxFrameCodec.encode(FluxFrame.Data(fileIndex = 1, offset = 8_192L, bytes = bytes)),
        )
        val data = decoded as FluxFrame.Data
        assertEquals(1, data.fileIndex)
        assertEquals(8_192L, data.offset)
        assertContentEquals(bytes, data.bytes)
    }

    @Test
    fun `digest response and error frames round trip`() {
        val digest = ByteArray(32) { (0xFF - it).toByte() }
        val decodedDigest = FluxFrameCodec.decode(FluxFrameCodec.encode(FluxFrame.FileDigest(2, digest)))
        assertContentEquals(digest, (decodedDigest as FluxFrame.FileDigest).sha256)

        val response = FluxFrameCodec.decode(
            FluxFrameCodec.encode(
                FluxFrame.ManifestResponse(transferId, accepted = false, reason = "No space left"),
            ),
        ) as FluxFrame.ManifestResponse
        assertEquals(transferId, response.transferId)
        assertEquals(false, response.accepted)
        assertEquals("No space left", response.reason)

        val error = FluxFrameCodec.decode(
            FluxFrameCodec.encode(FluxFrame.Error(ProtocolErrorCode.CHECKSUM_MISMATCH, "bad digest")),
        ) as FluxFrame.Error
        assertEquals(ProtocolErrorCode.CHECKSUM_MISMATCH, error.code)
        assertEquals("bad digest", error.message)
    }

    @Test
    fun `transfer complete frame round trips`() {
        val decoded = FluxFrameCodec.decode(
            FluxFrameCodec.encode(FluxFrame.TransferComplete(transferId)),
        )
        assertEquals(transferId, (decoded as FluxFrame.TransferComplete).transferId)
    }

    @Test
    fun `transfer complete frame rejects trailing bytes and invalid identifiers`() {
        val encoded = FluxFrameCodec.encode(FluxFrame.TransferComplete(transferId))
        assertFailsWith<FluxShareError.ProtocolViolation> {
            FluxFrameCodec.decode(encoded + byteArrayOf(0))
        }

        val blank = PayloadWriter().apply {
            writeString("", ProtocolLimits.MAX_TRANSFER_ID_BYTES, "transfer identifier")
        }.toByteArray()
        assertFailsWith<FluxShareError.ProtocolViolation> {
            FluxFrameCodec.decode(
                header(typeId = FrameType.TRANSFER_COMPLETE.id, payloadLength = blank.size) + blank,
            )
        }

        val truncated = PayloadWriter().apply { writeU16(8) }.toByteArray()
        assertFailsWith<FluxShareError.ProtocolViolation> {
            FluxFrameCodec.decode(
                header(typeId = FrameType.TRANSFER_COMPLETE.id, payloadLength = truncated.size) + truncated,
            )
        }
    }

    @Test
    fun `rejects a bad magic`() {
        val bytes = FluxFrameCodec.encode(hello())
        bytes[0] = 'X'.code.toByte()
        assertFailsWith<FluxShareError.ProtocolViolation> { FluxFrameCodec.decode(bytes) }
    }

    @Test
    fun `rejects an unsupported protocol version`() {
        val bytes = FluxFrameCodec.encode(hello())
        bytes[4] = 2
        assertFailsWith<FluxShareError.ProtocolViolation> { FluxFrameCodec.decode(bytes) }
    }

    @Test
    fun `rejects an oversized declared payload`() {
        val header = header(typeId = FrameType.HELLO.id, payloadLength = ProtocolLimits.MAX_PAYLOAD_BYTES + 1)
        assertFailsWith<FluxShareError.ProtocolViolation> { FluxFrameCodec.decodeHeader(header) }
    }

    @Test
    fun `rejects a negative declared payload`() {
        val header = header(typeId = FrameType.HELLO.id, payloadLength = -1)
        assertFailsWith<FluxShareError.ProtocolViolation> { FluxFrameCodec.decodeHeader(header) }
    }

    @Test
    fun `rejects an unknown frame type`() {
        val header = header(typeId = 0x7F, payloadLength = 0)
        assertFailsWith<FluxShareError.ProtocolViolation> { FluxFrameCodec.decodeHeader(header) }
    }

    @Test
    fun `rejects trailing bytes after the declared payload`() {
        val bytes = FluxFrameCodec.encode(hello()) + byteArrayOf(0)
        assertFailsWith<FluxShareError.ProtocolViolation> { FluxFrameCodec.decode(bytes) }
    }

    @Test
    fun `rejects a truncated header`() {
        assertFailsWith<FluxShareError.ProtocolViolation> {
            FluxFrameCodec.decodeHeader(ByteArray(ProtocolLimits.FRAME_HEADER_BYTES - 1))
        }
    }

    @Test
    fun `rejects unknown flags`() {
        val header = header(typeId = FrameType.HELLO.id, payloadLength = 0, flags = 1)
        assertFailsWith<FluxShareError.ProtocolViolation> { FluxFrameCodec.decodeHeader(header) }
    }

    @Test
    fun `rejects control characters in text fields`() {
        val payload = PayloadWriter().apply {
            writeString("device-1", ProtocolLimits.MAX_DEVICE_ID_BYTES, "device identifier")
            writeU16(5)
            writeRaw(byteArrayOf('b'.code.toByte(), 0x07, 'a'.code.toByte(), 'd'.code.toByte(), '!'.code.toByte()))
            writeByte(1)
        }.toByteArray()
        val frame = header(typeId = FrameType.HELLO.id, payloadLength = payload.size) + payload

        assertFailsWith<FluxShareError.ProtocolViolation> { FluxFrameCodec.decode(frame) }
    }

    @Test
    fun `rejects an empty data payload`() {
        val payload = PayloadWriter().apply {
            writeI32(0)
            writeI64(0L)
        }.toByteArray()
        val frame = header(typeId = FrameType.DATA.id, payloadLength = payload.size) + payload

        assertFailsWith<FluxShareError.ProtocolViolation> { FluxFrameCodec.decode(frame) }
    }

    private fun header(typeId: Int, payloadLength: Int, flags: Int = 0): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(ProtocolLimits.FRAME_HEADER_BYTES)
            .order(java.nio.ByteOrder.BIG_ENDIAN)
        buffer.putInt(FluxFrameCodec.MAGIC)
        buffer.put(FluxFrameCodec.VERSION.toByte())
        buffer.put(typeId.toByte())
        buffer.putShort(flags.toShort())
        buffer.putInt(payloadLength)
        return buffer.array()
    }
}
