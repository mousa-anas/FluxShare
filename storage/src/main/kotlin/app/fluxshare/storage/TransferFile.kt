package app.fluxshare.storage

/**
 * A file taking part in a transfer.
 *
 * [handle] is an opaque, platform-specific reference resolved by the
 * [StorageProvider], such as a content URI on Android or a path on Windows.
 */
data class TransferFile(
    val handle: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String? = null,
)
