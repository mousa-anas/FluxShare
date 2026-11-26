package app.fluxshare.protocol

import app.fluxshare.core.FluxShareError
import app.fluxshare.transport.TransportSession

/**
 * Reads and writes [FluxFrame]s over any [TransportSession].
 *
 * The stream keeps no buffered state between calls, so one reader coroutine and
 * one writer coroutine may share a session. Reading stops cleanly at end of
 * stream; a frame that is cut short is a protocol violation, never silently
 * ignored.
 */
class FluxFrameStream(private val session: TransportSession) {

    /** Encodes and writes one frame, the whole frame in a single session write. */
    suspend fun write(frame: FluxFrame) {
        session.write(FluxFrameCodec.encode(frame))
    }

    /**
     * Reads the next frame.
     *
     * @return the frame, or `null` when the peer closed the channel between
     * frames.
     * @throws FluxShareError.ProtocolViolation when the peer sends malformed,
     * oversized or truncated data.
     * @throws FluxShareError.PeerUnavailable when the underlying channel fails.
     */
    suspend fun read(): FluxFrame? {
        val header = ByteArray(ProtocolLimits.FRAME_HEADER_BYTES)
        val firstByte = session.read(header, 0, 1)
        if (firstByte < 0) return null
        if (firstByte == 0) {
            throw FluxShareError.ProtocolViolation("Session returned no data for a frame header.")
        }
        readFully(header, offset = 1, length = header.size - 1)

        val parsed = FluxFrameCodec.decodeHeader(header)
        val payload = ByteArray(parsed.payloadLength)
        if (payload.isNotEmpty()) {
            readFully(payload, offset = 0, length = payload.size)
        }

        return FluxFrameCodec.decodePayload(parsed.type, payload)
    }

    private suspend fun readFully(buffer: ByteArray, offset: Int, length: Int) {
        var read = 0
        var emptyReads = 0
        while (read < length) {
            val count = session.read(buffer, offset + read, length - read)
            when {
                count < 0 -> throw FluxShareError.ProtocolViolation("Frame was truncated by the peer.")
                count == 0 -> {
                    // A conforming byte channel only returns 0 for a zero-length
                    // request. Bound the retries so a broken session cannot spin here.
                    emptyReads++
                    if (emptyReads > MAX_EMPTY_READS) {
                        throw FluxShareError.ProtocolViolation(
                            "Session made no progress while reading a frame.",
                        )
                    }
                }

                else -> {
                    emptyReads = 0
                    read += count
                }
            }
        }
    }

    private companion object {
        const val MAX_EMPTY_READS = 1_024
    }
}
