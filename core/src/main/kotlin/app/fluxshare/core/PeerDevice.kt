package app.fluxshare.core

/** Platform family of a remote FluxShare device. */
enum class DevicePlatform {
    ANDROID,
    WINDOWS,
    UNKNOWN,
}

/** A FluxShare device as described to the local app. */
data class PeerDevice(
    val id: DeviceId,
    val displayName: String,
    val platform: DevicePlatform = DevicePlatform.UNKNOWN,
)
