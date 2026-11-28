package app.fluxshare.security

import app.fluxshare.core.DeviceId
import app.fluxshare.core.FluxShareError
import app.fluxshare.core.FluxShareResult
import app.fluxshare.transport.TransportSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * TLS with pinned self-signed identities.
 *
 * ### What this protects against
 *
 * A passive or active attacker on the local network who can read, modify,
 * replay or inject traffic, and who can run a rogue FluxShare device. TLS
 * provides confidentiality and integrity; mutual certificate authentication
 * proves that the peer holds the private key for the certificate it presents;
 * pinning by certificate fingerprint prevents impersonation of a paired device;
 * the pairing code (or a pairing link) authenticates the certificates out of
 * band, which is what stops a man in the middle during the very first session.
 *
 * ### What it does not protect against
 *
 * Compromised devices (malware, a rooted phone, a stolen unlocked device),
 * traffic analysis and other side channels, anonymity, and denial of service.
 * A user who confirms a pairing prompt for an attacker's device has authorized
 * that device; the code comparison is the only check that catches it.
 *
 * ### Trust is decided after the handshake
 *
 * The TLS trust manager accepts every certificate, because there is no CA.
 * Instead the peer certificate is authenticated here, after the handshake and
 * before any application data is read or written: a pinned fingerprint is
 * accepted, an unknown device goes through [confirmPairing], and a known device
 * presenting a different certificate is a hard failure.
 *
 * @param identity this device's identity, created by an [IdentityStore].
 * @param trustStore pinned peers.
 * @param confirmPairing asks the user to compare [PairingCode] with the peer;
 * no UI type crosses this boundary. A prompt rate limit is applied before it is
 * called.
 * @param handshakeTimeoutMillis upper bound for the TLS handshake; on expiry
 * the underlying session is closed.
 */
