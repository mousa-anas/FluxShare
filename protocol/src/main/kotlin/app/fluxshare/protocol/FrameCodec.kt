package app.fluxshare.protocol

import app.fluxshare.core.FluxShareError
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bit flags reserved for forward-compatible header extensions. */
object FrameFlags {
    /** No flags are defined in protocol version 1; anything else is rejected. */
    const val NONE: Int = 0
}

/** Parsed and validated frame header. */
data class FrameHeader(
    val version: Int,
    val type: FrameType,
    val flags: Int,
    val payloadLength: Int,
)

/** Wire identifiers for every frame kind. */
enum class FrameType(val id: Int) {
    HELLO(0x01),
    MANIFEST(0x02),
    MANIFEST_RESPONSE(0x03),
    DATA(0x04),
    FILE_DIGEST(0x05),
    ERROR(0x06),
    ;

    companion object {
        fun fromId(id: Int): FrameType = entries.firstOrNull { it.id == id }
            ?: throw FluxShareError.ProtocolViolation("Unknown frame type 0x${id.toString(16)}.")
    }
}

/**
 * Encodes and decodes FluxShare frames.
 *
 * Wire layout:
 * ```
 * magic    : u32  = 0x464C5853 ("FLXS")
 * version  : u8   = 1
 * type     : u8
 * flags    : u16  = 0 in protocol version 1
 * length   : u32  payload byte count, at most ProtocolLimits.MAX_PAYLOAD_BYTES
 * payload  : length bytes
 * ```
 *
 * Decoding never trusts declared lengths: the frame must contain exactly the
 * payload it announces, and every inner field is bounds-checked.
 */
object FluxFrameCodec {
    /** `FLXS` in ASCII. */
    const val MAGIC: Int = 0x464C5853

    /** The only protocol version this build speaks. */
    const val VERSION: Int = 1

    /** Encodes [frame] into a complete frame, header included. */
    fun encode(frame: FluxFrame): ByteArray {
        val type = frame.type()
        val payload = encodePayload(frame)
        if (payload.size > ProtocolLimits.MAX_PAYLOAD_BYTES) {
            throw FluxShareError.ProtocolViolation(
                "Frame payload is ${payload.size} bytes, above the limit of ${ProtocolLimits.MAX_PAYLOAD_BYTES}.",
            )
        }

        val header = ByteBuffer.allocate(ProtocolLimits.FRAME_HEADER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC)
            .put(VERSION.toByte())
            .put(type.id.toByte())
            .putShort(FrameFlags.NONE.toShort())
            .putInt(payload.size)
            .array()

        val encoded = ByteArray(header.size + payload.size)
        header.copyInto(encoded, destinationOffset = 0)
        payload.copyInto(encoded, destinationOffset = header.size)
        return encoded
    }

