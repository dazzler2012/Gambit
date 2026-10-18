package tv.own.owntv.gambit.addons.net

/**
 * The outcome of one addon call. Calls never throw (except coroutine cancellation); every failure is
 * an [AddonError].
 */
sealed interface AddonResult<out T> {
    data class Ok<out T>(val value: T) : AddonResult<T>
    data class Err(val error: AddonError) : AddonResult<Nothing>

    fun valueOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): AddonError? = (this as? Err)?.error
}

inline fun <T, R> AddonResult<T>.map(transform: (T) -> R): AddonResult<R> = when (this) {
    is AddonResult.Ok -> AddonResult.Ok(transform(value))
    is AddonResult.Err -> this
}

/**
 * Why an addon call failed. [addon] is a redacted label (addon name and/or host), never the URL, and
 * no error keeps an exception or response text, since either can echo the URL or a stream link.
 */
sealed interface AddonError {
    val addon: String

    /** The text the user typed or scanned isn't an http(s) or stremio:// addon URL. */
    data object InvalidUrl : AddonError { override val addon: String = "" }

    /** No response within the client's time limit. */
    data class Timeout(override val addon: String) : AddonError

    /** Connection failed: DNS, refused, TLS, reset. [kind] is the exception class name only. */
    data class Network(override val addon: String, val kind: String) : AddonError

    /** The addon answered with a status other than 2xx. */
    data class HttpStatus(override val addon: String, val code: Int) : AddonError

    /** The body isn't JSON, or isn't the shape the call expects. */
    data class MalformedJson(override val addon: String) : AddonError

    /** The JSON is valid but a required field is missing or empty. */
    data class MissingField(override val addon: String, val field: String) : AddonError

    /** The addon answered but has nothing for this id (e.g. `{"meta": null}`). */
    data class NotFound(override val addon: String) : AddonError

    /** No enabled addon serves this resource, type and id. */
    data class NoAddon(val resource: String, val type: String) : AddonError { override val addon: String = "" }
}
