package app.fluxshare.security

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Locale

/**
 * SHA-256 digest of a certificate's DER encoding, in lowercase hex.
 *
 * Fingerprints are the only identity anchor FluxShare uses: there is no
 * certificate authority and no hostname validation. A fingerprint is compared
 * byte for byte, so a peer that presents a different certificate for the same
 * [app.fluxshare.core.DeviceId] is rejected.
 */
@JvmInline
value class CertificateFingerprint private constructor(val hex: String) {

    init {
        require(hex.length == HEX_LENGTH && HEX.matches(hex)) {
            "A certificate fingerprint must be $HEX_LENGTH lowercase hex characters."
        }
    }

    /** Raw 32-byte digest, used for key derivation such as [PairingCode]. */
    fun bytes(): ByteArray = ByteArray(HEX_LENGTH / 2) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    override fun toString(): String = hex

    companion object {
        const val HEX_LENGTH: Int = 64

        private val HEX = Regex("[0-9a-f]{$HEX_LENGTH}")

        fun of(encodedCertificate: ByteArray): CertificateFingerprint {
            val digest = MessageDigest.getInstance("SHA-256").digest(encodedCertificate)
            return CertificateFingerprint(digest.joinToString("") { "%02x".format(it) })
        }

        fun of(certificate: X509Certificate): CertificateFingerprint = of(certificate.encoded)

        /**
         * Parses a fingerprint from hex text, tolerating uppercase and the
         * colon-separated form often shown to users (`AB:CD:...`).
         */
        fun parse(text: String): CertificateFingerprint {
            val normalized = text
                .replace(":", "")
                .replace(" ", "")
                .lowercase(Locale.ROOT)
            require(normalized.length == HEX_LENGTH && HEX.matches(normalized)) {
                "Not a SHA-256 certificate fingerprint: $text"
            }
            return CertificateFingerprint(normalized)
        }

        fun parseOrNull(text: String): CertificateFingerprint? =
            runCatching { parse(text) }.getOrNull()
    }
}
