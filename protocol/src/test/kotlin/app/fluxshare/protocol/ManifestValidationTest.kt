package app.fluxshare.protocol

import app.fluxshare.core.FluxShareError
import app.fluxshare.core.TransferId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ManifestValidationTest {
    private val transferId = TransferId("transfer-1")

    private fun entry(name: String, size: Long = 1L) = ManifestEntry(name, size)

    private fun validate(vararg files: ManifestEntry): FluxManifest =
        ManifestValidator.validate(transferId, files.toList())

    @Test
    fun `accepts portable flat names and reports the total size`() {
        val manifest = validate(
            entry("photo.jpg", 2_048L),
            entry("readme.md", 512L),
            entry(".gitignore", 0L),
        )
        assertEquals(2_560L, manifest.totalBytes)
    }

    @Test
    fun `rejects blank names`() {
        assertFailsWith<FluxShareError.ProtocolViolation> { validate(entry("   ")) }
    }

    @Test
    fun `rejects relative path segments`() {
        assertFailsWith<FluxShareError.ProtocolViolation> { validate(entry(".")) }
        assertFailsWith<FluxShareError.ProtocolViolation> { validate(entry("..")) }
    }

    @Test
    fun `rejects path separators and traversal`() {
        assertFailsWith<FluxShareError.ProtocolViolation> { validate(entry("../secrets.txt")) }
        assertFailsWith<FluxShareError.ProtocolViolation> { validate(entry("/etc/passwd")) }
        assertFailsWith<FluxShareError.ProtocolViolation> { validate(entry("dir\\file.txt")) }
        assertFailsWith<FluxShareError.ProtocolViolation> { validate(entry("C:file.txt")) }
    }

    @Test
    fun `rejects control characters`() {
        assertFailsWith<FluxShareError.ProtocolViolation> { validate(entry("bad\u0000name")) }
        assertFailsWith<FluxShareError.ProtocolViolation> { validate(entry("bad\nname")) }
    }

    @Test
    fun `rejects non portable characters`() {
        assertFailsWith<FluxShareError.ProtocolViolation> { validate(entry("what?.txt")) }
        assertFailsWith<FluxShareError.ProtocolViolation> { validate(entry("pipe|name")) }
    }

    @Test
    fun `rejects names ending with a dot or space`() {
        assertFailsWith<FluxShareError.ProtocolViolation> { validate(entry("trailing.")) }
        assertFailsWith<FluxShareError.ProtocolViolation> { validate(entry("trailing ")) }
    }

    @Test
    fun `rejects Windows reserved device names`() {
        assertFailsWith<FluxShareError.ProtocolViolation> { validate(entry("CON")) }
        assertFailsWith<FluxShareError.ProtocolViolation> { validate(entry("con.txt")) }
        assertFailsWith<FluxShareError.ProtocolViolation> { validate(entry("LPT1.log")) }
    }

    @Test
    fun `rejects case insensitive duplicates`() {
        assertFailsWith<FluxShareError.ProtocolViolation> {
            validate(entry("Photo.jpg"), entry("photo.JPG"))
        }
    }

    @Test
    fun `rejects negative sizes`() {
        assertFailsWith<FluxShareError.ProtocolViolation> { validate(entry("file.txt", -1L)) }
    }

    @Test
    fun `rejects a total size overflow`() {
        assertFailsWith<FluxShareError.ProtocolViolation> {
            validate(entry("a", Long.MAX_VALUE), entry("b", Long.MAX_VALUE))
        }
    }

    @Test
    fun `rejects an empty manifest`() {
        assertFailsWith<FluxShareError.ProtocolViolation> {
            ManifestValidator.validate(transferId, emptyList())
        }
    }

    @Test
    fun `rejects too many files`() {
        val files = List(ProtocolLimits.MAX_MANIFEST_FILES + 1) { index -> entry("file-$index.txt") }
        assertFailsWith<FluxShareError.ProtocolViolation> {
            ManifestValidator.validate(transferId, files)
        }
    }

    @Test
    fun `rejects an invalid MIME type`() {
        assertFailsWith<FluxShareError.ProtocolViolation> {
            validate(ManifestEntry("file.txt", 1L, mimeType = ""))
        }
        assertFailsWith<FluxShareError.ProtocolViolation> {
            validate(ManifestEntry("file.txt", 1L, mimeType = "text/plain\n"))
        }
    }

    @Test
    fun `rejects a malformed digest`() {
        assertFailsWith<FluxShareError.ProtocolViolation> {
            validate(ManifestEntry("file.txt", 1L, sha256 = ByteArray(16)))
        }
    }

    @Test
    fun `rejects Unicode normalization duplicates`() {
        assertFailsWith<FluxShareError.ProtocolViolation> {
            validate(entry("caf\u00e9.txt"), entry("cafe\u0301.txt"))
        }
    }

    @Test
    fun `rejects a truncated manifest payload`() {
        assertFailsWith<FluxShareError.ProtocolViolation> {
            ManifestCodec.decode(ByteArray(0))
        }
    }

    @Test
    fun `rejects a manifest with duplicate names on the wire`() {
        val payload = PayloadWriter().apply {
            writeString(transferId.value, ProtocolLimits.MAX_TRANSFER_ID_BYTES, "transferId")
            writeU16(2)
            repeat(2) {
                writeString("same.txt", ProtocolLimits.MAX_FILE_NAME_BYTES, "file name")
                writeI64(4L)
                writeOptionalString(null, ProtocolLimits.MAX_MIME_TYPE_BYTES, "MIME type")
                writeOptionalDigest(null, "digest")
            }
        }.toByteArray()

        assertFailsWith<FluxShareError.ProtocolViolation> { ManifestCodec.decode(payload) }
    }
}
