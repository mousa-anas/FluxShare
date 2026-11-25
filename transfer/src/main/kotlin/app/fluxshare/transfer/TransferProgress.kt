package app.fluxshare.transfer

import app.fluxshare.core.TransferId

/** A progress sample for a running transfer. */
data class TransferProgress(
    val transferId: TransferId,
    val state: TransferState,
    val bytesTransferred: Long,
    val totalBytes: Long,
) {
    /** Completed fraction in the range `0f..1f`; `0f` when the size is unknown. */
    val fraction: Float
        get() = if (totalBytes <= 0L) {
            0f
        } else {
            (bytesTransferred.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
        }
}
