package tv.own.owntv.gambit.ui.rail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import tv.own.owntv.core.settings.SettingsRepository.NavSize
import tv.own.owntv.features.shell.components.RailAvatar
import tv.own.owntv.gambit.ui.theme.GambitColors
import tv.own.owntv.ui.components.BrandMark
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.Wordmark
import tv.own.owntv.ui.components.rememberAppliedIcon
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/*
 * The side menu in TiviMate's layout, drawn for OwnTV's StageRail (which keeps every state, setting and
 * focus rule): a flat near-black strip, full height and flush with the screen edge. Closed, icons only,
 * the current section white and the rest grey. Open, icon and name rows; the focused row on the flat
 * green fill (Stage's focus, as Gambit draws it), the current section on grey.
 */

/** Closed: 112, inside the 128 OwnTV reserves for its capsule (28 + 84 + 16), so no screen moves. */
val GambitRailClosedWidth: Dp = 112.mpx

/**
 * Open, per the Navigation Size setting. TiviMate opens at ~440, but its screens keep a group column under
 * the menu; Gambit's screens put content there and are laid out around OwnTV's open rail (24 + 280 for
 * Normal), so the open menu covers no more than that did.
 */
fun gambitRailOpenWidth(size: NavSize): Dp = when (size) {
    NavSize.COMPACT -> 112.mpx
    NavSize.NORMAL -> 300.mpx
    NavSize.WIDE -> 340.mpx
    NavSize.EXTRA_WIDE -> 380.mpx
}

/** The space a docked rail takes from the content: its open width and a gap. */
fun gambitRailDockedReserve(size: NavSize): Dp = gambitRailOpenWidth(size) + 16.mpx

/** The strip itself: flat #121212 from the top of the screen to the bottom. */
fun gambitRailPanel(): Modifier = Modifier.background(GambitColors.Rail)

/** The mark at the top; with the rail open, "Gambit" beside it. */
@Composable
fun GambitRailBrand(open: Boolean) {
    val icon = rememberAppliedIcon()
    Box(
        Modifier.fillMaxWidth().padding(top = 18.mpx, bottom = 46.mpx).then(if (open) Modifier.padding(start = 22.mpx) else Modifier),
        contentAlignment = if (open) Alignment.CenterStart else Alignment.Center,
    ) {
        if (!open) {
            BrandMark(icon, 54.mpx)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.mpx), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(icon, 54.mpx)
                Wordmark(170.mpx)
            }
        }
    }
}

/** TiviMate has no rules between items; a little air stands in for OwnTV's separators. */
@Composable
fun GambitRailSeparator() {
    Box(Modifier.height(10.mpx))
}

/**
 * One item. Closed: the icon alone in an 80 square (white for the current section, grey otherwise).
 * Open: an 80 high row, icon and name in white; the current section on grey, the focused one on the
 * green focus fill. [trailing] (a count) and [detail] (a line under the name) come with Wide and up.
 */
@Composable
fun GambitRailItem(
    icon: OwnTVIcon,
    label: String,
    trailing: String?,
    detail: String?,
    open: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentIcon: Boolean = false,
) {
    val accent = stageAccent.accent
    val r = 10.mpx
    StageSurface(
        onClick = onClick,
        radius = r,
        modifier = modifier.then(if (open) Modifier.fillMaxWidth().height(80.mpx) else Modifier.size(80.mpx)),
        idle = if (active && open) Modifier.background(GambitColors.CellNow, RoundedCornerShape(r)) else Modifier,
        contentAlignment = if (open) Alignment.CenterStart else Alignment.Center,
    ) { focused ->
        val tint = when {
            focused || active -> Color.White
            accentIcon -> accent
            else -> GambitColors.Muted
        }
        if (!open) {
            OwnTVIcon(icon, tint, Modifier.size(34.mpx))
            return@StageSurface
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.mpx),
            horizontalArrangement = Arrangement.spacedBy(24.mpx),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OwnTVIcon(icon, if (accentIcon && !focused) accent else Color.White, Modifier.size(34.mpx))
            Column(Modifier.weight(1f)) {
                Text(label, style = stageText(28, 400), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail != null) {
                    Text(
                        detail, style = stageText(18, 400), color = if (focused) Color.White.copy(alpha = 0.8f) else GambitColors.Dim,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.mpx),
                    )
                }
            }
            if (trailing != null) {
                Text(trailing, style = stageText(22, 500), color = if (focused) Color.White else GambitColors.Dim, maxLines = 1)
            }
        }
    }
}

/**
 * The profile at the bottom: the avatar alone when closed; avatar and name (and from Wide up the
 * playlist line) when open. OK switches profile, a long press changes the avatar — as OwnTV's.
 */
@Composable
fun GambitRailProfile(
    open: Boolean,
    name: String,
    line: String?,
    avatarId: Int,
    avatarPath: String,
    onSwitchProfile: () -> Unit,
    onPickAvatar: () -> Unit,
) {
    val initial = name.trim().take(1).uppercase().ifEmpty { "?" }
    StageSurface(
        onClick = onSwitchProfile,
        onLongClick = onPickAvatar,
        radius = if (open) 10.mpx else 28.mpx,
        modifier = if (open) Modifier.fillMaxWidth().height(80.mpx) else Modifier.size(56.mpx),
        focusStyle = if (open) StageFocus.FILLED else StageFocus.POSTER,
        contentAlignment = if (open) Alignment.CenterStart else Alignment.Center,
    ) { focused ->
        if (!open) {
            RailAvatar(initial, size = 46, avatarId = avatarId, imagePath = avatarPath)
            return@StageSurface
        }
        Row(
            Modifier.padding(horizontal = 16.mpx),
            horizontalArrangement = Arrangement.spacedBy(18.mpx),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RailAvatar(initial, size = 46, avatarId = avatarId, imagePath = avatarPath)
            Column {
                Text(name, style = stageText(26, 500), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (line != null) {
                    Text(line, style = stageText(18, 400), color = if (focused) Color.White.copy(alpha = 0.8f) else GambitColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