    /** Parses the first 12 bytes of a frame, or the whole array when it is a header. */
    fun decodeHeader(header: ByteArray): FrameHeader {
        if (header.size < ProtocolLimits.FRAME_HEADER_BYTES) {
            throw FluxShareError.ProtocolViolation("Frame header is truncated.")
        }
        val buffer = ByteBuffer.wrap(header, 0, ProtocolLimits.FRAME_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        val magic = buffer.int
        if (magic != MAGIC) {
            throw FluxShareError.ProtocolViolation("Bad frame magic 0x${Integer.toHexString(magic)}.")
        }
        val version = buffer.get().toInt() and 0xFF
        if (version != VERSION) {
            throw FluxShareError.ProtocolViolation("Unsupported protocol version $version.")
        }
        val type = FrameType.fromId(buffer.get().toInt() and 0xFF)
        val flags = buffer.short.toInt() and 0xFFFF
        if (flags != FrameFlags.NONE) {
            throw FluxShareError.ProtocolViolation("Unsupported frame flags 0x${Integer.toHexString(flags)}.")
        }
        val payloadLength = buffer.int
        if (payloadLength < 0 || payloadLength > ProtocolLimits.MAX_PAYLOAD_BYTES) {
            throw FluxShareError.ProtocolViolation("Invalid frame payload length $payloadLength.")
        }
        return FrameHeader(version = version, type = type, flags = flags, payloadLength = payloadLength)
    }

    /** Decodes one complete frame from [bytes]. */
    fun decode(bytes: ByteArray): FluxFrame {
        val header = decodeHeader(bytes)
        val expectedSize = ProtocolLimits.FRAME_HEADER_BYTES + header.payloadLength
        if (bytes.size != expectedSize) {
            throw FluxShareError.ProtocolViolation(
                "Frame declares ${header.payloadLength} payload bytes but ${bytes.size - ProtocolLimits.FRAME_HEADER_BYTES} were supplied.",
            )
        }
        val payload = bytes.copyOfRange(ProtocolLimits.FRAME_HEADER_BYTES, bytes.size)
        return decodePayload(header.type, payload)
    }

    private fun FluxFrame.type(): FrameType = when (this) {
        is FluxFrame.Hello -> FrameType.HELLO
        is FluxFrame.Manifest -> FrameType.MANIFEST
        is FluxFrame.ManifestResponse -> FrameType.MANIFEST_RESPONSE
        is FluxFrame.Data -> FrameType.DATA
        is FluxFrame.FileDigest -> FrameType.FILE_DIGEST
        is FluxFrame.Error -> FrameType.ERROR
    }

    private fun encodePayload(frame: FluxFrame): ByteArray {
        val writer = PayloadWriter()
        when (frame) {
            is FluxFrame.Hello -> {
                writer.writeString(
                    frame.peer.id.value,
                    ProtocolLimits.MAX_DEVICE_ID_BYTES,
                    "device identifier",
                )
                writer.writeString(
                    frame.peer.displayName,
                    ProtocolLimits.MAX_DISPLAY_NAME_BYTES,
                    "display name",
                )
                writer.writeByte(frame.peer.platform.toWireId())
            }

            is FluxFrame.Manifest -> return ManifestCodec.encode(frame.manifest)

            is FluxFrame.ManifestResponse -> {
                writer.writeString(
                    frame.transferId.value,
                    ProtocolLimits.MAX_TRANSFER_ID_BYTES,
                    "transfer identifier",
                )
                writer.writeFlag(frame.accepted)
                writer.writeOptionalString(frame.reason, ProtocolLimits.MAX_TEXT_BYTES, "reason")
            }

            is FluxFrame.Data -> {
                require(frame.fileIndex >= 0) { "fileIndex must not be negative" }
                require(frame.offset >= 0L) { "offset must not be negative" }
                require(frame.bytes.isNotEmpty()) { "data frame must carry at least one byte" }
                writer.writeI32(frame.fileIndex)
                writer.writeI64(frame.offset)
                writer.writeRaw(frame.bytes)
            }

            is FluxFrame.FileDigest -> {
                require(frame.fileIndex >= 0) { "fileIndex must not be negative" }
                writer.writeI32(frame.fileIndex)
                writer.writeOptionalDigest(frame.sha256, "digest")
            }

            is FluxFrame.Error -> {
                writer.writeByte(frame.code.id)
                writer.writeOptionalString(
                    frame.message,
                    ProtocolLimits.MAX_TEXT_BYTES,
                    "message",
                )
            }
        }
        return writer.toByteArray()
    }

    /** Decodes an already-read payload. Internal so the stream avoids a second copy. */
    internal fun decodePayload(type: FrameType, payload: ByteArray): FluxFrame {
        // Manifests have their own self-contained codec, so decode them first.
        if (type == FrameType.MANIFEST) {
            return FluxFrame.Manifest(ManifestCodec.decode(payload))
        }

        val reader = PayloadReader(payload)
        val frame = when (type) {
            FrameType.HELLO -> {
                val id = reader.readString(ProtocolLimits.MAX_DEVICE_ID_BYTES, "device identifier")
                val displayName = reader.readString(
                    ProtocolLimits.MAX_DISPLAY_NAME_BYTES,
                    "display name",
                )
                if (displayName.isBlank()) {
                    throw FluxShareError.ProtocolViolation("Display name must not be blank.")
                }
                val platform = devicePlatformFromWireId(reader.readByte("platform"))
                FluxFrame.Hello(peerDeviceFromWire(id, displayName, platform))
            }

            FrameType.MANIFEST -> error("Manifest frames are decoded before the shared reader")

            FrameType.MANIFEST_RESPONSE -> {
                val transferId = try {
                    app.fluxshare.core.TransferId(
                        reader.readString(ProtocolLimits.MAX_TRANSFER_ID_BYTES, "transfer identifier"),
                    )
                } catch (e: IllegalArgumentException) {
                    throw FluxShareError.ProtocolViolation("Transfer identifier is invalid: ${e.message}")
                }
                val accepted = reader.readFlag("accepted")
                val reason = reader.readOptionalString(ProtocolLimits.MAX_TEXT_BYTES, "reason")
                FluxFrame.ManifestResponse(transferId, accepted, reason)
            }

            FrameType.DATA -> {
                val fileIndex = reader.readI32("file index")
                val offset = reader.readI64("offset")
                if (fileIndex < 0) {
                    throw FluxShareError.ProtocolViolation("File index must not be negative.")
                }
                if (offset < 0L) {
                    throw FluxShareError.ProtocolViolation("Offset must not be negative.")
                }
                val bytes = reader.readRemaining("chunk")
                if (bytes.isEmpty()) {
                    throw FluxShareError.ProtocolViolation("Data frame carries no bytes.")
                }
                FluxFrame.Data(fileIndex = fileIndex, offset = offset, bytes = bytes)
            }

            FrameType.FILE_DIGEST -> {
                val fileIndex = reader.readI32("file index")
                if (fileIndex < 0) {
                    throw FluxShareError.ProtocolViolation("File index must not be negative.")
                }
                val digest = reader.readOptionalDigest("digest")
                    ?: throw FluxShareError.ProtocolViolation("File digest frame has no digest.")
                FluxFrame.FileDigest(fileIndex, digest)
            }

            FrameType.ERROR -> {
                val code = ProtocolErrorCode.fromId(reader.readByte("error code"))
                val message = reader.readOptionalString(ProtocolLimits.MAX_TEXT_BYTES, "message")
                FluxFrame.Error(code, message)
            }
        }
        reader.requireExhausted()
        return frame
    }
}
