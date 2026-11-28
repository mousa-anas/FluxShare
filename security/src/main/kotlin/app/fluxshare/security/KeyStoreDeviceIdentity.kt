package app.fluxshare.security

import app.fluxshare.core.DeviceId
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.X509ExtendedKeyManager

/**
 * A [DeviceIdentity] backed by a [KeyStore] that holds the private key.
 *
 * The key is only reachable through the returned key manager; the identity has
 * no method that exports it.
 */
internal class KeyStoreDeviceIdentity(
    override val deviceId: DeviceId,
    private val keyStore: KeyStore,
    private val keyPassword: CharArray,
) : DeviceIdentity {

    override val certificate: X509Certificate by lazy {
        Identities.certificateOf(keyStore, keyPassword)
    }

    override val fingerprint: CertificateFingerprint by lazy {
        CertificateFingerprint.of(certificate)
    }

    private val keyManager: X509ExtendedKeyManager by lazy {
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore, keyPassword)
        val delegate = factory.keyManagers.filterIsInstance<X509ExtendedKeyManager>().firstOrNull()
            ?: throw IllegalStateException("The default key manager factory produced no X.509 key manager.")
        // Both TLS roles have exactly one identity, so never let the default
        // algorithm pick an unrelated alias (it can when a peer sends an empty
        // certificate-authority list).
        SingleAliasKeyManager(delegate, Identities.KEY_ALIAS)
    }

    override fun keyManager(): X509ExtendedKeyManager = keyManager
}

/**
 * Key manager that always offers the single FluxShare identity.
 *
 * `KeyManagerFactory` chooses an alias from the peer's accepted issuers, which
 * are meaningless for self-signed certificates. Delegate for everything else,
 * so key material handling stays in the JDK.
 */
internal class SingleAliasKeyManager(
    private val delegate: X509ExtendedKeyManager,
    private val alias: String,
) : X509ExtendedKeyManager() {

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out java.security.Principal>?,
        socket: java.net.Socket?,
    ): String = alias

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out java.security.Principal>?,
        engine: javax.net.ssl.SSLEngine?,
    ): String = alias

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out java.security.Principal>?,
        socket: java.net.Socket?,
    ): String = alias

    override fun chooseEngineServerAlias(
        keyType: String?,
        issuers: Array<out java.security.Principal>?,
        engine: javax.net.ssl.SSLEngine?,
    ): String = alias

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        delegate.getCertificateChain(alias)

    override fun getClientAliases(
        keyType: String?,
        issuers: Array<out java.security.Principal>?,
    ): Array<String>? = delegate.getClientAliases(keyType, issuers)

    override fun getPrivateKey(alias: String?): java.security.PrivateKey? =
        delegate.getPrivateKey(alias)

    override fun getServerAliases(
        keyType: String?,
        issuers: Array<out java.security.Principal>?,
    ): Array<String>? = delegate.getServerAliases(keyType, issuers)
}
