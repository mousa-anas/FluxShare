package app.fluxshare.protocol

import app.fluxshare.core.DeviceId
import app.fluxshare.core.DevicePlatform
import app.fluxshare.core.FluxShareResult
import app.fluxshare.core.PeerDevice
import app.fluxshare.core.TransferId
import app.fluxshare.transport.TransportEndpoint
import app.fluxshare.transport.tcp.TcpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Exercises the real codec over a real loopback TCP session, proving the
 * transport and protocol layers compose without an in-memory hand-off.
 */
class FluxProtocolTcpIntegrationTest {

    @Test
    fun `exchanges hello manifest and file bytes over tcp`() = runBlocking {
        withTimeout(20_000) {
            val transport = TcpTransport()
            val listener = transport.listen(0).requireSuccess()
            try {
                val serverDeferred = async(Dispatchers.IO) {
                    val session = listener.accept().requireSuccess()
                    try {
                        val stream = FluxFrameStream(session)
                        val hello = stream.read() as FluxFrame.Hello
                        val manifest = (stream.read() as FluxFrame.Manifest).manifest
                        val data = stream.read() as FluxFrame.Data

                        stream.write(
                            FluxFrame.ManifestResponse(manifest.transferId, accepted = true),
                        )
                        stream.write(
                            FluxFrame.FileDigest(data.fileIndex, IncrementalSha256.of(data.bytes)),
                        )

                        hello.peer.displayName to manifest.files.size
                    } finally {
                        session.close()
                    }
                }

                val client = transport.connect(
                    TransportEndpoint("127.0.0.1", listener.endpoint.port),
                ).requireSuccess()
                try {
                    val stream = FluxFrameStream(client)
                    val content = "fluxshare phase 1".toByteArray()

                    stream.write(
                        FluxFrame.Hello(
                            PeerDevice(DeviceId("sender"), "Sender", DevicePlatform.ANDROID),
                        ),
                    )
                    stream.write(
                        FluxFrame.Manifest(
                            FluxManifest(
                                transferId = TransferId("transfer-1"),
                                files = listOf(
                                    ManifestEntry(
                                        name = "hello.txt",
                                        sizeBytes = content.size.toLong(),
                                        mimeType = "text/plain",
                                    ),
                                ),
                            ),
                        ),
                    )
                    stream.write(FluxFrame.Data(fileIndex = 0, offset = 0L, bytes = content))

                    val response = stream.read() as FluxFrame.ManifestResponse
                    val digest = stream.read() as FluxFrame.FileDigest

                    assertEquals(true, response.accepted)
                    assertEquals(0, digest.fileIndex)
                    assertContentEquals(IncrementalSha256.of(content), digest.sha256)
                } finally {
                    client.close()
                }

                val (displayName, fileCount) = serverDeferred.await()
                assertEquals("Sender", displayName)
                assertEquals(1, fileCount)
            } finally {
                listener.close()
            }
        }
    }

    private fun <T> FluxShareResult<T>.requireSuccess(): T = when (this) {
        is FluxShareResult.Success -> value
        is FluxShareResult.Failure -> fail("Expected success but got $error")
    }
}
