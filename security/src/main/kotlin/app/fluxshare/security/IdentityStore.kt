package app.fluxshare.security

import app.fluxshare.core.FluxShareResult

/**
 * Loads this device's long-lived identity, creating it on first use.
 *
 * Implementations persist the private key; the JVM one uses a PKCS#12 file, a
 * future Android one wraps the key in the Android Keystore. Callers never see
 * key material, only a [DeviceIdentity] backed by a key manager.
 */
interface IdentityStore {
    /**
     * Returns the stored identity, generating and persisting one when the store
     * is still empty.
     *
     * @return [FluxShareResult.Failure] with [app.fluxshare.core.FluxShareError.StorageUnavailable]
     * when the identity cannot be read or written.
     */
    suspend fun loadOrCreate(): FluxShareResult<DeviceIdentity>
}
