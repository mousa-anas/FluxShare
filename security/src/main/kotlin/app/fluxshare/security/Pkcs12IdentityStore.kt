package app.fluxshare.security

import app.fluxshare.core.DeviceId
import app.fluxshare.core.FluxShareError
import app.fluxshare.core.FluxShareResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.time.Duration
import java.util.UUID

/**
 * [IdentityStore] that persists one identity in a PKCS#12 file.
 *
 * This is the JVM stand-in for the Android Keystore: the private key stays
 * inside the key store and is only exposed through a key manager. The file is
 * written atomically with owner-only permissions where the platform supports
 * them.
 *
 * @param password protects the PKCS#12 file at rest. It is not a substitute for
 * Android Keystore hardware protection; keep it in the platform's secret
 * storage, not in shared preferences.
 */
class Pkcs12IdentityStore(
    private val file: Path,
    private val password: CharArray,
    private val deviceId: DeviceId = DeviceId("jvm-" + UUID.randomUUID()),
    private val validity: Duration = Identities.DEFAULT_VALIDITY,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : IdentityStore {

    private val mutex = Mutex()
    private var cached: DeviceIdentity? = null

    override suspend fun loadOrCreate(): FluxShareResult<DeviceIdentity> =
        withContext(ioDispatcher) {
            mutex.withLock {
                cached?.let { return@withContext FluxShareResult.Success(it) }
                try {
                    val identity = if (Files.exists(file)) {
                        load()
                    } else {
                        create()
                    }
                    cached = identity
                    FluxShareResult.Success(identity)
                } catch (e: IOException) {
                    FluxShareResult.Failure(FluxShareError.StorageUnavailable)
                } catch (e: GeneralSecurityException) {
                    FluxShareResult.Failure(FluxShareError.StorageUnavailable)
                } catch (e: IllegalStateException) {
                    FluxShareResult.Failure(FluxShareError.StorageUnavailable)
                }
            }
        }

    private fun load(): DeviceIdentity {
        val store = KeyStore.getInstance(Identities.PKCS12)
        Files.newInputStream(file).use { store.load(it, password) }
        val certificate = Identities.certificateOf(store, password)
        val storedId = deviceIdFromCertificate(certificate)
            ?: throw IllegalStateException("The stored identity has no FluxShare device identifier.")
        return KeyStoreDeviceIdentity(storedId, store, password)
    }

    private fun create(): DeviceIdentity {
        val store = Identities.createKeyStore(deviceId, clockMillis(), validity, password)
        persist(store)
        return KeyStoreDeviceIdentity(deviceId, store, password)
    }

    private fun persist(store: KeyStore) {
        file.parent?.let { Files.createDirectories(it) }
        val temporary = Files.createTempFile(file.parent, "fluxshare-identity", ".p12")
        try {
            Files.newOutputStream(temporary).use { store.store(it, password) }
            try {
                Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
            runCatching {
                Files.setPosixFilePermissions(
                    file,
                    PosixFilePermissions.fromString("rw-------"),
                )
            }
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }
}
