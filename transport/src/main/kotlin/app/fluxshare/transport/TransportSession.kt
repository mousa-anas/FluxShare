package app.fluxshare.transport

/**
 * One ordered, reliable byte channel to a remote device.
 *
 * Sessions are bulk-transfer oriented: implementations read and write directly
 * into caller-provided buffers so large files can be streamed without extra
 * copies, which is critical for transfer throughput.
 */
interface TransportSession {
    /** The endpoint this session is connected to. */
    val endpoint: TransportEndpoint

    /** False once the session is closed or has failed. */
    val isOpen: Boolean

    /**
     * Reads up to [length] bytes into [buffer], starting at [offset].
     *
     * @return the number of bytes read, or `-1` when the remote side closed the
     * channel.
     */
    suspend fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Int

    /** Writes exactly [length] bytes from [buffer], starting at [offset]. */
    suspend fun write(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)

    suspend fun close()
}
