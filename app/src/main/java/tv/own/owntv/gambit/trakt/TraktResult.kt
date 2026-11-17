package tv.own.owntv.gambit.trakt

/**
 * What every public Trakt call returns. Calls never throw (coroutine cancellation aside): a failure
 * comes back as [Failure] carrying a [TraktError].
 */
sealed interface TraktResult<out T> {
    data class Success<out T>(val value: T) : TraktResult<T>

    data class Failure(val error: TraktError) : TraktResult<Nothing>
}

inline fun <T, R> TraktResult<T>.map(transform: (T) -> R): TraktResult<R> = when (this) {
    is TraktResult.Success -> TraktResult.Success(transform(value))
    is TraktResult.Failure -> this
}

inline fun <T, R> TraktResult<T>.flatMap(transform: (T) -> TraktResult<R>): TraktResult<R> = when (this) {
    is TraktResult.Success -> transform(value)
    is TraktResult.Failure -> this
}

fun <T> TraktResult<T>.getOrNull(): T? = (this as? TraktResult.Success)?.value

fun TraktResult<*>.errorOrNull(): TraktError? = (this as? TraktResult.Failure)?.error
