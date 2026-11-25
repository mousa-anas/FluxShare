package app.fluxshare.transfer

/** Lifecycle of a single transfer. */
enum class TransferState {
    QUEUED,
    CONNECTING,
    TRANSFERRING,
    COMPLETED,
    FAILED,
    CANCELLED,
}
