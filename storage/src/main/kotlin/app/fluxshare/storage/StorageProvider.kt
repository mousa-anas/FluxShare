package app.fluxshare.storage

import app.fluxshare.core.FluxShareResult

/**
 * Opens files for sending and creates files for receiving.
 *
 * Platform details (content URIs, SAF, Windows paths) stay inside
 * implementations; the transfer layer only sees [FileSource] and [FileSink].
 */
interface StorageProvider {
    suspend fun openSource(file: TransferFile): FluxShareResult<FileSource>

    suspend fun createSink(file: TransferFile): FluxShareResult<FileSink>
}
