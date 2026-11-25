package app.fluxshare.transfer

import app.fluxshare.core.PeerDevice
import app.fluxshare.storage.TransferFile

/** A request to move [files] to or from [peer]. */
data class TransferRequest(
    val peer: PeerDevice,
    val direction: TransferDirection,
    val files: List<TransferFile>,
)
