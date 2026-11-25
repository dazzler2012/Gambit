package tv.own.owntv.gambit.ui.trakt

import androidx.annotation.StringRes
import tv.own.owntv.R
import tv.own.owntv.gambit.trakt.TraktError

/**
 * The words for each way a Trakt call can fail. Every [TraktError] type is listed, so adding a new one
 * does not compile until it has a text here. [TraktError.description] is for logs and is never shown.
 */
@StringRes
internal fun TraktError.messageRes(): Int = when (this) {
    TraktError.CodeExpired -> R.string.gambit_trakt_err_expired
    TraktError.CodeDenied -> R.string.gambit_trakt_err_denied
    TraktError.CodeAlreadyUsed -> R.string.gambit_trakt_err_used
    TraktError.InvalidCode -> R.string.gambit_trakt_err_invalid
    TraktError.Timeout, is TraktError.Network -> R.string.gambit_trakt_err_network
    is TraktError.RateLimited, is TraktError.Server -> R.string.gambit_trakt_err_busy
    TraktError.Unauthorized, TraktError.Forbidden -> R.string.gambit_trakt_err_refused
    is TraktError.Storage -> R.string.gambit_trakt_err_storage
    TraktError.AccountLimit -> R.string.gambit_trakt_err_account_limit
    TraktError.SignInExpired, TraktError.NotSignedIn -> R.string.gambit_trakt_err_expired_signin
    TraktError.NotFound, is TraktError.Conflict, TraktError.Unprocessable, is TraktError.Http, is TraktError.Malformed ->
        R.string.gambit_trakt_err_other
}
