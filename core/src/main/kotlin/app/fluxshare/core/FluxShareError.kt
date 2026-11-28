package app.fluxshare.core

/**
 * Expected failure modes shared by every FluxShare layer.
 *
 * Transport, security, discovery and storage implementations map their own
 * platform errors onto these cases so callers never depend on platform types.
 */
sealed class FluxShareError(message: String) : Exception(message) {
    data object NetworkUnavailable : FluxShareError("No usable network is available.")

    data object PeerUnavailable : FluxShareError("The remote device is not reachable.")

    data object TransferRejected : FluxShareError("The remote device rejected the transfer.")

    data object SecurityFailure : FluxShareError("The secure channel could not be established.")

    /**
     * A device that was paired before presented a different identity certificate.
     *
     * This is never a prompt: it either means the peer reinstalled FluxShare or
     * that somebody is impersonating it, so the session must fail and the user
     * has to pair again out of band.
     */
    data class PeerIdentityChanged(val detail: String) : FluxShareError(detail)

    data object StorageUnavailable : FluxShareError("The file could not be read or written.")

    /** A peer sent bytes that do not satisfy the FluxShare protocol. */
    data class ProtocolViolation(val detail: String) : FluxShareError("Protocol violation: $detail")

    data class Unexpected(val detail: String) : FluxShareError(detail)
}
