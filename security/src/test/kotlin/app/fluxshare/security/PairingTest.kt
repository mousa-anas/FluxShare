package app.fluxshare.security

import app.fluxshare.core.DeviceId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingTest {

    private val fingerprintA = CertificateFingerprint.parse("aa".repeat(32))
    private val fingerprintB = CertificateFingerprint.parse("bb".repeat(32))
    private val fingerprintC = CertificateFingerprint.parse("cc".repeat(32))

    @Test
    fun `pairing code is identical on both devices and formatted`() {
        val fromClient = PairingCode.derive(fingerprintA, fingerprintB)
        val fromServer = PairingCode.derive(fingerprintB, fingerprintA)

        assertEquals(fromClient, fromServer)
        assertEquals(12, fromClient.display.length)
        assertTrue(Regex("[0-9]{3} [0-9]{3} [0-9]{4}").matches(fromClient.display))
        assertTrue(fromClient.display.replace(" ", "").length == PairingCode.DIGITS)
    }

    @Test
    fun `pairing code changes when one certificate changes`() {
        val original = PairingCode.derive(fingerprintA, fingerprintB)
        val substituted = PairingCode.derive(fingerprintA, fingerprintC)
        assertNotEquals(original, substituted)
    }

    @Test
    fun `pairing link round trips and preserves any device id`() {
        for (id in listOf("device-1", "dev ice/#1&x=2", "Gerät-Ω")) {
            val link = PairingLink(DeviceId(id), fingerprintA)
            val parsed = PairingLink.parse(link.format())

            assertEquals(link, parsed)
            assertEquals(fingerprintA, parsed.fingerprint)
        }
    }

    @Test
    fun `pairing link rejects malformed input`() {
        val valid = PairingLink(DeviceId("device-1"), fingerprintA).format()
        assertTrue(PairingLink.parseOrNull(valid) != null)

        val malformed = listOf(
            "",
            "https://pair?id=device-1&fp=${fingerprintA.hex}",
            "fluxshare://pair?id=device-1",
            "fluxshare://pair?fp=${fingerprintA.hex}",
            "fluxshare://pair?id=&fp=${fingerprintA.hex}",
            "fluxshare://pair?id=device-1&fp=nothex",
            "fluxshare://pair?id=device-1&fp=${fingerprintA.hex}&fp=${fingerprintA.hex}",
            "fluxshare://other?id=device-1&fp=${fingerprintA.hex}",
            "fluxshare://pair/path?id=device-1&fp=${fingerprintA.hex}",
            "not a uri at all",
        )

        for (text in malformed) {
            assertFailsWith<IllegalArgumentException>("Expected rejection of '$text'") {
                PairingLink.parse(text)
            }
            assertNull(PairingLink.parseOrNull(text), "Expected '$text' to be unparseable")
        }
    }

    @Test
    fun `a pairing link pre trusts the peer`() = runBlocking {
        val store = InMemoryPeerTrustStore()
        val link = PairingLink(DeviceId("qr-device"), fingerprintB)

        assertEquals(null, store.fingerprintFor(link.deviceId))
        link.trust(store)
        assertEquals(fingerprintB, store.fingerprintFor(link.deviceId))

        assertTrue(store.revoke(link.deviceId))
        assertFalse(store.revoke(link.deviceId))
        assertEquals(null, store.fingerprintFor(link.deviceId))
    }

    @Test
    fun `fingerprints tolerate uppercase and separators`() {
        val spaced = fingerprintA.hex.uppercase().chunked(2).joinToString(":")
        assertEquals(fingerprintA, CertificateFingerprint.parse(spaced))
        assertEquals(fingerprintA, CertificateFingerprint.parse(fingerprintA.hex.uppercase()))
        assertNull(CertificateFingerprint.parseOrNull("abc"))
    }

    @Test
    fun `prompt limiter enforces a sliding window`() {
        var now = 0L
        val limiter = PairingPromptLimiter(maxPromptsPerMinute = 2, clockMillis = { now })

        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())

        now = 59_999
        assertFalse(limiter.tryAcquire())

        now = 60_000
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }
}
