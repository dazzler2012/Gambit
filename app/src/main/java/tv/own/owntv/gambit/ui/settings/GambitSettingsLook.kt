package tv.own.owntv.gambit.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.features.settings.SettingValue
import tv.own.owntv.gambit.ui.theme.GambitColors
import tv.own.owntv.gambit.ui.theme.gambitFocusFill
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/*
 * Settings in Gambit's look, drawn for OwnTV's settings pages (which keep their layout: rows on the left,
 * the explanation panel on the right, and every key, pin and step as they are).
 *  - Rows: no icon; the title in white; the value on a second line in grey; on/off on a switch at the right.
 *  - Headings: as written (sentence case), in the light green accent, not grey capitals.
 *  - Switch: a light green thumb on a dark green track when on; grey on grey when off.
 */

/**
 * A settings row's content. The second line is the row's value ("Gambit player", "85%", the chosen
 * segment); a row with none shows its description instead — but not while focused, when the panel beside
 * it already says it.
 */
@Composable
fun GambitSettingRowContent(
    title: String,
    desc: String?,
    value: SettingValue?,
    pinned: Boolean,
    enabled: Boolean,
    focused: Boolean,
) {
    val accent = stageAccent.accent
    val second = valueText(value) ?: desc?.takeIf { !focused && it.isNotBlank() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 26.mpx),
        horizontalArrangement = Arrangement.spacedBy(18.mpx),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.mpx), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, style = stageText(22, 600), color = if (enabled) Color.White else GambitColors.Dim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (pinned) Box(Modifier.size(7.mpx).background(accent, CircleShape))
            }
            if (second != null) {
                Text(
                    second, style = stageText(17, 400),
                    color = if (focused) Color.White.copy(alpha = 0.8f) else GambitColors.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.mpx),
                )
            }
        }
        when (value) {
            is SettingValue.Switch -> GambitSwitch(value.on)
            is SettingValue.Opens -> OwnTVIcon(OwnTVIcon.CHEVRON, if (focused) Color.White else GambitColors.Muted, Modifier.size(20.mpx))
            is SettingValue.Choice -> OwnTVIcon(OwnTVIcon.CHEVRON_DOWN, if (focused) Color.White else GambitColors.Muted, Modifier.size(20.mpx))
            is SettingValue.Custom -> value.content()
            else -> Unit
        }
    }
}

/** What a value says, for the second line; null for a switch, a custom value or an empty "opens". */
@Composable
private fun valueText(value: SettingValue?): String? = when (value) {
    null, is SettingValue.Switch, is SettingValue.Custom -> null
    is SettingValue.Choice -> value.text
    is SettingValue.Opens -> value.text?.takeIf { it.isNotBlank() }
    is SettingValue.Stepper -> value.text
    is SettingValue.Segmented -> value.options.getOrNull(value.selected)
    is SettingValue.Saved -> if (value.any) stringResource(R.string.gambit_settings_saved_reset, value.text, stringResource(R.string.common_reset)) else value.text
    is SettingValue.Action -> value.text
}

/** A group's heading, as written, in the light green accent; the row count after it in grey. */
@Composable
fun GambitSettingsHeading(text: String, count: Int?, first: Boolean) {
    Row(
        Modifier.padding(start = 26.mpx, top = if (first) 4.mpx else 22.mpx, bottom = 10.mpx),
        horizontalArrangement = Arrangement.spacedBy(10.mpx),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = stageText(20, 600), color = stageAccent.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null) Text(count.toString(), style = stageText(17, 500), color = GambitColors.Dim, maxLines = 1)
    }
}

/** On: a light green thumb on a dark green track. Off: a grey thumb on a grey track. */
@Composable
fun GambitSwitch(on: Boolean) {
    val accent = stageAccent.accent
    Box(
        Modifier.size(56.mpx, 32.mpx)
            .background(if (on) gambitFocusFill(accent) else Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.mpx)),
    ) {
        Box(
            Modifier.offset(x = if (on) 28.mpx else 4.mpx, y = 4.mpx).size(24.mpx)
                .background(if (on) accent else GambitColors.Dim, CircleShape),
        )
    }
}

/** Every long-press menu's panel (OwnTV's StageMenu): flat near-black, in place of the glass. */
fun gambitMenuPanel(): Modifier = Modifier.background(GambitColors.Rail, RoundedCornerShape(18.mpx))
