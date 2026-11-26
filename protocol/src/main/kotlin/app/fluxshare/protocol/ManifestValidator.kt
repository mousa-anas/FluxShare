package app.fluxshare.protocol

import app.fluxshare.core.FluxShareError
import app.fluxshare.core.TransferId
import java.text.Normalizer
import java.util.Locale

/**
 * Validates manifests received from, or about to be sent to, another device.
 *
 * Validation is intentionally strict because a hostile peer controls every
 * byte of a manifest. Phase 1 manifests describe flat files only: directory
 * traversal, absolute paths and platform-specific separators are rejected
 * outright so a receiver can never be tricked into writing outside its chosen
 * destination.
 */
object ManifestValidator {
    /** Characters Windows forbids in file names. Rejected for cross-platform safety. */
    private val WINDOWS_FORBIDDEN_CHARS = charArrayOf('<', '>', ':', '"', '|', '?', '*')

    /** Windows device names that cannot be used as regular file names. */
    private val WINDOWS_RESERVED_NAMES: Set<String> = buildSet {
        add("CON")
        add("PRN")
        add("AUX")
        add("NUL")
        for (index in 1..9) {
            add("COM$index")
            add("LPT$index")
        }
    }

    /**
     * Returns a validated copy of [transferId] plus [files].
     *
     * @throws FluxShareError.ProtocolViolation when any rule is broken.
     */
    fun validate(transferId: TransferId, files: List<ManifestEntry>): FluxManifest {
        val transferIdBytes = transferId.value.toByteArray(Charsets.UTF_8).size
        if (transferIdBytes > ProtocolLimits.MAX_TRANSFER_ID_BYTES) {
            throw FluxShareError.ProtocolViolation(
                "Transfer identifier exceeds ${ProtocolLimits.MAX_TRANSFER_ID_BYTES} bytes.",
            )
        }
        if (files.isEmpty()) {
            throw FluxShareError.ProtocolViolation("Manifest must contain at least one file.")
        }
        if (files.size > ProtocolLimits.MAX_MANIFEST_FILES) {
            throw FluxShareError.ProtocolViolation(
                "Manifest contains ${files.size} files, above the limit of ${ProtocolLimits.MAX_MANIFEST_FILES}.",
            )
        }

        val seenNames = HashSet<String>(files.size)
        var totalBytes = 0L
        files.forEachIndexed { index, entry ->
            validateEntry(entry, index)

            // Normalize first: on macOS, NFC and NFD spellings of the same name
            // collide on disk even though the raw strings differ.
            val duplicateKey = Normalizer.normalize(entry.name, Normalizer.Form.NFC)
                .lowercase(Locale.ROOT)
            if (!seenNames.add(duplicateKey)) {
                throw FluxShareError.ProtocolViolation(
                    "Manifest contains the duplicate file name \"${entry.name}\".",
                )
            }

            totalBytes = try {
                Math.addExact(totalBytes, entry.sizeBytes)
            } catch (e: ArithmeticException) {
                throw FluxShareError.ProtocolViolation("Manifest total size overflows 64-bit bytes.")
            }
        }

        return FluxManifest(transferId = transferId, files = files.toList())
    }

    private fun validateEntry(entry: ManifestEntry, index: Int) {
        val field = "files[$index]"
        validateName(entry.name, field)

        if (entry.sizeBytes < 0L) {
            throw FluxShareError.ProtocolViolation("$field has a negative size.")
        }

        entry.mimeType?.let { mimeType ->
            val bytes = mimeType.toByteArray(Charsets.UTF_8).size
            if (bytes == 0 || bytes > ProtocolLimits.MAX_MIME_TYPE_BYTES) {
                throw FluxShareError.ProtocolViolation(
                    "$field MIME type must be 1..${ProtocolLimits.MAX_MIME_TYPE_BYTES} bytes.",
                )
            }
            if (mimeType.any { it.isISOControl() }) {
                throw FluxShareError.ProtocolViolation("$field MIME type contains control characters.")
            }
        }

        entry.sha256?.let { digest ->
            if (digest.size != ProtocolLimits.SHA256_BYTES) {
                throw FluxShareError.ProtocolViolation(
                    "$field digest must be ${ProtocolLimits.SHA256_BYTES} bytes.",
                )
            }
        }
    }

    private fun validateName(name: String, field: String) {
        if (name.isBlank()) {
            throw FluxShareError.ProtocolViolation("$field has a blank name.")
        }
        if (name == "." || name == "..") {
            throw FluxShareError.ProtocolViolation("$field has a relative path name.")
        }
        if (name.any { it.isISOControl() }) {
            throw FluxShareError.ProtocolViolation("$field name contains control characters.")
        }
        if (name.contains('/') || name.contains('\\')) {
            throw FluxShareError.ProtocolViolation("$field name must not contain path separators.")
        }
        if (name.any { it in WINDOWS_FORBIDDEN_CHARS }) {
            throw FluxShareError.ProtocolViolation("$field name contains characters that are not portable.")
        }
        if (name.endsWith('.') || name.endsWith(' ')) {
            throw FluxShareError.ProtocolViolation("$field name must not end with a dot or a space.")
        }

        val bytes = name.toByteArray(Charsets.UTF_8).size
        if (bytes > ProtocolLimits.MAX_FILE_NAME_BYTES) {
            throw FluxShareError.ProtocolViolation(
                "$field name exceeds ${ProtocolLimits.MAX_FILE_NAME_BYTES} bytes.",
            )
        }

        val baseName = name.substringBefore('.').trim().uppercase(Locale.ROOT)
        if (baseName in WINDOWS_RESERVED_NAMES) {
            throw FluxShareError.ProtocolViolation("$field name is reserved on Windows.")
        }
    }
}
