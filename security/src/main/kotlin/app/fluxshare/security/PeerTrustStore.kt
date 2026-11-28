package app.fluxshare.security

import app.fluxshare.core.DeviceId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The set of peers this device trusts, keyed by [DeviceId] and pinned to a
 * certificate [CertificateFingerprint].
 *
 * Trust is decided after the TLS handshake and before any application data is
 * exchanged. A store never "upgrades" a pin silently: changing the certificate
 * for a known device is an error, not a new pairing.
 */
interface PeerTrustStore {
    /** The pinned fingerprint for [deviceId], or `null` when the device is unknown. */
    suspend fun fingerprintFor(deviceId: DeviceId): CertificateFingerprint?

    /** Pins [fingerprint] for [deviceId], replacing an existing pin. */
    suspend fun trust(deviceId: DeviceId, fingerprint: CertificateFingerprint)

    /** Removes the pin for [deviceId]. @return true when a pin existed. */
    suspend fun revoke(deviceId: DeviceId): Boolean

    /** Snapshot of every trusted device. */
    suspend fun trustedPeers(): Map<DeviceId, CertificateFingerprint>
}

/** Process-local [PeerTrustStore]; the persistent Android store comes later. */
class InMemoryPeerTrustStore(
    initial: Map<DeviceId, CertificateFingerprint> = emptyMap(),
) : PeerTrustStore {

    private val mutex = Mutex()
    private val peers = LinkedHashMap(initial)

    override suspend fun fingerprintFor(deviceId: DeviceId): CertificateFingerprint? =
        mutex.withLock { peers[deviceId] }

    override suspend fun trust(deviceId: DeviceId, fingerprint: CertificateFingerprint) {
        mutex.withLock { peers[deviceId] = fingerprint }
    }

    override suspend fun revoke(deviceId: DeviceId): Boolean =
        mutex.withLock { peers.remove(deviceId) != null }

    override suspend fun trustedPeers(): Map<DeviceId, CertificateFingerprint> =
        mutex.withLock { peers.toMap() }
}
