package tv.own.owntv.gambit.ui.about

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import tv.own.owntv.BuildConfig
import tv.own.owntv.R
import tv.own.owntv.core.i18n.SupportedLocales
import tv.own.owntv.gambit.ui.theme.GambitColors
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageTool
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.mpxSp
import tv.own.owntv.ui.theme.stageText

/**
 * More → About in Gambit, in place of OwnTV's AboutPage: the name and version, what the app is, one
 * line crediting the GPL-3.0 projects it is built on, the licence notice, and the Language button.
 * No OwnTV logo, wordmark, Telegram card or GitHub link, and no Check for updates: OwnTV's updater
 * looks at OwnTV's own releases, which are not Gambit's. The full credits are in CREDITS.md.
 */
@Composable
internal fun GambitAboutPage(entry: FocusRequester, onOpenLanguage: () -> Unit) {
    Column(Modifier.fillMaxSize().focusGroup().widthIn(max = 900.mpx)) {
        Text(stringResource(R.string.app_name), style = stageText(46, 800), color = GambitColors.Text, maxLines = 1)
        Text(
            stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
            style = stageText(22, 700), color = GambitColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.mpx),
        )
        Text(
            stringResource(R.string.more_about_description), style = stageText(18, 500).copy(lineHeight = 29.mpxSp), color = GambitColors.Muted,
            modifier = Modifier.padding(top = 26.mpx),
        )
        Text(
            stringResource(R.string.gambit_about_credits), style = stageText(18, 500).copy(lineHeight = 29.mpxSp), color = GambitColors.Muted,
            modifier = Modifier.padding(top = 14.mpx),
        )
        Text(stringResource(R.string.more_about_license), style = stageText(16, 500), color = GambitColors.Dim, modifier = Modifier.padding(top = 14.mpx))
        Row(Modifier.padding(top = 30.mpx)) {
            val languages = SupportedLocales.all.count { it.packaged }
            StageTool(pluralStringResource(R.plurals.more_about_languages, languages, languages), onClick = onOpenLanguage, icon = OwnTVIcon.LIST, boxed = true, modifier = Modifier.focusRequester(entry))
        }
    }
}
