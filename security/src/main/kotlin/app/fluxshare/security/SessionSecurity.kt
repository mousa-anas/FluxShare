package app.fluxshare.security

import app.fluxshare.core.DeviceId
import app.fluxshare.core.FluxShareResult
import app.fluxshare.transport.TransportSession

/** Turns a raw transport session into an authenticated, encrypted session. */
interface SessionSecurity {
    /**
     * Runs the handshake over [session] and verifies the remote device, as the
     * connecting side.
     *
     * @param remoteDeviceId the device the caller expects to reach; the
     * handshake must fail with [app.fluxshare.core.FluxShareError.SecurityFailure]
     * when the authenticated identity does not match.
     */
    suspend fun secure(
        session: TransportSession,
        remoteDeviceId: DeviceId,
    ): FluxShareResult<SecureSession>

    /**
     * Runs the handshake over an accepted [session] and verifies the remote
     * device, as the accepting side.
     *
     * The caller does not know which device is connecting, so the identity is
     * whatever the peer proves and trusts. The result's
     * [SecureSession.remoteDeviceId] is that authenticated identity.
     */
    suspend fun acceptSecure(session: TransportSession): FluxShareResult<SecureSession>
}
