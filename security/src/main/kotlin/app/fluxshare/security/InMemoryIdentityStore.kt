package app.fluxshare.security

import app.fluxshare.core.DeviceId
import app.fluxshare.core.FluxShareError
import app.fluxshare.core.FluxShareResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.util.UUID

/**
 * [IdentityStore] that keeps a single identity in memory for the process
 * lifetime.
 *
 * Intended for tests and for platforms without persistent storage; a new
 * process gets a new identity, which means peers have to pair again.
 */
class InMemoryIdentityStore(
    private val deviceId: DeviceId = DeviceId("jvm-" + UUID.randomUUID()),
    private val validity: Duration = Identities.DEFAULT_VALIDITY,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : IdentityStore {

    private val mutex = Mutex()
    private var cached: DeviceIdentity? = null

    override suspend fun loadOrCreate(): FluxShareResult<DeviceIdentity> = mutex.withLock {
        cached?.let { return FluxShareResult.Success(it) }
        val keyPassword = Identities.randomPassword()
        val store = runCatching {
            Identities.createKeyStore(deviceId, clockMillis(), validity, keyPassword)
        }.getOrElse {
            return FluxShareResult.Failure(FluxShareError.SecurityFailure)
        }
        val identity = KeyStoreDeviceIdentity(deviceId, store, keyPassword)
        cached = identity
        FluxShareResult.Success(identity)
    }
}
