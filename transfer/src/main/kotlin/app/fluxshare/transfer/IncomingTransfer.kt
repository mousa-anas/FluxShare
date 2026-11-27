package app.fluxshare.transfer

import app.fluxshare.core.PeerDevice
import app.fluxshare.core.TransferId
import app.fluxshare.protocol.ManifestEntry

/**
 * A transfer offered by a remote device, shown to the approval callback before
 * any file content is read.
 */
data class IncomingTransfer(
    val peer: PeerDevice,
    val transferId: TransferId,
    val files: List<ManifestEntry>,
) {
    /** Sum of the declared file sizes. */
    val totalBytes: Long
        get() = files.fold(0L) { total, file -> total + file.sizeBytes }
}

/** What the local device decided about an [IncomingTransfer]. */
sealed interface TransferDecision {
    /** Accept the files and start receiving them. */
    data object Accept : TransferDecision

    /** Refuse the transfer; [reason] is sent to the sender when it fits the wire limit. */
    data class Reject(val reason: String? = null) : TransferDecision
}
