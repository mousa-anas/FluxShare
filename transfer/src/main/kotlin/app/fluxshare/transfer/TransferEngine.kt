package app.fluxshare.transfer

import app.fluxshare.core.TransferId
import kotlinx.coroutines.flow.Flow

/**
 * Orchestrates transfers between FluxShare devices.
 *
 * Implementations combine a [app.fluxshare.transport.Transport], a
 * [app.fluxshare.security.SessionSecurity] and a
 * [app.fluxshare.storage.StorageProvider]. They must stay independent of the
 * Android UI so the same orchestration can serve other platforms later.
 *
 * No implementation exists yet; the contracts are defined first so transport,
 * security and storage work can proceed independently.
 */
interface TransferEngine {
    /** Starts [request] and emits progress until it completes, fails or is cancelled. */
    fun execute(request: TransferRequest): Flow<TransferProgress>

    /** Requests cancellation of a running transfer. */
    suspend fun cancel(transferId: TransferId)
}
