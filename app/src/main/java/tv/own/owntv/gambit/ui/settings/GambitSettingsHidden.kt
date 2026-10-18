package tv.own.owntv.gambit.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R

/*
 * Settings rows Gambit does not show. OwnTV's rows stay defined as they are; Settings filters these out of
 * its root list and its search results.
 *  - "Check for updates" and "Check for updates on startup": they look for OwnTV's releases, not Gambit's.
 *  - "App icon": Gambit has one icon.
 *  - Quick's "Check for updates on startup" toggle: the same setting, and Gambit never checks at startup.
 */

/** Root row keys (SettingsScreen's RootRow.key). */
val GambitHiddenSettingsKeys: Set<String> = setOf("app_icon", "check_updates", "update_startup", "quick_check_update")

/** The same rows' titles, as Settings search lists them. */
@Composable
fun gambitHiddenSettingsTitles(): Set<String> = setOf(
    stringResource(R.string.settings_app_icon),
    stringResource(R.string.settings_check_updates),
    stringResource(R.string.settings_update_startup),
)
