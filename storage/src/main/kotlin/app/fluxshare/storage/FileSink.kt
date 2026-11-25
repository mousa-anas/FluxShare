package app.fluxshare.storage

/** Writable side of a file taking part in a transfer. */
interface FileSink {
    val file: TransferFile

    /** Writes exactly [length] bytes from [buffer], starting at [offset]. */
    suspend fun write(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)

    suspend fun close()
}
