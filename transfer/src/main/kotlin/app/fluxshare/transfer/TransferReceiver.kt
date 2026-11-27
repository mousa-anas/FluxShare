package app.fluxshare.transfer

import app.fluxshare.core.FluxShareError
import app.fluxshare.core.FluxShareResult
import app.fluxshare.protocol.FluxFrame
import app.fluxshare.protocol.FluxFrameStream
import app.fluxshare.protocol.FluxManifest
import app.fluxshare.protocol.IncrementalSha256
import app.fluxshare.protocol.ManifestEntry
import app.fluxshare.protocol.ProtocolErrorCode
import app.fluxshare.storage.FileSink
import app.fluxshare.storage.StorageProvider
import app.fluxshare.storage.TransferFile
import app.fluxshare.transport.Transport
import app.fluxshare.transport.TransportListener
import app.fluxshare.transport.TransportSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Receives files from one sending FluxShare device.
 *
 * The frame sequence is validated before anything is written: the sender's
 * `HELLO` and `MANIFEST` are read, the manifest is validated by the protocol
 * codec, and only then is [onApproval] asked. File content is read only after
 * the transfer is accepted, so a rejected offer never touches storage.
 *
 * Every file is streamed into a [FileSink] from the [StorageProvider] while its
 * SHA-256 digest is computed, and the digest is compared with the sender's
 * `FILE_DIGEST` (and the manifest digest when the sender supplied one). A
 * mismatch sends `ERROR(CHECKSUM_MISMATCH)` and fails the transfer.
 *
 * Cancelling the collecting coroutine closes the session, which unblocks a
 * pending read, and closes the open sink. Partial files are not deleted because
 * [StorageProvider] has no delete operation.
 *
 * **Security:** the protocol and the TCP transport are neither encrypted nor
 * authenticated. Do not use them on untrusted networks.
 */
