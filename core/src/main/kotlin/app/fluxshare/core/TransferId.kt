package app.fluxshare.core

import java.util.UUID

/** Identifier of a single transfer, unique within a FluxShare session. */
@JvmInline
value class TransferId(val value: String) {
    init {
        require(value.isNotBlank()) { "TransferId must not be blank" }
        require(value.length <= 128) { "TransferId must not exceed 128 characters" }
    }

    companion object {
        fun new(): TransferId = TransferId(UUID.randomUUID().toString())
    }
}
