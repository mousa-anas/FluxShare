package app.fluxshare.protocol

/**
 * Hard bounds applied to every FluxShare protocol message.
 *
 * The limits are deliberately generous for real transfers but small enough that
 * a malicious or buggy peer cannot make the receiver allocate unbounded memory.
 */
object ProtocolLimits {
    /** Maximum encoded frame payload, excluding the 12-byte frame header. */
    const val MAX_PAYLOAD_BYTES: Int = 8 * 1024 * 1024

    /** Maximum encoded frame header size: magic, version, type, flags, length. */
    const val FRAME_HEADER_BYTES: Int = 12

    /** Maximum number of files in a single manifest. */
    const val MAX_MANIFEST_FILES: Int = 4096

    /** Maximum UTF-8 size of a file name inside a manifest. */
    const val MAX_FILE_NAME_BYTES: Int = 1024

    /** Maximum UTF-8 size of a MIME type. */
    const val MAX_MIME_TYPE_BYTES: Int = 255

    /** Maximum UTF-8 size of a device display name. */
    const val MAX_DISPLAY_NAME_BYTES: Int = 128

    /** Maximum UTF-8 size of a device identifier on the wire. */
    const val MAX_DEVICE_ID_BYTES: Int = 128

    /** Maximum UTF-8 size of a manifest identifier on the wire. */
    const val MAX_TRANSFER_ID_BYTES: Int = 128

    /** Maximum UTF-8 size of a rejection reason or error message. */
    const val MAX_TEXT_BYTES: Int = 512

    /** Size of a SHA-256 digest. */
    const val SHA256_BYTES: Int = 32
}
