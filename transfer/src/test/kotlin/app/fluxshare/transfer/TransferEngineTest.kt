package app.fluxshare.transfer

import app.fluxshare.core.FluxShareError
import app.fluxshare.core.FluxShareResult
import app.fluxshare.protocol.FluxFrame
import app.fluxshare.protocol.IncrementalSha256
import app.fluxshare.protocol.ProtocolErrorCode
import app.fluxshare.storage.StorageProvider
import app.fluxshare.storage.TransferFile
import app.fluxshare.transport.TransportEndpoint
import app.fluxshare.transport.tcp.TcpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TransferEngineTest {

    @Test
    fun `sends multiple files and completes only after the receiver closes`() = runBlocking {
        withTimeout(20_000) {
            val alpha = "alpha content".toByteArray()
            val beta = patterned(1500, seed = 7)
            val storage = FakeStorage().apply {
                addFile("alpha", alpha)
                addFile("beta", beta)
            }
            val files = listOf(
                TransferFile("alpha", "alpha.txt", alpha.size.toLong(), "text/plain"),
                TransferFile("beta", "beta.bin", beta.size.toLong(), "application/octet-stream"),
            )
            val listener = TcpTransport().listen(0).requireSuccess("listen")
            try {
                val peer = startPeer(listener) { remote ->
                    assertEquals(
                        LOCAL_DEVICE.displayName,
                        remote.expectFrame<FluxFrame.Hello>().peer.displayName,
                    )
                    val manifest = remote.expectFrame<FluxFrame.Manifest>().manifest
                    assertEquals(listOf("alpha.txt", "beta.bin"), manifest.files.map { it.name })
                    assertEquals(
                        listOf(alpha.size.toLong(), beta.size.toLong()),
                        manifest.files.map { it.sizeBytes },
                    )

                    remote.stream.write(
                        FluxFrame.ManifestResponse(manifest.transferId, accepted = true),
                    )
                    val received = remote.receiveAll(2)
                    assertContentEquals(alpha, received[0].bytes)
                    assertContentEquals(beta, received[1].bytes)
                    assertContentEquals(IncrementalSha256.of(alpha), received[0].digest)
                    assertContentEquals(IncrementalSha256.of(beta), received[1].digest)
                    assertEquals(listOf(1024, 476), received[1].chunkSizes)
                }

                val progress = engine(storage, listener.endpoint.port)
                    .execute(requestOf(files))
                    .toList()
                peer.await()

                assertEquals(
                    listOf(
                        TransferState.CONNECTING,
                        TransferState.AWAITING_APPROVAL,
                        TransferState.TRANSFERRING,
                        TransferState.COMPLETED,
                    ),
                    progress.states(),
                )
                val last = progress.last()
                assertEquals(TransferState.COMPLETED, last.state)
                assertEquals((alpha.size + beta.size).toLong(), last.totalBytes)
                assertEquals(last.totalBytes, last.bytesTransferred)
                assertEquals(1f, last.fraction)

                val transferred = progress.map { it.bytesTransferred }
                assertEquals(transferred.sorted(), transferred, "progress must never go backwards")
                assertTrue(storage.allSourcesClosed, "every opened source must be closed")
            } finally {
                listener.close()
            }
        }
    }

    @Test
    fun `sends a zero byte file as a digest without data frames`() = runBlocking {
        withTimeout(20_000) {
            val storage = FakeStorage().apply { addFile("empty", ByteArray(0)) }
            val files = listOf(TransferFile("empty", "empty.txt", 0, "text/plain"))
            val listener = TcpTransport().listen(0).requireSuccess("listen")
            try {
                val peer = startPeer(listener) { remote ->
                    remote.expectFrame<FluxFrame.Hello>()
                    val manifest = remote.expectFrame<FluxFrame.Manifest>().manifest
                    remote.stream.write(
                        FluxFrame.ManifestResponse(manifest.transferId, accepted = true),
                    )
                    val received = remote.receiveAll(1).single()
                    assertEquals(0, received.bytes.size)
                    assertEquals(emptyList(), received.chunkSizes)
                    assertContentEquals(IncrementalSha256.of(ByteArray(0)), received.digest)
                }

                val progress = engine(storage, listener.endpoint.port)
                    .execute(requestOf(files))
                    .toList()
                peer.await()

                assertEquals(TransferState.COMPLETED, progress.last().state)
                assertEquals(0L, progress.last().totalBytes)
                assertEquals(0L, progress.last().bytesTransferred)
            } finally {
                listener.close()
            }
        }
    }

    @Test
    fun `default chunk size is 256 KiB and files span several frames`() = runBlocking {
        withTimeout(20_000) {
            assertEquals(256 * 1024, TransferChunking.DEFAULT_CHUNK_SIZE_BYTES)
            val payload = patterned(TransferChunking.DEFAULT_CHUNK_SIZE_BYTES + 1024, seed = 3)
            val storage = FakeStorage().apply { addFile("big", payload) }
            val files = listOf(TransferFile("big", "big.bin", payload.size.toLong()))

            val listener = TcpTransport().listen(0).requireSuccess("listen")
            try {
                val peer = startPeer(listener) { remote ->
                    remote.expectFrame<FluxFrame.Hello>()
                    val manifest = remote.expectFrame<FluxFrame.Manifest>().manifest
                    remote.stream.write(
                        FluxFrame.ManifestResponse(manifest.transferId, accepted = true),
                    )
                    val received = remote.receiveAll(1).single()
                    assertEquals(
                        listOf(TransferChunking.DEFAULT_CHUNK_SIZE_BYTES, 1024),
                        received.chunkSizes,
                    )
                    assertContentEquals(payload, received.bytes)
                }

                val progress = engineWithDefaults(storage, listener.endpoint.port)
                    .execute(requestOf(files))
                    .toList()
                peer.await()

                assertEquals(TransferState.COMPLETED, progress.last().state)
                assertEquals(payload.size.toLong(), progress.last().bytesTransferred)
            } finally {
                listener.close()
            }
        }
    }

    @Test
    fun `rejected manifest fails with TransferRejected and reads no file`() = runBlocking {
        withTimeout(20_000) {
            val storage = FakeStorage().apply { addFile("alpha", "alpha".toByteArray()) }
            val files = listOf(TransferFile("alpha", "alpha.txt", 5, "text/plain"))
            val listener = TcpTransport().listen(0).requireSuccess("listen")
            try {
                val peer = startPeer(listener) { remote ->
                    remote.expectFrame<FluxFrame.Hello>()
                    val manifest = remote.expectFrame<FluxFrame.Manifest>().manifest
                    remote.stream.write(
                        FluxFrame.ManifestResponse(
                            transferId = manifest.transferId,
                            accepted = false,
                            reason = "not enough space",
                        ),
                    )
                }

                val progress = mutableListOf<TransferProgress>()
                assertFailsWith<FluxShareError.TransferRejected> {
                    engine(storage, listener.endpoint.port)
                        .execute(requestOf(files))
                        .collect { progress += it }
                }
                peer.await()

                assertEquals(TransferState.FAILED, progress.last().state)
                assertTrue(
                    progress.none { it.state == TransferState.COMPLETED },
                    "a rejected transfer must never complete",
                )
                assertTrue(storage.openedSources.isEmpty(), "no file may be read before approval")
            } finally {
                listener.close()
            }
        }
    }

    @Test
    fun `receiver error frame fails the transfer after the data was sent`() = runBlocking {
        withTimeout(20_000) {
            val payload = patterned(2048, seed = 11)
            val storage = FakeStorage().apply { addFile("payload", payload) }
            val files = listOf(TransferFile("payload", "payload.bin", payload.size.toLong()))
            val listener = TcpTransport().listen(0).requireSuccess("listen")
            try {
                val peer = startPeer(listener) { remote ->
                    remote.expectFrame<FluxFrame.Hello>()
                    val manifest = remote.expectFrame<FluxFrame.Manifest>().manifest
                    remote.stream.write(
                        FluxFrame.ManifestResponse(manifest.transferId, accepted = true),
                    )
                    remote.receiveAll(1)
                    remote.stream.write(
                        FluxFrame.Error(ProtocolErrorCode.CHECKSUM_MISMATCH, "digest mismatch"),
                    )
                }

                val progress = mutableListOf<TransferProgress>()
                assertFailsWith<FluxShareError.Unexpected> {
                    engine(storage, listener.endpoint.port)
                        .execute(requestOf(files))
                        .collect { progress += it }
                }
                peer.await()

                assertEquals(TransferState.FAILED, progress.last().state)
                assertTrue(progress.none { it.state == TransferState.COMPLETED })
                assertTrue(storage.allSourcesClosed)
            } finally {
                listener.close()
            }
        }
    }

    @Test
    fun `disconnect while sending never reports success`() = runBlocking {
        withTimeout(30_000) {
            // Larger than any socket buffer, so the sender really notices the
            // peer is gone instead of finishing into the kernel buffer.
            val payload = patterned(4 * 1024 * 1024, seed = 5)
            val storage = FakeStorage().apply { addFile("payload", payload) }
            val files = listOf(TransferFile("payload", "payload.bin", payload.size.toLong()))
            val listener = TcpTransport().listen(0).requireSuccess("listen")
            try {
                val peer = startPeer(listener) { remote ->
                    remote.expectFrame<FluxFrame.Hello>()
                    val manifest = remote.expectFrame<FluxFrame.Manifest>().manifest
                    remote.stream.write(
                        FluxFrame.ManifestResponse(manifest.transferId, accepted = true),
                    )
                    remote.expectFrame<FluxFrame.Data>()
                    // Close the session without reading the rest of the file.
                }

                val progress = mutableListOf<TransferProgress>()
                assertFailsWith<FluxShareError> {
                    engine(storage, listener.endpoint.port, chunkSizeBytes = 64 * 1024)
                        .execute(requestOf(files))
                        .collect { progress += it }
                }
                peer.await()

                assertNotEquals(TransferState.COMPLETED, progress.last().state)
                assertEquals(TransferState.FAILED, progress.last().state)
                assertTrue(storage.allSourcesClosed)
            } finally {
                listener.close()
            }
        }
    }

    @Test
    fun `cancel closes the session and ends in CANCELLED`() = runBlocking {
        withTimeout(20_000) {
            val payload = patterned(64 * 1024, seed = 9)
            val storage = FakeStorage().apply { addFile("payload", payload) }
            val files = listOf(TransferFile("payload", "payload.bin", payload.size.toLong()))
            val listener = TcpTransport().listen(0).requireSuccess("listen")
            try {
                val peer = startPeer(listener) { remote ->
                    remote.expectFrame<FluxFrame.Hello>()
                    val manifest = remote.expectFrame<FluxFrame.Manifest>().manifest
                    remote.stream.write(
                        FluxFrame.ManifestResponse(manifest.transferId, accepted = true),
                    )
                    remote.drain()
                }

                val transfer = engine(storage, listener.endpoint.port)
                val progress = mutableListOf<TransferProgress>()
                val collector = launch(Dispatchers.IO) {
                    transfer.execute(requestOf(files)).collect { sample ->
                        progress += sample
                        if (sample.state == TransferState.TRANSFERRING &&
                            sample.bytesTransferred > 0
                        ) {
                            transfer.cancel(sample.transferId)
                        }
                    }
                }
                collector.join()
                peer.await()

                assertEquals(TransferState.CANCELLED, progress.last().state)
                assertTrue(progress.none { it.state == TransferState.COMPLETED })
                assertTrue(storage.openedSources.isNotEmpty(), "the file was opened before cancelling")
                assertTrue(storage.allSourcesClosed, "cancel must close the open source")
            } finally {
                listener.close()
            }
        }
    }

    private fun requestOf(files: List<TransferFile>) = TransferRequest(
        peer = REMOTE_DEVICE,
        direction = TransferDirection.SEND,
        files = files,
    )

    private fun engine(
        storage: StorageProvider,
        port: Int,
        chunkSizeBytes: Int = 1024,
    ) = DefaultTransferEngine(
        transport = TcpTransport(),
        storage = storage,
        localDevice = LOCAL_DEVICE,
        endpoints = PeerEndpointResolver {
            FluxShareResult.Success(TransportEndpoint("127.0.0.1", port))
        },
        chunkSizeBytes = chunkSizeBytes,
    )

    private fun engineWithDefaults(storage: StorageProvider, port: Int) = DefaultTransferEngine(
        transport = TcpTransport(),
        storage = storage,
        localDevice = LOCAL_DEVICE,
        endpoints = PeerEndpointResolver {
            FluxShareResult.Success(TransportEndpoint("127.0.0.1", port))
        },
    )
}
