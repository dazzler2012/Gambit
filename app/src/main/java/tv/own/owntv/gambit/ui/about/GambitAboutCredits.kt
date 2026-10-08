package tv.own.owntv.gambit.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.gambit.ui.theme.GambitColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/**
 * Gambit's credits on More → About, under OwnTV's own text: what Gambit is and the GPL-3.0 projects it
 * is built on. Read-only — it takes no focus. The full list is in CREDITS.md.
 */
@Composable
internal fun GambitAboutCredits(modifier: Modifier = Modifier) {
    Column(modifier.widthIn(max = 860.mpx)) {
        Text(stringResource(R.string.gambit_about_title), style = stageText(18, 700), color = GambitColors.Text)
        Text(
            stringResource(R.string.gambit_about_credits), style = stageText(16, 500), color = GambitColors.Muted,
            modifier = Modifier.padding(top = 4.mpx),
        )
    }
}
