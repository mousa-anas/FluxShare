package app.fluxshare.security

import app.fluxshare.core.DeviceId
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Provider
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.util.Date

/**
 * Self-signed identity certificates.
 *
 * Only certificate *encoding* uses Bouncy Castle; the signature algorithm is
 * the JDK's `SHA256withECDSA`. No key exchange, MAC or cipher is implemented
 * here: TLS does all of that.
 */
internal object Identities {
    /** Alias of the single private key entry every identity store holds. */
    const val KEY_ALIAS: String = "fluxshare-identity"

    /** Ten years: the certificate is a long-lived identity, not a short-lived credential. */
    val DEFAULT_VALIDITY: Duration = Duration.ofDays(3650)

    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private const val KEY_ALGORITHM = "EC"
    private const val CURVE = "secp256r1"

    /** Tolerated clock skew when backdating `notBefore`. */
    private const val CLOCK_SKEW_MILLIS = 60_000L

    private val provider: Provider by lazy { BouncyCastleProvider() }
    private val random = SecureRandom()

    /**
     * Creates a key store holding a fresh ECDSA P-256 key and a self-signed
     * certificate whose SAN URI names [deviceId].
     */
    fun createKeyStore(
        deviceId: DeviceId,
        nowMillis: Long,
        validity: Duration = DEFAULT_VALIDITY,
        keyPassword: CharArray = randomPassword(),
    ): KeyStore {
        val keyPair = KeyPairGenerator.getInstance(KEY_ALGORITHM).run {
            initialize(ECGenParameterSpec(CURVE), random)
            generateKeyPair()
        }

        val subject = X500Name("CN=FluxShare ${deviceId.value}")
        val notBefore = Date(nowMillis - CLOCK_SKEW_MILLIS)
        val notAfter = Date(nowMillis - CLOCK_SKEW_MILLIS + validity.toMillis())
        val serial = BigInteger(64, random).abs().add(BigInteger.ONE)

        val builder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            notBefore,
            notAfter,
            subject,
            keyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature),
        )
        builder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(arrayOf(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth)),
        )
        builder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(GeneralName(GeneralName.uniformResourceIdentifier, deviceIdUri(deviceId))),
        )

        val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
            .setProvider(provider)
            .build(keyPair.private)
        val certificate: X509Certificate = JcaX509CertificateConverter()
            .setProvider(provider)
            .getCertificate(builder.build(signer))

        // Fail loudly at creation time rather than at the first handshake.
        certificate.checkValidity(Date(nowMillis))
        certificate.verify(keyPair.public)

        val store = KeyStore.getInstance(PKCS12)
        store.load(null, keyPassword)
        store.setKeyEntry(KEY_ALIAS, keyPair.private, keyPassword, arrayOf(certificate))
        return store
    }

    /** Random PKCS#12 password; the file is protected by file permissions as well. */
    fun randomPassword(): CharArray {
        val bytes = ByteArray(24).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }.toCharArray()
    }

    const val PKCS12: String = "PKCS12"

    /** Reads the identity certificate out of [store]. */
    fun certificateOf(store: KeyStore, keyPassword: CharArray): X509Certificate {
        val entry = store.getEntry(KEY_ALIAS, KeyStore.PasswordProtection(keyPassword))
            as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("The identity key store has no private key entry.")
        return entry.certificate as X509Certificate
    }
}
