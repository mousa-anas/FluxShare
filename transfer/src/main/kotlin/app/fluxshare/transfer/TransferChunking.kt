package app.fluxshare.transfer

import app.fluxshare.protocol.ProtocolLimits

/**
 * Bounds for the file content chunk size used by the transfer engine.
 *
 * A `DATA` frame carries an `i32` file index and an `i64` offset before the
 * content, so the largest chunk that still fits a single frame is
 * [MAX_CHUNK_SIZE_BYTES]. Chunks larger than
 * `ProtocolLimits.MAX_PAYLOAD_BYTES` would be rejected by the codec.
 */
object TransferChunking {
    /** 256 KiB keeps per-frame overhead low while bounding memory per transfer. */
    const val DEFAULT_CHUNK_SIZE_BYTES: Int = 256 * 1024

    /** Bytes a `DATA` frame spends on fields before the file content. */
    const val DATA_FRAME_HEADER_BYTES: Int = 4 + 8

    /** Largest chunk that still fits a `DATA` frame payload. */
    val MAX_CHUNK_SIZE_BYTES: Int =
        ProtocolLimits.MAX_PAYLOAD_BYTES - DATA_FRAME_HEADER_BYTES

    /** Returns [chunkSizeBytes] when it can travel in one frame, else throws. */
    fun requireValid(chunkSizeBytes: Int): Int {
        require(chunkSizeBytes in 1..MAX_CHUNK_SIZE_BYTES) {
            "chunkSizeBytes must be in 1..$MAX_CHUNK_SIZE_BYTES, was $chunkSizeBytes"
        }
        return chunkSizeBytes
    }
}
