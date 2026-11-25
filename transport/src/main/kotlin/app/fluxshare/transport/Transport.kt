package app.fluxshare.transport

import app.fluxshare.core.FluxShareResult

/**
 * A way to move bytes between FluxShare devices.
 *
 * Implementations must stay independent of the Android UI so the same contracts
 * can later be backed by a Windows-compatible transport.
 */
interface Transport {
    /** Stable, human-readable name of the implementation. */
    val name: String

    /** Opens an outgoing session to [endpoint]. */
    suspend fun connect(endpoint: TransportEndpoint): FluxShareResult<TransportSession>

    /** Starts accepting incoming sessions on [port], or on an ephemeral port when `0`. */
    suspend fun listen(port: Int = 0): FluxShareResult<TransportListener>
}
