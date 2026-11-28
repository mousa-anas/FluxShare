package app.fluxshare.security

/**
 * TLS parameters FluxShare accepts.
 *
 * Only TLS 1.3 and TLS 1.2 are enabled, and only AEAD cipher suites. The
 * ECDHE-ECDSA suites are the TLS 1.2 fallbacks; TLS 1.3 suites are always
 * forward secret. Everything else the JDK supports (CBC, RSA key exchange,
 * static DH, RC4, 3DES) stays off.
 */
object TlsPolicy {
    val PROTOCOLS: List<String> = listOf("TLSv1.3", "TLSv1.2")

    val CIPHER_SUITES: List<String> = listOf(
        // TLS 1.3
        "TLS_AES_128_GCM_SHA256",
        "TLS_AES_256_GCM_SHA384",
        "TLS_CHACHA20_POLY1305_SHA256",
        // TLS 1.2 with ECDSA certificates and ephemeral key exchange
        "ECDHE-ECDSA-AES128-GCM-SHA256",
        "ECDHE-ECDSA-AES256-GCM-SHA384",
        "ECDHE-ECDSA-CHACHA20-POLY1305",
    )

    /** AEAD suites, used by tests to assert that nothing weaker was negotiated. */
    val AEAD_CIPHER_SUITES: Set<String> = CIPHER_SUITES.toSet()

    /** The oldest protocol this policy allows. */
    const val MINIMUM_PROTOCOL: String = "TLSv1.2"
}
