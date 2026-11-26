package app.fluxshare.transport

/**
 * Address of a remote FluxShare endpoint on the local network.
 *
 * This is deliberately provisional: each [Transport] implementation is free to
 * interpret the address, so adding a Windows-compatible transport later does
 * not change this contract.
 */
data class TransportEndpoint(
    val host: String,
    val port: Int,
) {
    init {
        require(host.isNotBlank()) { "Endpoint host must not be blank" }
        require(port in 0..65535) { "Endpoint port must be in 0..65535, was $port" }
    }
}
