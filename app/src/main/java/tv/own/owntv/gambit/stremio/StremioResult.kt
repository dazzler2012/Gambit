package tv.own.owntv.gambit.stremio

/**
 * The outcome of one call to Stremio's link or account service. Calls never throw (coroutine
 * cancellation aside); every failure is a [StremioError].
 */
sealed interface StremioResult<out T> {
    data class Ok<out T>(val value: T) : StremioResult<T>
    data class Err(val error: StremioError) : StremioResult<Nothing>
}

/**
 * Why a Stremio call failed. No error keeps a response body, an exception message or a request URL,
 * since any of those could echo the session key or an addon address. The screen maps each type to a
 * `gambit_strings.xml` entry; nothing here is display text.
 */
sealed interface StremioError {
    /** No answer within the client's time limit. */
    data object Timeout : StremioError

    /** Connection failed: DNS, refused, TLS, reset. [kind] is the exception class name only. */
    data class Network(val kind: String) : StremioError

    /** The service answered with a status other than 2xx. */
    data class HttpStatus(val code: Int) : StremioError

    /** The service answered `{"error": {...}}`. [code] is Stremio's error number (0 when absent). */
    data class Api(val code: Int) : StremioError

    /** The body isn't JSON, or isn't the expected shape. [reason] is a fixed text from this package. */
    data class Malformed(val reason: String) : StremioError
}
