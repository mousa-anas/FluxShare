package app.fluxshare.core

/** Result of an operation that can fail with a [FluxShareError]. */
sealed interface FluxShareResult<out T> {
    data class Success<T>(val value: T) : FluxShareResult<T>

    data class Failure(val error: FluxShareError) : FluxShareResult<Nothing>
}
