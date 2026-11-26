package app.fluxshare.protocol

import app.fluxshare.core.FluxShareError
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Builds a frame payload.
 *
 * Strings are written as a 16-bit unsigned byte length followed by strict
 * UTF-8, which keeps the format compact and unambiguous without depending on a
 * serialization library.
 */
internal class PayloadWriter(initialCapacity: Int = 64) {
    private val buffer = ByteArrayOutputStream(initialCapacity)
    private val out = DataOutputStream(buffer)

    fun writeByte(value: Int) {
        require(value in 0..0xFF) { "Byte value out of range: $value" }
        out.writeByte(value)
    }

    fun writeFlag(value: Boolean) {
        out.writeByte(if (value) 1 else 0)
    }

    fun writeU16(value: Int) {
        require(value in 0..0xFFFF) { "Unsigned 16-bit value out of range: $value" }
        out.writeShort(value)
    }

    fun writeI32(value: Int) {
        out.writeInt(value)
    }

    fun writeI64(value: Long) {
        out.writeLong(value)
    }

    fun writeRaw(value: ByteArray) {
        out.write(value)
    }

    fun writeString(value: String, maxBytes: Int, field: String) {
        val encoded = encodeUtf8(value, maxBytes, field)
        out.writeShort(encoded.size)
        out.write(encoded)
    }

    fun writeOptionalString(value: String?, maxBytes: Int, field: String) {
        if (value == null) {
            out.writeByte(0)
            return
        }
        out.writeByte(1)
        writeString(value, maxBytes, field)
    }

    fun writeOptionalDigest(value: ByteArray?, field: String) {
        if (value == null) {
            out.writeByte(0)
            return
        }
        if (value.size != ProtocolLimits.SHA256_BYTES) {
            throw FluxShareError.ProtocolViolation("$field must be ${ProtocolLimits.SHA256_BYTES} bytes.")
        }
        out.writeByte(1)
        out.write(value)
    }

    fun toByteArray(): ByteArray {
        out.flush()
        return buffer.toByteArray()
    }

    private fun encodeUtf8(value: String, maxBytes: Int, field: String): ByteArray {
        // Control characters are never meaningful protocol text and are a common
        // log/terminal injection vector, so reject them in both directions.
        if (value.any { it.isISOControl() }) {
            throw FluxShareError.ProtocolViolation("$field contains control characters.")
        }
        val encoded = value.toByteArray(Charsets.UTF_8)
        if (encoded.size > maxBytes) {
            throw FluxShareError.ProtocolViolation("$field exceeds $maxBytes bytes.")
        }
        if (encoded.size > 0xFFFF) {
            throw FluxShareError.ProtocolViolation("$field is too long to encode.")
        }
        return encoded
    }
}

/**
 * Reads a frame payload and rejects any malformed or truncated content.
 *
 * Every read is bounds-checked against the payload, so a peer cannot make the
 * receiver read past the declared frame.
 */
internal class PayloadReader(private val payload: ByteArray) {
    private val input = DataInputStream(ByteArrayInputStream(payload))

    fun readByte(field: String): Int = guard(field) { input.readUnsignedByte() }

    fun readFlag(field: String): Boolean = when (val value = readByte(field)) {
        0 -> false
        1 -> true
        else -> throw FluxShareError.ProtocolViolation("$field must be 0 or 1, was $value.")
    }

    fun readU16(field: String): Int = guard(field) { input.readUnsignedShort() }

    fun readI32(field: String): Int = guard(field) { input.readInt() }

    fun readI64(field: String): Long = guard(field) { input.readLong() }

    fun readBytes(count: Int, field: String): ByteArray {
        if (count < 0 || count > payload.size) {
            throw FluxShareError.ProtocolViolation("$field has an invalid length of $count bytes.")
        }
        val bytes = ByteArray(count)
        guard(field) { input.readFully(bytes) }
        return bytes
    }

    fun readRemaining(field: String): ByteArray = readBytes(remainingBytes(), field)

    fun remainingBytes(): Int = input.available()

    fun readString(maxBytes: Int, field: String): String {
        val length = readU16("$field length")
        if (length > maxBytes) {
            throw FluxShareError.ProtocolViolation("$field exceeds $maxBytes bytes.")
        }
        val bytes = readBytes(length, field)
        return decodeUtf8(bytes, field)
    }

    fun readOptionalString(maxBytes: Int, field: String): String? {
        return when (readFlag("$field presence")) {
            false -> null
            true -> readString(maxBytes, field)
        }
    }

    fun readOptionalDigest(field: String): ByteArray? {
        return when (readFlag("$field presence")) {
            false -> null
            true -> readBytes(ProtocolLimits.SHA256_BYTES, field)
        }
    }

    fun requireExhausted() {
        val remaining = remainingBytes()
        if (remaining != 0) {
            throw FluxShareError.ProtocolViolation("Payload has $remaining unexpected trailing bytes.")
        }
    }

    private inline fun <T> guard(field: String, block: () -> T): T {
        return try {
            block()
        } catch (e: EOFException) {
            throw FluxShareError.ProtocolViolation("Payload is truncated while reading $field.")
        }
    }

    private fun decodeUtf8(bytes: ByteArray, field: String): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw FluxShareError.ProtocolViolation("$field is not valid UTF-8.")
        }
        if (decoded.any { it.isISOControl() }) {
            throw FluxShareError.ProtocolViolation("$field contains control characters.")
        }
        return decoded
    }
}
