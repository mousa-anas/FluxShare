package app.fluxshare.protocol

import app.fluxshare.core.DeviceId
import app.fluxshare.core.DevicePlatform
import app.fluxshare.core.FluxShareError
import app.fluxshare.core.PeerDevice
import app.fluxshare.core.TransferId

/** Machine-readable failure reason carried by a `FILE_DIGEST`-adjacent error frame. */
enum class ProtocolErrorCode(val id: Int) {
    PROTOCOL_VIOLATION(0x01),
    UNSUPPORTED_FRAME(0x02),
    MANIFEST_REJECTED(0x03),
    TRANSFER_FAILED(0x04),
    CHECKSUM_MISMATCH(0x05),
    INTERNAL_ERROR(0x7F),

    /** A code from a newer protocol revision that this build does not know. */
    UNKNOWN(0xFF),
    ;

    companion object {
        fun fromId(id: Int): ProtocolErrorCode = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/**
 * One FluxShare protocol message.
 *
 * Frames are transport independent; the TCP implementation simply moves the
 * encoded bytes. Additional transports can reuse every frame unchanged.
 */
sealed interface FluxFrame {
    /** Opening frame identifying the peer. */
    data class Hello(
        val peer: PeerDevice,
    ) : FluxFrame

    /** The sending side offers these files. */
    data class Manifest(
        val manifest: FluxManifest,
    ) : FluxFrame

    /** The receiving side accepts or rejects the offered manifest. */
    data class ManifestResponse(
        val transferId: TransferId,
        val accepted: Boolean,
        val reason: String? = null,
    ) : FluxFrame

    /** A chunk of file content. */
    data class Data(
        val fileIndex: Int,
        val offset: Long,
        val bytes: ByteArray,
    ) : FluxFrame

    /** The sending side finished a file and reports its SHA-256 digest. */
    data class FileDigest(
        val fileIndex: Int,
        val sha256: ByteArray,
    ) : FluxFrame

    /**
     * The receiving side verified every file of [transferId].
     *
     * Sent after the last `FILE_DIGEST`. The sender reports success only when
     * this frame arrives; a session that closes without it failed.
     */
    data class TransferComplete(
        val transferId: TransferId,
    ) : FluxFrame

    /** A recoverable protocol or transfer error. */
    data class Error(
        val code: ProtocolErrorCode,
        val message: String? = null,
    ) : FluxFrame
}

/** Maps a [DevicePlatform] onto its stable wire value. */
internal fun DevicePlatform.toWireId(): Int = when (this) {
    DevicePlatform.ANDROID -> 1
    DevicePlatform.WINDOWS -> 2
    DevicePlatform.UNKNOWN -> 0
}

/** Restores a [DevicePlatform] from its stable wire value. */
internal fun devicePlatformFromWireId(id: Int): DevicePlatform = when (id) {
    0 -> DevicePlatform.UNKNOWN
    1 -> DevicePlatform.ANDROID
    2 -> DevicePlatform.WINDOWS
    else -> throw FluxShareError.ProtocolViolation("Unknown device platform id $id.")
}

/** Builds a [PeerDevice] while mapping invalid identifiers onto protocol errors. */
internal fun peerDeviceFromWire(id: String, displayName: String, platform: DevicePlatform): PeerDevice {
    val deviceId = try {
        DeviceId(id)
    } catch (e: IllegalArgumentException) {
        throw FluxShareError.ProtocolViolation("Device identifier is invalid: ${e.message}")
    }
    return PeerDevice(id = deviceId, displayName = displayName, platform = platform)
}
