package app.fluxshare.transfer

import app.fluxshare.core.FluxShareResult
import app.fluxshare.core.PeerDevice
import app.fluxshare.transport.TransportEndpoint

/**
 * Finds the transport address of a peer.
 *
 * Discovery is deliberately outside the transfer layer, so [TransferRequest]
 * only names the [PeerDevice]. Callers that already know the address can return
 * it directly; a discovery implementation can look it up.
 */
fun interface PeerEndpointResolver {
    /** Returns the address to dial for [peer]. */
    suspend fun resolve(peer: PeerDevice): FluxShareResult<TransportEndpoint>
}
