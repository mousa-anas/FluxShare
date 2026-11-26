package app.fluxshare.protocol

import java.security.MessageDigest

/**
 * Incremental SHA-256 over a stream of chunks.
 *
 * Transfer senders and receivers hash data as it moves instead of buffering a
 * whole file, so verification costs no extra memory and only one pass.
 *
 * Instances are not thread-safe; use one accumulator per transfer direction.
 */
class IncrementalSha256 {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var processedBytes = 0L
    private var finished = false

    /** Number of bytes fed into the digest so far. */
    val bytesHashed: Long
        get() = processedBytes

    /** Adds [length] bytes from [buffer], starting at [offset]. */
    fun update(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
        require(!finished) { "Digest has already been finalized" }
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size) {
            "Range [$offset, ${offset + length}) is outside a ${buffer.size}-byte buffer"
        }
        if (length == 0) return
        digest.update(buffer, offset, length)
        processedBytes += length
    }

    /**
     * Finalizes and returns the 32-byte digest.
     *
     * The accumulator cannot be updated afterwards; callers that need another
     * pass over the same data should create a new instance.
     */
    fun digest(): ByteArray {
        require(!finished) { "Digest has already been finalized" }
        finished = true
        return digest.digest()
    }

    companion object {
        /** Convenience one-shot hash for small buffers. */
        fun of(buffer: ByteArray): ByteArray = IncrementalSha256().apply { update(buffer) }.digest()
    }
}

/** Lowercase hexadecimal representation, as used by manifests and logs. */
fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        out.append(digits[value ushr 4])
        out.append(digits[value and 0x0F])
    }
    return out.toString()
}

/** SHA-256 of this array as lowercase hexadecimal. */
fun ByteArray.sha256Hex(): String = IncrementalSha256.of(this).toHex()
