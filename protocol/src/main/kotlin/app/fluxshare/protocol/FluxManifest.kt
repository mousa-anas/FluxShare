package app.fluxshare.protocol

import app.fluxshare.core.TransferId

/**
 * One file offered in a manifest.
 *
 * [sha256], when present, is the digest the sender commits to. Receivers must
 * still verify the bytes they actually wrote before trusting it.
 */
data class ManifestEntry(
    val name: String,
    val sizeBytes: Long,
    val mimeType: String? = null,
    val sha256: ByteArray? = null,
)

/** The set of files belonging to one transfer. */
data class FluxManifest(
    val transferId: TransferId,
    val files: List<ManifestEntry>,
) {
    /** Total size across all entries. Safe because manifests are validated. */
    val totalBytes: Long
        get() = files.fold(0L) { total, entry -> total + entry.sizeBytes }
}
