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

    data object StorageUnavailable : FluxShareError("The file could not be read or written.")

    data class Unexpected(val detail: String) : FluxShareError(detail)
}
