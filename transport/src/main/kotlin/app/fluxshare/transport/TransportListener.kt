package app.fluxshare.transport

import app.fluxshare.core.FluxShareResult

/** Accepts incoming transport sessions. */
interface TransportListener {
    /** The local endpoint remote devices can connect to. */
    val endpoint: TransportEndpoint

    /** Suspends until a remote device opens a session. */
    suspend fun accept(): FluxShareResult<TransportSession>

    suspend fun close()
}
