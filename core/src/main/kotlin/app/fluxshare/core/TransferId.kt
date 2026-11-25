package app.fluxshare.core

import java.util.UUID

/** Identifier of a single transfer, unique within a FluxShare session. */
@JvmInline
value class TransferId(val value: String) {
    companion object {
        fun new(): TransferId = TransferId(UUID.randomUUID().toString())
    }
}
