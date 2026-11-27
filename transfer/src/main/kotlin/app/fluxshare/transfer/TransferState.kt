package app.fluxshare.transfer

/** Lifecycle of a single transfer. */
enum class TransferState {
    QUEUED,
    CONNECTING,

    /** The manifest has been offered and the remote device has not decided yet. */
    AWAITING_APPROVAL,
    TRANSFERRING,
    COMPLETED,
    FAILED,
    CANCELLED,
}
