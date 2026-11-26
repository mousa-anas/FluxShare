package app.fluxshare.protocol

import app.fluxshare.core.TransferId

/**
 * Encodes and decodes manifest payloads.
 *
 * Layout (big-endian):
 * ```
 * transferId : u16-length UTF-8
 * fileCount  : u16
 * files      : fileCount * (name, size, optional MIME type, optional SHA-256)
 * ```
 */
internal object ManifestCodec {
    fun encode(manifest: FluxManifest): ByteArray {
        val validated = ManifestValidator.validate(manifest.transferId, manifest.files)
        val writer = PayloadWriter(initialCapacity = 64 + validated.files.size * 64)
        writer.writeString(
            validated.transferId.value,
            ProtocolLimits.MAX_TRANSFER_ID_BYTES,
            "transferId",
        )
        writer.writeU16(validated.files.size)
        validated.files.forEach { entry ->
            writer.writeString(entry.name, ProtocolLimits.MAX_FILE_NAME_BYTES, "file name")
            writer.writeI64(entry.sizeBytes)
            writer.writeOptionalString(entry.mimeType, ProtocolLimits.MAX_MIME_TYPE_BYTES, "MIME type")
            writer.writeOptionalDigest(entry.sha256, "digest")
        }
        return writer.toByteArray()
    }

    fun decode(payload: ByteArray): FluxManifest {
        val reader = PayloadReader(payload)
        val transferIdValue = reader.readString(ProtocolLimits.MAX_TRANSFER_ID_BYTES, "transferId")
        val transferId = try {
            TransferId(transferIdValue)
        } catch (e: IllegalArgumentException) {
            throw app.fluxshare.core.FluxShareError.ProtocolViolation(
                "Transfer identifier is invalid: ${e.message}",
            )
        }

        val fileCount = reader.readU16("fileCount")
        if (fileCount > ProtocolLimits.MAX_MANIFEST_FILES) {
            throw app.fluxshare.core.FluxShareError.ProtocolViolation(
                "Manifest declares $fileCount files, above the limit of ${ProtocolLimits.MAX_MANIFEST_FILES}.",
            )
        }

        val files = ArrayList<ManifestEntry>(fileCount)
        repeat(fileCount) {
            val name = reader.readString(ProtocolLimits.MAX_FILE_NAME_BYTES, "file name")
            val sizeBytes = reader.readI64("file size")
            val mimeType = reader.readOptionalString(ProtocolLimits.MAX_MIME_TYPE_BYTES, "MIME type")
            val sha256 = reader.readOptionalDigest("digest")
            files += ManifestEntry(
                name = name,
                sizeBytes = sizeBytes,
                mimeType = mimeType,
                sha256 = sha256,
            )
        }
        reader.requireExhausted()

        return ManifestValidator.validate(transferId, files)
    }
}
