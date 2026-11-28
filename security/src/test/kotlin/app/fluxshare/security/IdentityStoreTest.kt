package app.fluxshare.security

import app.fluxshare.core.DeviceId
import app.fluxshare.core.FluxShareError
import app.fluxshare.core.FluxShareResult
import kotlinx.coroutines.runBlocking
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.time.Duration
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdentityStoreTest {

    @Test
    fun `in-memory store keeps one identity per process`() = runBlocking {
        val store = InMemoryIdentityStore(DeviceId("memory-device"))
        val first = store.loadOrCreate().getOrFail()
        val second = store.loadOrCreate().getOrFail()

        assertEquals(first.deviceId, second.deviceId)
        assertEquals(first.fingerprint, second.fingerprint)
        assertContentEquals(first.certificate.encoded, second.certificate.encoded)
    }

    @Test
    fun `certificate carries the device id an ECDSA P-256 key and ten year validity`() = runBlocking {
        val now = System.currentTimeMillis()
        val identity = InMemoryIdentityStore(
            DeviceId("cert-device"),
            clockMillis = { now },
        ).loadOrCreate().getOrFail()
        val certificate = identity.certificate

        assertEquals(identity.deviceId, deviceIdFromCertificate(certificate))
        assertEquals("fluxshare:device:cert-device", deviceIdUri(identity.deviceId))

        val publicKey = certificate.publicKey as ECPublicKey
        assertEquals(256, publicKey.params.curve.field.fieldSize)
        assertEquals("EC", publicKey.algorithm)

        val validity = certificate.notAfter.time - certificate.notBefore.time
        assertTrue(
            validity >= Duration.ofDays(3650).toMillis(),
            "Certificates must stay valid for about ten years, was $validity ms",
        )

        certificate.checkValidity()
        certificate.verify(certificate.publicKey)

        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        assertEquals(digest.joinToString("") { "%02x".format(it) }, identity.fingerprint.hex)
    }

    @Test
    fun `pkcs12 store creates once then reloads the same identity`() = runBlocking {
        val directory = Files.createTempDirectory("fluxshare-identity-test")
        try {
            val file = directory.resolve("identity.p12")
            val password = "test-password-123".toCharArray()

            val created = Pkcs12IdentityStore(file, password, DeviceId("file-device"))
                .loadOrCreate()
                .getOrFail()
            assertTrue(Files.exists(file))

            val reloaded = Pkcs12IdentityStore(file, password)
                .loadOrCreate()
                .getOrFail()

            assertEquals(DeviceId("file-device"), reloaded.deviceId)
            assertEquals(created.fingerprint, reloaded.fingerprint)
            assertContentEquals(created.certificate.encoded, reloaded.certificate.encoded)
        } finally {
            deleteRecursively(directory)
        }
    }

    @Test
    fun `pkcs12 store rejects a wrong password`() = runBlocking {
        val directory = Files.createTempDirectory("fluxshare-identity-bad-password")
        try {
            val file = directory.resolve("identity.p12")
            Pkcs12IdentityStore(file, "correct-password".toCharArray(), DeviceId("locked-device"))
                .loadOrCreate()
                .getOrFail()

            val result = Pkcs12IdentityStore(file, "wrong-password".toCharArray()).loadOrCreate()

            assertTrue(result is FluxShareResult.Failure)
            assertEquals(
                FluxShareError.StorageUnavailable,
                result.error,
            )
        } finally {
            deleteRecursively(directory)
        }
    }

    @Test
    fun `two identities never share a key`() = runBlocking {
        val first = testIdentity("same-name")
        val second = testIdentity("same-name")

        assertNotEquals(first.fingerprint, second.fingerprint)
        assertNotEquals(
            BigInteger(1, first.certificate.publicKey.encoded),
            BigInteger(1, second.certificate.publicKey.encoded),
        )
    }

    private fun deleteRecursively(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
