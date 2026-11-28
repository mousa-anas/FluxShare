package app.fluxshare.security

import app.fluxshare.core.DeviceId
import java.security.cert.X509Certificate

/**
 * Device identifiers travel inside a certificate's subject alternative names.
 *
 * The URI form is used instead of a DNS name or an e-mail address because a
 * FluxShare device has no domain, and because it round-trips any identifier
 * without additional encoding.
 */
internal const val DEVICE_ID_URI_PREFIX: String = "fluxshare:device:"

/** X.509 general name type 6, `uniformResourceIdentifier`. */
private const val GENERAL_NAME_URI: Int = 6

internal fun deviceIdUri(deviceId: DeviceId): String = DEVICE_ID_URI_PREFIX + deviceId.value

/**
 * Reads the single FluxShare device identifier from [certificate].
 *
 * @return the identifier, or `null` when the certificate has no FluxShare SAN,
 * has more than one, or carries an empty identifier.
 */
internal fun deviceIdFromCertificate(certificate: X509Certificate): DeviceId? {
    val uris = runCatching { certificate.subjectAlternativeNames }.getOrNull()
        ?: return null
    val identifiers = uris.mapNotNull { entry ->
        val type = entry.getOrNull(0) as? Int ?: return@mapNotNull null
        if (type != GENERAL_NAME_URI) return@mapNotNull null
        val value = entry.getOrNull(1) as? String ?: return@mapNotNull null
        if (!value.startsWith(DEVICE_ID_URI_PREFIX)) return@mapNotNull null
        value.removePrefix(DEVICE_ID_URI_PREFIX)
    }.filter { it.isNotBlank() }
        .distinct()

    return identifiers.singleOrNull()?.let(::DeviceId)
}
