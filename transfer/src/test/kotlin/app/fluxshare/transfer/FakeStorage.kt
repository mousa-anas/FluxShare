package app.fluxshare.transfer

import app.fluxshare.core.FluxShareError
import app.fluxshare.core.FluxShareResult
import app.fluxshare.storage.FileSink
import app.fluxshare.storage.FileSource
import app.fluxshare.storage.StorageProvider
import app.fluxshare.storage.TransferFile
import java.io.ByteArrayOutputStream

/** In-memory [StorageProvider] that remembers every source and sink it handed out. */
internal class FakeStorage : StorageProvider {
    private val contents = LinkedHashMap<String, ByteArray>()
    private val sinks = LinkedHashMap<String, ByteArrayOutputStream>()

    val openedSources = mutableListOf<FakeSource>()
    val createdSinks = mutableListOf<FakeSink>()

    fun addFile(handle: String, bytes: ByteArray) {
        contents[handle] = bytes
    }

    fun received(handle: String): ByteArray? = synchronized(sinks) { sinks[handle]?.toByteArray() }

    val allSourcesClosed: Boolean
        get() = openedSources.all { it.closed }

    val allSinksClosed: Boolean
        get() = createdSinks.all { it.closed }

    override suspend fun openSource(file: TransferFile): FluxShareResult<FileSource> {
        val bytes = contents[file.handle]
            ?: return FluxShareResult.Failure(FluxShareError.StorageUnavailable)
        return FluxShareResult.Success(FakeSource(file, bytes).also { openedSources += it })
    }

    override suspend fun createSink(file: TransferFile): FluxShareResult<FileSink> {
        val out = ByteArrayOutputStream()
        synchronized(sinks) { sinks[file.handle] = out }
        return FluxShareResult.Success(FakeSink(file, out).also { createdSinks += it })
    }
}

internal class FakeSource(
    override val file: TransferFile,
    private val bytes: ByteArray,
) : FileSource {
    private var position = 0

    @Volatile
    var closed: Boolean = false
        private set

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(length, bytes.size - position)
        bytes.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }

    override suspend fun close() {
        closed = true
    }
}

internal class FakeSink(
    override val file: TransferFile,
    private val out: ByteArrayOutputStream,
) : FileSink {
    @Volatile
    var closed: Boolean = false
        private set

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        check(!closed) { "Sink for ${file.name} is already closed" }
        out.write(buffer, offset, length)
    }

    override suspend fun close() {
        closed = true
    }
}
