package app.fluxshare.discovery

import kotlinx.coroutines.flow.Flow

/**
 * Announces this device and discovers FluxShare peers on the local network.
 *
 * Android will back this with network service discovery; other platforms can
 * provide their own implementation without changing the contract.
 */
interface DeviceDiscovery {
    /** True when the current platform can run discovery. */
    val isSupported: Boolean

    /** Emits updates until the returned flow is cancelled. */
    fun discover(): Flow<DiscoveryEvent>

    suspend fun stop()
}