class TransferReceiver(
    private val transport: Transport,
    private val storage: StorageProvider,
) {
    /**
     * Listens on [port] (`0` for an ephemeral port) and receives one transfer.
     *
     * The listener this flow creates is closed when the transfer ends.
     */
    fun receive(
        port: Int = 0,
        onApproval: suspend (IncomingTransfer) -> TransferDecision,
    ): Flow<TransferProgress> = receiveFlow(
        onApproval = onApproval,
        suppliedListener = null,
        openListener = { transport.listen(port) },
        ownsListener = true,
    )

    /**
     * Receives one transfer over an already listening [listener].
     *
     * The caller keeps ownership of [listener] and must close it; the accepted
     * session is owned and closed by this flow.
     */
    fun receive(
        listener: TransportListener,
        onApproval: suspend (IncomingTransfer) -> TransferDecision,
    ): Flow<TransferProgress> = receiveFlow(
        onApproval = onApproval,
        suppliedListener = listener,
        openListener = null,
        ownsListener = false,
    )

    private fun receiveFlow(
        onApproval: suspend (IncomingTransfer) -> TransferDecision,
        suppliedListener: TransportListener?,
        openListener: (suspend () -> FluxShareResult<TransportListener>)?,
        ownsListener: Boolean,
    ): Flow<TransferProgress> = channelFlow {
        val out = channel
        val sessionRef = AtomicReference<TransportSession?>(null)
        val sinkRef = AtomicReference<FileSink?>(null)
        var listenerRef: TransportListener? = null
        var reporter: TransferProgressReporter? = null
        var failure: FluxShareError? = null

        val job = launch(start = CoroutineStart.LAZY) {
            var stream: FluxFrameStream? = null
            try {
                val listener = suppliedListener ?: openListener!!.invoke().orThrow()
                listenerRef = listener

                val session = listener.accept().orThrow()
                sessionRef.set(session)
                stream = FluxFrameStream(session)

                val hello = stream.readExpecting<FluxFrame.Hello>("a hello frame")
                val manifest = stream.readExpecting<FluxFrame.Manifest>("a manifest frame").manifest

                val progress = TransferProgressReporter(
                    out = out,
                    transferId = manifest.transferId,
                    totalBytes = manifest.totalBytes,
                )
                reporter = progress
                // The local transfer id is only known once the manifest arrived.
                progress.emit(TransferState.CONNECTING)
                progress.emit(TransferState.AWAITING_APPROVAL)

                val decision = onApproval(
                    IncomingTransfer(
                        peer = hello.peer,
                        transferId = manifest.transferId,
                        files = manifest.files,
                    ),
                )
                when (decision) {
                    is TransferDecision.Accept -> {
                        stream.write(
                            FluxFrame.ManifestResponse(manifest.transferId, accepted = true),
                        )
                        progress.emit(TransferState.TRANSFERRING)
                        receiveFiles(stream, manifest, progress, sinkRef)
                        progress.emit(TransferState.COMPLETED)
                    }

                    is TransferDecision.Reject -> {
                        stream.write(
                            FluxFrame.ManifestResponse(
                                transferId = manifest.transferId,
                                accepted = false,
                                reason = decision.reason.toWireText(),
                            ),
                        )
                        throw ReceiverFailure(FluxShareError.TransferRejected, reply = null)
                    }
                }
            } catch (e: CancellationException) {
                reporter?.emitTerminal(TransferState.CANCELLED)
            } catch (e: ReceiverFailure) {
                replyWithError(sessionRef.get(), e.reply, e.error.message)
                failure = e.error
                reporter?.emit(TransferState.FAILED)
            } catch (e: FluxShareError) {
                // Malformed or out-of-sequence frames are reported to the peer
                // when the session still allows writing.
                replyWithError(sessionRef.get(), ProtocolErrorCode.PROTOCOL_VIOLATION, e.message)
                failure = e
                reporter?.emit(TransferState.FAILED)
            } finally {
                withContext(NonCancellable) {
                    sinkRef.get()?.let { sink -> runCatching { sink.close() } }
                    sinkRef.set(null)
                    sessionRef.get()?.let { session -> runCatching { session.close() } }
                    if (ownsListener) {
                        listenerRef?.let { listener -> runCatching { listener.close() } }
                    }
                }
            }
        }

        try {
            job.start()
            try {
                job.join()
            } finally {
                // Closing the session here unblocks a pending read before the
                // worker is awaited; blocking socket I/O ignores cancellation alone.
                withContext(NonCancellable) {
                    sessionRef.get()?.let { session -> runCatching { session.close() } }
                    job.cancelAndJoin()
                }
            }
        } finally {
            if (ownsListener) {
                withContext(NonCancellable) {
                    listenerRef?.let { listener -> runCatching { listener.close() } }
                }
            }
        }

        failure?.let { throw it }
    }

    private suspend fun receiveFiles(
        stream: FluxFrameStream,
        manifest: FluxManifest,
        reporter: TransferProgressReporter,
        sinkRef: AtomicReference<FileSink?>,
    ) {
        var index = 0
        var offset = 0L
        var hasher = IncrementalSha256()
        var sink: FileSink? = null

        suspend fun openSink(entry: ManifestEntry): FileSink {
            // `handle` is only a hint for the provider: on receive there is no
            // platform reference yet, so the validated name is used.
            val file = TransferFile(
                handle = entry.name,
                name = entry.name,
                sizeBytes = entry.sizeBytes,
                mimeType = entry.mimeType,
            )
            val created = when (val opened = storage.createSink(file)) {
                is FluxShareResult.Success -> opened.value
                is FluxShareResult.Failure ->
                    throw ReceiverFailure(opened.error, ProtocolErrorCode.TRANSFER_FAILED)
            }
            sink = created
            sinkRef.set(created)
            return created
        }

        while (index < manifest.files.size) {
            val entry = manifest.files[index]
            val frame = stream.read() ?: throw ReceiverFailure(
                FluxShareError.Unexpected(
                    "The sender disconnected after $index of ${manifest.files.size} files.",
                ),
                reply = null,
            )

            when (frame) {
                is FluxFrame.Data -> {
                    if (frame.fileIndex != index) {
                        throw ReceiverFailure(
                            FluxShareError.ProtocolViolation(
                                "Data for file ${frame.fileIndex} arrived while file $index was expected.",
                            ),
                            ProtocolErrorCode.PROTOCOL_VIOLATION,
                        )
                    }
                    if (frame.offset != offset) {
                        throw ReceiverFailure(
                            FluxShareError.ProtocolViolation(
                                "File \"${entry.name}\" chunk starts at ${frame.offset} instead of $offset.",
                            ),
                            ProtocolErrorCode.PROTOCOL_VIOLATION,
                        )
                    }
                    if (frame.bytes.size.toLong() > entry.sizeBytes - offset) {
                        throw ReceiverFailure(
                            FluxShareError.ProtocolViolation(
                                "File \"${entry.name}\" exceeds its declared size of ${entry.sizeBytes} bytes.",
                            ),
                            ProtocolErrorCode.TRANSFER_FAILED,
                        )
                    }

                    val target = sink ?: openSink(entry)
                    target.write(frame.bytes)
                    hasher.update(frame.bytes)
                    offset += frame.bytes.size
                    reporter.advance(frame.bytes.size.toLong())
                }

                is FluxFrame.FileDigest -> {
                    if (frame.fileIndex != index) {
                        throw ReceiverFailure(
                            FluxShareError.ProtocolViolation(
                                "Digest for file ${frame.fileIndex} arrived while file $index was expected.",
                            ),
                            ProtocolErrorCode.PROTOCOL_VIOLATION,
                        )
                    }
                    // A zero-byte file has no data frame, but it still becomes a file.
                    val target = sink ?: openSink(entry)

                    if (offset != entry.sizeBytes) {
                        throw ReceiverFailure(
                            FluxShareError.ProtocolViolation(
                                "File \"${entry.name}\" ended after $offset of ${entry.sizeBytes} bytes.",
                            ),
                            ProtocolErrorCode.TRANSFER_FAILED,
                        )
                    }

                    val digest = hasher.digest()
                    if (!digest.contentEquals(frame.sha256)) {
                        throw ReceiverFailure(
                            FluxShareError.Unexpected("Checksum mismatch for file \"${entry.name}\"."),
                            ProtocolErrorCode.CHECKSUM_MISMATCH,
                        )
                    }
                    entry.sha256?.let { expected ->
                        if (!expected.contentEquals(digest)) {
                            throw ReceiverFailure(
                                FluxShareError.Unexpected(
                                    "File \"${entry.name}\" does not match the manifest digest.",
                                ),
                                ProtocolErrorCode.CHECKSUM_MISMATCH,
                            )
                        }
                    }

                    target.close()
                    sink = null
                    sinkRef.set(null)
                    index++
                    offset = 0L
                    hasher = IncrementalSha256()
                }

                is FluxFrame.Error -> throw ReceiverFailure(frame.toTransferError(), reply = null)

                else -> throw ReceiverFailure(
                    FluxShareError.ProtocolViolation(
                        "The sender sent ${frameName(frame)} while file content was expected.",
                    ),
                    ProtocolErrorCode.PROTOCOL_VIOLATION,
                )
            }
        }
    }

    private suspend fun replyWithError(
        session: TransportSession?,
        code: ProtocolErrorCode?,
        message: String?,
    ) {
        if (code == null || session == null || !session.isOpen) return
        FluxFrameStream(session).writeErrorQuietly(code, message)
    }
}
