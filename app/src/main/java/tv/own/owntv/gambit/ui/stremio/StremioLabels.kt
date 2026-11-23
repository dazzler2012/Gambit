package tv.own.owntv.gambit.ui.stremio

import androidx.annotation.StringRes
import tv.own.owntv.R
import tv.own.owntv.gambit.stremio.StremioError

/**
 * The words for each way a Stremio call can fail. Every [StremioError] type is listed, so adding a new
 * one does not compile until it has a text here.
 */
@StringRes
internal fun StremioError.messageRes(): Int = when (this) {
    StremioError.Timeout, is StremioError.Network -> R.string.gambit_stremio_err_network
    is StremioError.HttpStatus -> if (code >= 500) R.string.gambit_stremio_err_busy else R.string.gambit_stremio_err_other
    is StremioError.Api -> R.string.gambit_stremio_err_refused
    is StremioError.Malformed -> R.string.gambit_stremio_err_other
}
