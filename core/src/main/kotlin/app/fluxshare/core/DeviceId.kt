package app.fluxshare.core

/**
 * Stable identifier of a FluxShare device.
 *
 * Device identifiers are assigned once per installation and must not contain
 * personal information.
 */
@JvmInline
value class DeviceId(val value: String) {
    init {
        require(value.isNotBlank()) { "DeviceId must not be blank" }
    }
}
