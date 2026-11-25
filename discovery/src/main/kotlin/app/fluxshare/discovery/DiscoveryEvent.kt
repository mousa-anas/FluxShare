package app.fluxshare.discovery

import app.fluxshare.core.DeviceId
import app.fluxshare.core.FluxShareError

/** Incremental updates produced while discovery is running. */
sealed interface DiscoveryEvent {
    data class DeviceFound(val device: DiscoveredDevice) : DiscoveryEvent

    data class DeviceLost(val deviceId: DeviceId) : DiscoveryEvent

    data class Failed(val error: FluxShareError) : DiscoveryEvent
}
