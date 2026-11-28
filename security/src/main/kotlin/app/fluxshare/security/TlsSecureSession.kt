package app.fluxshare.security

import app.fluxshare.core.DeviceId
import app.fluxshare.transport.TransportEndpoint
import app.fluxshare.transport.TransportSession

/**
 * [SecureSession] over TLS, exposing only authenticated and encrypted bytes.
 *
 * Instances are created by [DefaultSessionSecurity] after the peer identity was
 * accepted, so a caller can never observe plaintext from an unauthenticated
 * peer.
 */
internal class TlsSecureSession(
    private val channel: TlsChannel,
    private val transportEndpoint: TransportEndpoint,
    override val remoteDeviceId: DeviceId,
) : SecureSession {

    override val endpoint: TransportEndpoint get() = transportEndpoint

    override val isOpen: Boolean get() = channel.isOpen

    /** Protocol actually negotiated; tests assert that it is TLS 1.2 or newer. */
    internal val negotiatedProtocol: String get() = channel.protocol

    /** Cipher suite actually negotiated; tests assert that it is AEAD. */
    internal val negotiatedCipherSuite: String get() = channel.cipherSuite

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        channel.read(buffer, offset, length)

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) =
        channel.write(buffer, offset, length)

    override suspend fun close() = channel.close()
}
