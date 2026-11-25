package app.fluxshare.storage

/** Readable side of a file taking part in a transfer. */
interface FileSource {
    val file: TransferFile

    /**
     * Reads up to [length] bytes into [buffer], starting at [offset].
     *
     * @return the number of bytes read, or `-1` at end of file.
     */
    suspend fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Int

    suspend fun close()
}
