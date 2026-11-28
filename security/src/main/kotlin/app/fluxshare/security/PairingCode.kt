package app.fluxshare.security

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Short authentication string shown on both devices during pairing.
 *
 * It is a function of the two certificate fingerprints only, so an attacker in
 * the middle who substitutes certificates produces different codes on the two
 * screens. The user compares the codes out of band; a match authenticates both
 * certificates.
 *
 * Ten decimal digits give about 33 bits, which is the usual size for a
 * human-compared SAS.
 */
@JvmInline
value class PairingCode private constructor(val display: String) {

    init {
        require(DISPLAY_FORMAT.matches(display)) {
            "A pairing code looks like '123 456 7890'."
        }
    }

    override fun toString(): String = display

    companion object {
        /** Domain separator so the same fingerprints cannot be reused elsewhere. */
        const val SAS_LABEL: String = "FluxShare-SAS-v1"

        const val DIGITS: Int = 10

        private val DISPLAY_FORMAT = Regex("[0-9]{3} [0-9]{3} [0-9]{4}")
        private val TEN_POW_DIGITS = BigInteger.TEN.pow(DIGITS)

        /**
         * Derives the code for a pair of certificates.
         *
         * Both devices pass their own fingerprint and the peer's; the
         * fingerprints are sorted, so the result is identical on both sides
         * regardless of role.
         */
        fun derive(
            localFingerprint: CertificateFingerprint,
            remoteFingerprint: CertificateFingerprint,
        ): PairingCode {
            val ordered = listOf(localFingerprint, remoteFingerprint).sortedBy { it.hex }
            val digest = MessageDigest.getInstance("SHA-256").run {
                update(SAS_LABEL.toByteArray(StandardCharsets.UTF_8))
                ordered.forEach { update(it.bytes()) }
                digest()
            }
            val number = BigInteger(1, digest.copyOfRange(0, 8)).mod(TEN_POW_DIGITS)
            val digits = number.toString().padStart(DIGITS, '0')
            return PairingCode(
                "${digits.substring(0, 3)} ${digits.substring(3, 6)} ${digits.substring(6)}",
            )
        }
    }
}
