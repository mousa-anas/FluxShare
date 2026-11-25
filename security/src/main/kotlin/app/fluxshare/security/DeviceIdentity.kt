package app.fluxshare.security

import app.fluxshare.core.DeviceId

/** Local identity used to authenticate this device to peers. */
interface DeviceIdentity {
    val deviceId: DeviceId

    /**
     * Short human-readable code derived from the identity key, for out-of-band
     * verification when pairing.
     */
    val fingerprint: String
}
