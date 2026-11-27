package app.fluxshare.transfer

import app.fluxshare.core.FluxShareError
import app.fluxshare.protocol.FluxFrame
import app.fluxshare.protocol.IncrementalSha256
import app.fluxshare.protocol.ProtocolErrorCode
import app.fluxshare.transport.tcp.TcpTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransferReceiverTest {

    @Test
    fun `receives multiple files then confirms and closes`() = runBlocking {
        withTimeout(20_000) {
            val transport = TcpTransport()
            val listener = transport.listen(0).requireSuccess("listen")
            val storage = FakeStorage()
            val manifest = manifestOf(
                entryOf("first.txt", 11, "text/plain"),
                entryOf("empty.txt", 0, "text/plain"),
                entryOf("last.bin", 3000),
            )
            val contents = listOf("hello world".toByteArray(), ByteArray(0), patterned(3000, 5))

            try {
                val peer = startClient(transport, listener.endpoint.port) { remote ->
                    remote.greet()
                    remote.offer(manifest)

                    val response = remote.expectFrame<FluxFrame.ManifestResponse>()
                    assertEquals(manifest.transferId, response.transferId)
                    assertTrue(response.accepted)

                    contents.forEachIndexed { index, bytes ->
                        remote.sendBytes(index, bytes, chunkSize = 1024)
                    }

                    val complete = remote.expectFrame<FluxFrame.TransferComplete>()
                    assertEquals(manifest.transferId, complete.transferId)
                    assertNull(
                        remote.stream.read(),
                        "the receiver must close the session when it is done",
                    )
                }

                val progress = async(Dispatchers.IO) {
                    TransferReceiver(storage)
                        .receive(listener) { incoming ->
                            assertEquals(manifest.transferId, incoming.transferId)
                            assertEquals(REMOTE_DEVICE.displayName, incoming.peer.displayName)
                            assertEquals(3011L, incoming.totalBytes)
                            assertTrue(
                                storage.createdSinks.isEmpty(),
                                "no file may be written before approval",
                            )
                            TransferDecision.Accept
                        }
                        .toList()
                }.await()
                peer.await()

                assertContentEquals(contents[0], storage.received("first.txt"))
                assertEquals(0, storage.received("empty.txt")!!.size)
                assertContentEquals(contents[2], storage.received("last.bin"))

                assertEquals(
                    listOf(
                        TransferState.CONNECTING,
                        TransferState.AWAITING_APPROVAL,
                        TransferState.TRANSFERRING,
                        TransferState.COMPLETED,
                    ),
                    progress.states(),
                )
                assertEquals(3011L, progress.last().bytesTransferred)
                assertEquals(3011L, progress.last().totalBytes)
                assertEquals(1f, progress.last().fraction)
                assertTrue(storage.allSinksClosed)
            } finally {
                listener.close()
            }
        }
    }

    @Test
    fun `rejection is sent to the sender and creates no files`() = runBlocking {
        withTimeout(20_000) {
            val transport = TcpTransport()
            val listener = transport.listen(0).requireSuccess("listen")
            val storage = FakeStorage()
            val manifest = manifestOf(entryOf("file.bin", 128))
            try {
                val peer = startClient(transport, listener.endpoint.port) { remote ->
                    remote.greet()
                    remote.offer(manifest)
                    val response = remote.expectFrame<FluxFrame.ManifestResponse>()
                    assertFalse(response.accepted)
                    assertEquals("no space", response.reason)
                }

                val progress = mutableListOf<TransferProgress>()
                assertFailsWith<FluxShareError.TransferRejected> {
                    TransferReceiver(storage)
                        .receive(listener) { TransferDecision.Reject("no space") }
                        .collect { progress += it }
                }
                peer.await()

                assertEquals(TransferState.FAILED, progress.last().state)
                assertTrue(progress.none { it.state == TransferState.COMPLETED })
                assertTrue(storage.createdSinks.isEmpty(), "a rejected offer must not touch storage")
            } finally {
                listener.close()
            }
        }
    }

    @Test
    fun `checksum mismatch is reported and fails the transfer`() = runBlocking {
        withTimeout(20_000) {
            val transport = TcpTransport()
            val listener = transport.listen(0).requireSuccess("listen")
            val storage = FakeStorage()
            val payload = patterned(64, seed = 2)
            val manifest = manifestOf(entryOf("file.bin", payload.size.toLong()))
            try {
                val peer = startClient(transport, listener.endpoint.port) { remote ->
                    remote.greet()
                    remote.offer(manifest)
                    remote.expectFrame<FluxFrame.ManifestResponse>()
                    remote.sendBytes(0, payload, digest = ByteArray(32) { 0x5A })
                    val error = remote.expectFrame<FluxFrame.Error>()
                    assertEquals(ProtocolErrorCode.CHECKSUM_MISMATCH, error.code)
                }

                val progress = mutableListOf<TransferProgress>()
                assertFailsWith<FluxShareError.Unexpected> {
                    TransferReceiver(storage)
                        .receive(listener) { TransferDecision.Accept }
                        .collect { progress += it }
                }
                peer.await()

                assertEquals(TransferState.FAILED, progress.last().state)
                assertContentEquals(payload, storage.received("file.bin"))
                assertTrue(storage.allSinksClosed)
            } finally {
                listener.close()
            }
        }
    }

    @Test
    fun `manifest digest mismatch fails even when the file digest matches`() = runBlocking {
        withTimeout(20_000) {
            val transport = TcpTransport()
            val listener = transport.listen(0).requireSuccess("listen")
            val storage = FakeStorage()
            val payload = patterned(32, seed = 4)
            val manifest = manifestOf(
                entryOf(
                    name = "file.bin",
                    size = payload.size.toLong(),
                    sha256 = ByteArray(32) { 0x11 },
                ),
            )
            try {
                val peer = startClient(transport, listener.endpoint.port) { remote ->
                    remote.greet()
                    remote.offer(manifest)
                    remote.expectFrame<FluxFrame.ManifestResponse>()
                    // The per-file digest is correct; only the manifest digest lies.
                    remote.sendBytes(0, payload)
                    val error = remote.expectFrame<FluxFrame.Error>()
                    assertEquals(ProtocolErrorCode.CHECKSUM_MISMATCH, error.code)
                }

                assertFailsWith<FluxShareError.Unexpected> {
                    TransferReceiver(storage)
                        .receive(listener) { TransferDecision.Accept }
                        .toList()
                }
                peer.await()
                assertTrue(storage.allSinksClosed)
            } finally {
                listener.close()
            }
        }
    }

    @Test
    fun `disconnect in the middle of a file fails without completing`() = runBlocking {
        withTimeout(20_000) {
            val transport = TcpTransport()
            val listener = transport.listen(0).requireSuccess("listen")
            val storage = FakeStorage()
            val manifest = manifestOf(entryOf("file.bin", 1000))
            try {
                val peer = startClient(transport, listener.endpoint.port) { remote ->
                    remote.greet()
                    remote.offer(manifest)
                    remote.expectFrame<FluxFrame.ManifestResponse>()
                    remote.stream.write(FluxFrame.Data(0, 0L, patterned(400, seed = 1)))
                    // Return without finishing the file: the session is closed.
                }

                val progress = mutableListOf<TransferProgress>()
                assertFailsWith<FluxShareError> {
                    TransferReceiver(storage)
                        .receive(listener) { TransferDecision.Accept }
                        .collect { progress += it }
                }
                peer.await()

                assertEquals(TransferState.FAILED, progress.last().state)
                assertTrue(progress.none { it.state == TransferState.COMPLETED })
                assertEquals(400, storage.received("file.bin")!!.size)
                assertTrue(storage.allSinksClosed)
            } finally {
                listener.close()
            }
        }
    }

    @Test
    fun `file shorter than declared size fails at its digest`() = runBlocking {
        withTimeout(20_000) {
            val transport = TcpTransport()
            val listener = transport.listen(0).requireSuccess("listen")
            val storage = FakeStorage()
            val manifest = manifestOf(entryOf("file.bin", 1000))
            try {
                val peer = startClient(transport, listener.endpoint.port) { remote ->
                    remote.greet()
                    remote.offer(manifest)
                    remote.expectFrame<FluxFrame.ManifestResponse>()
                    val partial = patterned(400, seed = 6)
                    remote.stream.write(FluxFrame.Data(0, 0L, partial))
                    remote.stream.write(
                        FluxFrame.FileDigest(0, IncrementalSha256.of(partial)),
                    )
                    val error = remote.expectFrame<FluxFrame.Error>()
                    assertEquals(ProtocolErrorCode.TRANSFER_FAILED, error.code)
                }

                assertFailsWith<FluxShareError.ProtocolViolation> {
                    TransferReceiver(storage)
                        .receive(listener) { TransferDecision.Accept }
                        .toList()
                }
                peer.await()
                assertTrue(storage.allSinksClosed)
            } finally {
                listener.close()
            }
        }
    }

    @Test
    fun `non contiguous offsets are rejected`() = runBlocking {
        withTimeout(20_000) {
            val transport = TcpTransport()
            val listener = transport.listen(0).requireSuccess("listen")
            val storage = FakeStorage()
            val manifest = manifestOf(entryOf("file.bin", 100))
            try {
                val peer = startClient(transport, listener.endpoint.port) { remote ->
                    remote.greet()
                    remote.offer(manifest)
                    remote.expectFrame<FluxFrame.ManifestResponse>()
                    remote.stream.write(FluxFrame.Data(0, 10L, patterned(4, seed = 8)))
                    val error = remote.expectFrame<FluxFrame.Error>()
                    assertEquals(ProtocolErrorCode.PROTOCOL_VIOLATION, error.code)
                }

                assertFailsWith<FluxShareError.ProtocolViolation> {
                    TransferReceiver(storage)
                        .receive(listener) { TransferDecision.Accept }
                        .toList()
                }
                peer.await()
                assertTrue(storage.createdSinks.all { it.closed })
            } finally {
                listener.close()
            }
        }
    }

    @Test
    fun `data beyond the declared size is rejected`() = runBlocking {
        withTimeout(20_000) {
            val transport = TcpTransport()
            val listener = transport.listen(0).requireSuccess("listen")
            val storage = FakeStorage()
            val manifest = manifestOf(entryOf("file.bin", 4))
            try {
                val peer = startClient(transport, listener.endpoint.port) { remote ->
                    remote.greet()
                    remote.offer(manifest)
                    remote.expectFrame<FluxFrame.ManifestResponse>()
                    remote.stream.write(FluxFrame.Data(0, 0L, patterned(8, seed = 3)))
                    val error = remote.expectFrame<FluxFrame.Error>()
                    assertEquals(ProtocolErrorCode.TRANSFER_FAILED, error.code)
                }

                assertFailsWith<FluxShareError.ProtocolViolation> {
                    TransferReceiver(storage)
                        .receive(listener) { TransferDecision.Accept }
                        .toList()
                }
                peer.await()
                assertTrue(storage.allSinksClosed)
            } finally {
                listener.close()
            }
        }
    }

    @Test
    fun `malformed frame is rejected with a protocol error`() = runBlocking {
        withTimeout(20_000) {
            val transport = TcpTransport()
            val listener = transport.listen(0).requireSuccess("listen")
            val storage = FakeStorage()
            val manifest = manifestOf(entryOf("file.bin", 10))
            try {
                val peer = startClient(transport, listener.endpoint.port) { remote ->
                    remote.greet()
                    remote.offer(manifest)
                    remote.expectFrame<FluxFrame.ManifestResponse>()
                    // Twelve bytes of junk look like a header with a bad magic.
                    remote.session.write(ByteArray(12) { 0x7F })
                    val error = remote.expectFrame<FluxFrame.Error>()
                    assertEquals(ProtocolErrorCode.PROTOCOL_VIOLATION, error.code)
                }

                assertFailsWith<FluxShareError.ProtocolViolation> {
                    TransferReceiver(storage)
                        .receive(listener) { TransferDecision.Accept }
                        .toList()
                }
                peer.await()
                assertTrue(storage.createdSinks.isEmpty())
            } finally {
                listener.close()
            }
        }
    }

    @Test
    fun `zero length data frame is rejected as a protocol violation`() = runBlocking {
        withTimeout(20_000) {
            val transport = TcpTransport()
            val listener = transport.listen(0).requireSuccess("listen")
            val storage = FakeStorage()
            val manifest = manifestOf(entryOf("file.bin", 10))
            try {
                val peer = startClient(transport, listener.endpoint.port) { remote ->
                    remote.greet()
                    remote.offer(manifest)
                    remote.expectFrame<FluxFrame.ManifestResponse>()
                    remote.session.write(emptyDataFrame())
                    val error = remote.expectFrame<FluxFrame.Error>()
                    assertEquals(ProtocolErrorCode.PROTOCOL_VIOLATION, error.code)
                }

                assertFailsWith<FluxShareError.ProtocolViolation> {
                    TransferReceiver(storage)
                        .receive(listener) { TransferDecision.Accept }
                        .toList()
                }
                peer.await()
                assertTrue(storage.createdSinks.isEmpty())
            } finally {
                listener.close()
            }
        }
    }

    @Test
    fun `storage failure creating a sink reports TRANSFER_FAILED`() = runBlocking {
        withTimeout(20_000) {
            val transport = TcpTransport()
            val listener = transport.listen(0).requireSuccess("listen")
            val storage = FakeStorage().apply { failCreateSink = IOException("no space") }
            val manifest = manifestOf(entryOf("file.bin", 32))
            try {
                val peer = startClient(transport, listener.endpoint.port) { remote ->
                    remote.greet()
                    remote.offer(manifest)
                    remote.expectFrame<FluxFrame.ManifestResponse>()
                    remote.stream.write(FluxFrame.Data(0, 0L, patterned(32, seed = 1)))
                    val error = remote.expectFrame<FluxFrame.Error>()
                    assertEquals(ProtocolErrorCode.TRANSFER_FAILED, error.code)
                }

                val progress = mutableListOf<TransferProgress>()
                assertFailsWith<FluxShareError.StorageUnavailable> {
                    TransferReceiver(storage)
                        .receive(listener) { TransferDecision.Accept }
                        .collect { progress += it }
                }
                peer.await()

                assertEquals(TransferState.FAILED, progress.last().state)
                assertTrue(progress.none { it.state == TransferState.COMPLETED })
            } finally {
                listener.close()
            }
        }
    }

    @Test
    fun `storage failure writing reports TRANSFER_FAILED and closes the sink`() = runBlocking {
        withTimeout(20_000) {
            val transport = TcpTransport()
            val listener = transport.listen(0).requireSuccess("listen")
            val storage = FakeStorage().apply { failWrite = IOException("disk full") }
            val manifest = manifestOf(entryOf("file.bin", 32))
            try {
                val peer = startClient(transport, listener.endpoint.port) { remote ->
                    remote.greet()
                    remote.offer(manifest)
                    remote.expectFrame<FluxFrame.ManifestResponse>()
                    remote.stream.write(FluxFrame.Data(0, 0L, patterned(32, seed = 1)))
                    val error = remote.expectFrame<FluxFrame.Error>()
                    assertEquals(ProtocolErrorCode.TRANSFER_FAILED, error.code)
                }

                val progress = mutableListOf<TransferProgress>()
                assertFailsWith<FluxShareError.StorageUnavailable> {
                    TransferReceiver(storage)
                        .receive(listener) { TransferDecision.Accept }
                        .collect { progress += it }
                }
                peer.await()

                assertEquals(TransferState.FAILED, progress.last().state)
                assertTrue(storage.allSinksClosed, "a failed write must still close the sink")
            } finally {
                listener.close()
            }
        }
    }

    @Test
    fun `times out waiting for the hello`() = runBlocking {
        withTimeout(20_000) {
            val transport = TcpTransport()
            val listener = transport.listen(0).requireSuccess("listen")
            val storage = FakeStorage()
            try {
                val peer = startClient(transport, listener.endpoint.port) { remote ->
                    // Connect and stay silent.
                    remote.drain()
                }

                assertFailsWith<FluxShareError.PeerUnavailable> {
                    TransferReceiver(storage, handshakeTimeoutMillis = 200)
                        .receive(listener) { TransferDecision.Accept }
                        .toList()
                }
                peer.await()
                assertTrue(storage.createdSinks.isEmpty())
            } finally {
                listener.close()
            }
        }
    }

    @Test
    fun `times out when the data phase goes idle`() = runBlocking {
        withTimeout(20_000) {
            val transport = TcpTransport()
            val listener = transport.listen(0).requireSuccess("listen")
            val storage = FakeStorage()
            val manifest = manifestOf(entryOf("file.bin", 64))
            try {
                val peer = startClient(transport, listener.endpoint.port) { remote ->
                    remote.greet()
                    remote.offer(manifest)
                    remote.expectFrame<FluxFrame.ManifestResponse>()
                    // Keep the session open without sending any file content.
                    remote.drain()
                }

                val progress = mutableListOf<TransferProgress>()
                assertFailsWith<FluxShareError.PeerUnavailable> {
                    TransferReceiver(storage, dataIdleTimeoutMillis = 200)
                        .receive(listener) { TransferDecision.Accept }
                        .collect { progress += it }
                }
                peer.await()

                assertEquals(TransferState.FAILED, progress.last().state)
                assertTrue(progress.none { it.state == TransferState.COMPLETED })
            } finally {
                listener.close()
            }
        }
    }

    @Test
    fun `approval time is not counted against the data idle timeout`() = runBlocking {
        withTimeout(20_000) {
            val transport = TcpTransport()
            val listener = transport.listen(0).requireSuccess("listen")
            val storage = FakeStorage()
            val payload = patterned(512, seed = 7)
            val manifest = manifestOf(entryOf("file.bin", payload.size.toLong()))
            try {
                val peer = startClient(transport, listener.endpoint.port) { remote ->
                    remote.greet()
                    remote.offer(manifest)
                    remote.expectFrame<FluxFrame.ManifestResponse>()
                    remote.sendBytes(0, payload)
                    remote.expectFrame<FluxFrame.TransferComplete>()
                }

                val progress = async(Dispatchers.IO) {
                    TransferReceiver(storage, dataIdleTimeoutMillis = 200)
                        .receive(listener) {
                            delay(500)
                            TransferDecision.Accept
                        }
                        .toList()
                }.await()
                peer.await()

                assertEquals(TransferState.COMPLETED, progress.last().state)
                assertContentEquals(payload, storage.received("file.bin"))
            } finally {
                listener.close()
            }
        }
    }

    @Test
    fun `cancelling the receiver closes the sink and the session`() = runBlocking {
        withTimeout(20_000) {
            val transport = TcpTransport()
            val listener = transport.listen(0).requireSuccess("listen")
            val storage = FakeStorage()
            val payload = patterned(64 * 1024, seed = 4)
            val manifest = manifestOf(entryOf("payload.bin", payload.size.toLong()))
            try {
                val peer = startClient(transport, listener.endpoint.port) { remote ->
                    remote.greet()
                    remote.offer(manifest)
                    remote.expectFrame<FluxFrame.ManifestResponse>()
                    remote.stream.write(FluxFrame.Data(0, 0L, payload.copyOfRange(0, 1024)))
                    assertNull(
                        remote.stream.read(),
                        "cancelling the receiver must close the session",
                    )
                }

                val progress = mutableListOf<TransferProgress>()
                val sawData = CompletableDeferred<Unit>()
                val collector = launch(Dispatchers.IO) {
                    TransferReceiver(storage)
                        .receive(listener) { TransferDecision.Accept }
                        .collect { sample ->
                            progress += sample
                            if (sample.state == TransferState.TRANSFERRING &&
                                sample.bytesTransferred > 0
                            ) {
                                sawData.complete(Unit)
                            }
                        }
                }

                withTimeout(10_000) { sawData.await() }
                collector.cancelAndJoin()
                withTimeout(5_000) {
                    while (!storage.allSinksClosed) delay(10)
                }
                peer.await()

                assertTrue(progress.none { it.state == TransferState.COMPLETED })
                assertEquals(1024, storage.received("payload.bin")!!.size)
            } finally {
                listener.close()
            }
        }
    }
}
