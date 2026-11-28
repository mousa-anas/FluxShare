package app.fluxshare.security

import app.fluxshare.core.DeviceId
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Pairing link that carries a device identifier and its certificate
 * fingerprint, for QR codes and other out-of-band channels.
 *
 * Format: `fluxshare://pair?id=<deviceId>&fp=<sha256 hex>`
 *
 * Scanning the link pins the peer's certificate before the first session, so
 * the user does not have to compare a pairing code on both screens. The link is
 * not secret: it identifies a device, it does not authenticate this device.
 */
data class PairingLink(
    val deviceId: DeviceId,
    val fingerprint: CertificateFingerprint,
) {
    fun format(): String = buildString {
        append(SCHEME).append("://").append(HOST)
        append("?id=").append(encode(deviceId.value))
        append("&fp=").append(fingerprint.hex)
    }

    /** Pins the peer described by this link, skipping the code comparison. */
    suspend fun trust(store: PeerTrustStore) {
        store.trust(deviceId, fingerprint)
    }

    companion object {
        const val SCHEME: String = "fluxshare"
        const val HOST: String = "pair"

        /**
         * Parses a pairing link.
         *
         * @throws IllegalArgumentException when the text is not a well-formed
         * FluxShare pairing link; never returns a partially valid link.
         */
        fun parse(text: String): PairingLink {
            val uri = try {
                URI(text.trim())
            } catch (e: URISyntaxException) {
                throw IllegalArgumentException("Not a FluxShare pairing link: $text", e)
            }
            require(uri.scheme.equals(SCHEME, ignoreCase = true)) {
                "Not a FluxShare pairing link: $text"
            }
            require(uri.host.equals(HOST, ignoreCase = true)) {
                "Not a FluxShare pairing link: $text"
            }
            require(uri.rawPath.isNullOrEmpty()) { "A pairing link has no path: $text" }
            require(uri.rawFragment.isNullOrEmpty()) { "A pairing link has no fragment: $text" }
            require(uri.rawUserInfo.isNullOrEmpty()) { "A pairing link has no user info: $text" }

            val parameters = parseQuery(uri.rawQuery ?: throw IllegalArgumentException("A pairing link needs parameters: $text"))
            val id = parameters["id"] ?: throw IllegalArgumentException("A pairing link needs a device id: $text")
            val fingerprintText = parameters["fp"]
                ?: throw IllegalArgumentException("A pairing link needs a fingerprint: $text")
            require(id.isNotBlank()) { "A pairing link needs a non-empty device id: $text" }

            return PairingLink(DeviceId(id), CertificateFingerprint.parse(fingerprintText))
        }

        fun parseOrNull(text: String): PairingLink? = runCatching { parse(text) }.getOrNull()

        private fun encode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8)

        private fun parseQuery(query: String): Map<String, String> {
            val parameters = mutableMapOf<String, String>()
            if (query.isEmpty()) return parameters
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val separator = pair.indexOf('=')
                require(separator > 0) { "Malformed pairing link parameter: $pair" }
                val key = decode(pair.substring(0, separator))
                val value = decode(pair.substring(separator + 1))
                require(parameters.put(key, value) == null) {
                    "Duplicate pairing link parameter: $key"
                }
            }
            return parameters
        }

        private fun decode(value: String): String =
            URLDecoder.decode(value, StandardCharsets.UTF_8)
    }
}
