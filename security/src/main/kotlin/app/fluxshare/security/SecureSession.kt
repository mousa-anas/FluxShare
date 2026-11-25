package app.fluxshare.security

import app.fluxshare.core.DeviceId
import app.fluxshare.transport.TransportSession

/**
 * A raw [TransportSession] after authentication and encryption were applied.
 *
 * It keeps the transport's byte-channel contract, so the transfer layer can use
 * an insecure and a secure session the same way.
 */
interface SecureSession : TransportSession {
    /** Identity of the authenticated remote device. */
    val remoteDeviceId: DeviceId
}
