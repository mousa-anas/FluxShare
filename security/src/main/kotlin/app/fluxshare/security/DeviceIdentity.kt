package app.fluxshare.security

import app.fluxshare.core.DeviceId
import java.security.cert.X509Certificate
import javax.net.ssl.X509ExtendedKeyManager

/**
 * Local identity used to authenticate this device to peers.
 *
 * The identity is a self-signed X.509 certificate with an ECDSA P-256 key. The
 * [deviceId] is carried in a subject alternative name URI
 * (`fluxshare:device:<id>`) and the [fingerprint] is the SHA-256 digest of the
 * certificate's DER encoding, so peers can pin the exact certificate instead of
 * relying on a certificate authority.
 *
 * **Private keys never leave the identity.** There is deliberately no accessor
 * that returns the key: TLS obtains it through [keyManager], which keeps the
 * key inside a [java.security.KeyStore] (PKCS#12 on the JVM, the Android
 * Keystore on Android).
 */
interface DeviceIdentity {
    val deviceId: DeviceId

    /** The self-signed certificate that proves this identity. */
    val certificate: X509Certificate

    /** SHA-256 digest of [certificate]'s DER encoding. */
    val fingerprint: CertificateFingerprint

    /**
     * Key manager that hands the identity key to a TLS engine.
     *
     * Implementations must return the private key only through the key manager,
     * never as a [java.security.PrivateKey] the caller could export.
     */
    fun keyManager(): X509ExtendedKeyManager
}
