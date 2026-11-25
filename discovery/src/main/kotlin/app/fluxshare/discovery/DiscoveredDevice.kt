package app.fluxshare.discovery

import app.fluxshare.core.PeerDevice
import app.fluxshare.transport.TransportEndpoint

/** A device announced on the local network, with the address to reach it. */
data class DiscoveredDevice(
    val device: PeerDevice,
    val endpoint: TransportEndpoint,
)
