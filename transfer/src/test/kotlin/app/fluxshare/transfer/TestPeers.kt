package app.fluxshare.transfer

import app.fluxshare.core.DeviceId
import app.fluxshare.core.DevicePlatform
import app.fluxshare.core.FluxShareError
import app.fluxshare.core.FluxShareResult
import app.fluxshare.core.PeerDevice
import app.fluxshare.core.TransferId
import app.fluxshare.protocol.FluxFrame
import app.fluxshare.protocol.FluxFrameStream
import app.fluxshare.protocol.FluxManifest
import app.fluxshare.protocol.IncrementalSha256
import app.fluxshare.protocol.ManifestEntry
import app.fluxshare.protocol.ProtocolLimits
import app.fluxshare.transport.Transport
import app.fluxshare.transport.TransportEndpoint
import app.fluxshare.transport.TransportListener
import app.fluxshare.transport.TransportSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

internal val LOCAL_DEVICE = PeerDevice(
    id = DeviceId("local-device"),
    displayName = "Local device",
    platform = DevicePlatform.ANDROID,
)

internal val REMOTE_DEVICE = PeerDevice(
    id = DeviceId("remote-device"),
    displayName = "Remote device",
    platform = DevicePlatform.WINDOWS,
)

/** One file as it arrived at the scripted peer. */
internal class PeerFile(
    val bytes: ByteArray,
    val digest: ByteArray,
    val chunkSizes: List<Int>,
)

/** A scripted remote device driving the other end of a loopback TCP session. */
internal class Peer(val session: TransportSession) {
    val stream = FluxFrameStream(session)

    suspend fun greet(name: String = REMOTE_DEVICE.displayName) {
        stream.write(
            FluxFrame.Hello(
                PeerDevice(DeviceId("peer-device"), name, DevicePlatform.WINDOWS),
            ),
        )
    }

    suspend fun offer(manifest: FluxManifest) {
        stream.write(FluxFrame.Manifest(manifest))
    }

    suspend inline fun <reified T : FluxFrame> expectFrame(): T {
        val frame = stream.read() ?: fail("Peer closed while expecting ${T::class.simpleName}")
        if (frame !is T) {
            fail("Expected ${T::class.simpleName} but received ${frame::class.simpleName}")
        }
        return frame
    }

    /** Reads data frames until [fileCount] digest frames arrived, checking framing rules. */
    suspend fun receiveAll(fileCount: Int): List<PeerFile> {
        val files = mutableListOf<PeerFile>()
        var index = 0
        var buffer = ByteArrayOutputStream()
        var offset = 0L
        val chunks = mutableListOf<Int>()

        while (index < fileCount) {
            val frame = stream.read()
                ?: fail("Peer closed after ${files.size} of $fileCount files")
            when (frame) {
                is FluxFrame.Data -> {
                    assertEquals(index, frame.fileIndex, "file index")
                    assertEquals(offset, frame.offset, "contiguous offset in file $index")
                    assertTrue(
                        frame.bytes.size + TransferChunking.DATA_FRAME_HEADER_BYTES <=
                            ProtocolLimits.MAX_PAYLOAD_BYTES,
                        "a data frame exceeded the payload limit",
                    )
                    buffer.write(frame.bytes)
                    offset += frame.bytes.size
                    chunks += frame.bytes.size
                }

                is FluxFrame.FileDigest -> {
                    assertEquals(index, frame.fileIndex, "digest file index")
                    files += PeerFile(buffer.toByteArray(), frame.sha256, chunks.toList())
                    index++
                    buffer = ByteArrayOutputStream()
                    offset = 0L
                    chunks.clear()
                }

                else -> fail("Unexpected ${frame::class.simpleName} while receiving file data")
            }
        }
        return files
    }

    /** Sends one file as data frames followed by its digest frame. */
    suspend fun sendBytes(
        index: Int,
        bytes: ByteArray,
        chunkSize: Int = 1024,
        digest: ByteArray? = null,
    ) {
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size)
            stream.write(
                FluxFrame.Data(index, offset.toLong(), bytes.copyOfRange(offset, end)),
            )
            offset = end
        }
        stream.write(FluxFrame.FileDigest(index, digest ?: IncrementalSha256.of(bytes)))
    }

    /** Reads frames until the other side closes, tolerating a reset session. */
    suspend fun drain() {
        while (true) {
            val frame = try {
                stream.read()
            } catch (e: FluxShareError) {
                null
            }
            if (frame == null) return
        }
    }
}

/** Accepts one session on [listener] and runs [script] against it. */
internal fun CoroutineScope.startPeer(
    listener: TransportListener,
    script: suspend (Peer) -> Unit,
): Deferred<Unit> = async(Dispatchers.IO) {
    val session = listener.accept().requireSuccess("accept")
    try {
        script(Peer(session))
    } finally {
        session.close()
    }
}

/** Connects to [port] and runs [script] as the sending peer. */
internal fun CoroutineScope.startClient(
    transport: Transport,
    port: Int,
    script: suspend (Peer) -> Unit,
): Deferred<Unit> = async(Dispatchers.IO) {
    val session = transport.connect(TransportEndpoint("127.0.0.1", port))
        .requireSuccess("connect")
    try {
        script(Peer(session))
    } finally {
        session.close()
    }
}

internal fun <T> FluxShareResult<T>.requireSuccess(what: String): T = when (this) {
    is FluxShareResult.Success -> value
    is FluxShareResult.Failure -> fail("$what failed with $error")
}

internal fun manifestOf(
    vararg files: ManifestEntry,
    transferId: String = "transfer-1",
): FluxManifest = FluxManifest(TransferId(transferId), files.toList())

internal fun entryOf(
    name: String,
    size: Long,
    mimeType: String? = "application/octet-stream",
    sha256: ByteArray? = null,
): ManifestEntry = ManifestEntry(name, size, mimeType, sha256)

/** Deterministic, non-repeating-per-file payload. */
internal fun patterned(size: Int, seed: Int = 0): ByteArray =
    ByteArray(size) { index -> ((index * 31 + seed * 17) % 251).toByte() }

/** Collapses runs of identical states, which is how a UI would show transitions. */
internal fun List<TransferProgress>.states(): List<TransferState> {
    val states = mutableListOf<TransferState>()
    for (progress in this) {
        if (states.lastOrNull() != progress.state) states += progress.state
    }
    return states
}