class DefaultSessionSecurity(
    private val identity: DeviceIdentity,
    private val trustStore: PeerTrustStore,
    private val confirmPairing: suspend (DeviceId, PairingCode) -> Boolean,
    private val handshakeTimeoutMillis: Long = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
    private val promptLimiter: PairingPromptLimiter = PairingPromptLimiter(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val sslContext: SSLContext = createSslContext(identity),
) : SessionSecurity {

    init {
        require(handshakeTimeoutMillis > 0) {
            "handshakeTimeoutMillis must be positive, was $handshakeTimeoutMillis"
        }
    }

    override suspend fun secure(
        session: TransportSession,
        remoteDeviceId: DeviceId,
    ): FluxShareResult<SecureSession> =
        establish(session, clientMode = true, expectedRemoteDeviceId = remoteDeviceId)

    override suspend fun acceptSecure(session: TransportSession): FluxShareResult<SecureSession> =
        establish(session, clientMode = false, expectedRemoteDeviceId = null)

    private suspend fun establish(
        session: TransportSession,
        clientMode: Boolean,
        expectedRemoteDeviceId: DeviceId?,
    ): FluxShareResult<SecureSession> {
        val engine = sslContext.createSSLEngine().also { engine ->
            configure(engine, clientMode)
        }
        val channel = TlsChannel(session, engine, ioDispatcher)

        val certificate = try {
            handshakeWithTimeout(session, channel)
        } catch (e: CancellationException) {
            runCatching { session.close() }
            throw e
        } catch (e: FluxShareError) {
            runCatching { session.close() }
            return FluxShareResult.Failure(e)
        } catch (e: Exception) {
            runCatching { session.close() }
            return FluxShareResult.Failure(FluxShareError.SecurityFailure)
        }

        val remoteDeviceId = try {
            authenticate(certificate, expectedRemoteDeviceId)
        } catch (e: CancellationException) {
            runCatching { session.close() }
            throw e
        } catch (e: FluxShareError) {
            runCatching { session.close() }
            return FluxShareResult.Failure(e)
        } catch (e: Exception) {
            runCatching { session.close() }
            return FluxShareResult.Failure(FluxShareError.SecurityFailure)
        }

        return FluxShareResult.Success(
            TlsSecureSession(channel, session.endpoint, remoteDeviceId),
        )
    }

    /**
     * Decides whether the authenticated certificate belongs to the expected peer.
     *
     * Runs before any application data is exposed, and closes the session on
     * every failure path.
     */
    private suspend fun authenticate(
        certificate: X509Certificate,
        expectedRemoteDeviceId: DeviceId?,
    ): DeviceId {
        val remoteDeviceId = deviceIdFromCertificate(certificate)
            ?: throw FluxShareError.SecurityFailure
        if (expectedRemoteDeviceId != null && expectedRemoteDeviceId != remoteDeviceId) {
            throw FluxShareError.SecurityFailure
        }

        val fingerprint = CertificateFingerprint.of(certificate)
        val pinned = trustStore.fingerprintFor(remoteDeviceId)
        when {
            pinned == fingerprint -> Unit

            pinned != null -> throw FluxShareError.PeerIdentityChanged(
                "Device ${remoteDeviceId.value} presented a different identity certificate.",
            )

            else -> {
                if (!promptLimiter.tryAcquire()) throw FluxShareError.SecurityFailure
                val code = PairingCode.derive(identity.fingerprint, fingerprint)
                val accepted = confirmPairing(remoteDeviceId, code)
                if (!accepted) throw FluxShareError.SecurityFailure
                trustStore.trust(remoteDeviceId, fingerprint)
            }
        }
        return remoteDeviceId
    }

    private suspend fun handshakeWithTimeout(
        session: TransportSession,
        channel: TlsChannel,
    ): X509Certificate = coroutineScope {
        val watchdog = launch {
            delay(handshakeTimeoutMillis)
            // Blocking transport reads ignore coroutine cancellation, so the
            // only reliable way to end an idle handshake is to close the socket.
            runCatching { session.close() }
        }
        try {
            channel.handshake()
        } catch (e: Exception) {
            throw FluxShareError.SecurityFailure
        } finally {
            watchdog.cancel()
        }
    }

    private fun configure(engine: SSLEngine, clientMode: Boolean) {
        engine.useClientMode = clientMode
        val parameters = engine.sslParameters
        parameters.protocols = TlsPolicy.PROTOCOLS.intersect(engine.supportedProtocols.toSet())
            .ifEmpty { TlsPolicy.PROTOCOLS.toSet() }
            .toTypedArray()
        parameters.cipherSuites =
            TlsPolicy.CIPHER_SUITES.intersect(engine.supportedCipherSuites.toSet())
                .ifEmpty { TlsPolicy.CIPHER_SUITES.toSet() }
                .toTypedArray()
        parameters.needClientAuth = !clientMode
        parameters.useCipherSuitesOrder = true
        // Identity is the pinned certificate, not a hostname.
        parameters.endpointIdentificationAlgorithm = null
        engine.sslParameters = parameters
    }

    companion object {
        const val DEFAULT_HANDSHAKE_TIMEOUT_MILLIS: Long = 15_000

        /**
         * Builds the SSL context for one identity.
         *
         * Session resumption is disabled so every session performs a full
         * handshake and therefore a fresh trust decision; there is no
         * certificate authority here, so a resumable session would be the only
         * path that skips the pin check.
         */
        fun createSslContext(identity: DeviceIdentity): SSLContext {
            val context = SSLContext.getInstance("TLS")
            context.init(
                arrayOf(identity.keyManager()),
                arrayOf<TrustManager>(AcceptAllCertificates),
                SecureRandom(),
            )
            context.clientSessionContext.sessionCacheSize = 0
            context.serverSessionContext.sessionCacheSize = 0
            return context
        }

        /**
         * Accepts every certificate during the handshake.
         *
         * This is not a trust decision: it only lets the TLS layer complete so
         * the certificate can be compared against the pin afterwards. PKIX
         * validation is impossible for self-signed FluxShare certificates.
         */
        internal object AcceptAllCertificates : X509ExtendedTrustManager() {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                Unit

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                Unit

            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                socket: java.net.Socket?,
            ) = Unit

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                socket: java.net.Socket?,
            ) = Unit

            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                engine: SSLEngine?,
            ) = Unit

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                engine: SSLEngine?,
            ) = Unit

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }
}
