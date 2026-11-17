package tv.own.owntv.gambit.ui.addons

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R
import tv.own.owntv.gambit.addons.net.AddonSource

/**
 * How the screen names an addon: "Name (host)", or the host alone before the manifest is known.
 * Only the host of the address is ever shown, never its path or query (they can hold debrid keys).
 */
@Composable
internal fun AddonSource.displayLabel(): String =
    if (name.isNullOrBlank()) host else stringResource(R.string.gambit_addons_label, name, host)
