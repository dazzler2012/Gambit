package tv.own.owntv.gambit.ui.player

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.i18n.HorizontalDirection
import tv.own.owntv.core.i18n.horizontalDirection
import tv.own.owntv.gambit.ui.guide.GambitProgrammeBlock
import tv.own.owntv.gambit.ui.theme.GambitColors
import tv.own.owntv.gambit.ui.theme.gambitAccentLight
import tv.own.owntv.gambit.ui.theme.gambitFocusFill
import tv.own.owntv.ui.components.ChannelLogoTile
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.format.rememberSystemTimeFormatter
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText
import java.util.Calendar
import java.util.Date

/** The schedule column's width, beside the channel list. */
internal val GambitSchedulePanelWidth = 560.mpx

/** The programme card's width, top right over the video. */
internal val GambitProgrammeCardWidth = 680.mpx

private const val Tabular = "tnum"

/** One line of the schedule: a day heading ("Tomorrow") or a programme. */
private sealed interface ScheduleLine {
    data class Day(val startMs: Long) : ScheduleLine
    data class Programme(val programme: EpgProgrammeEntity) : ScheduleLine
}

/** Midnight at the start of [ms]'s local day. */
private fun dayStart(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** The programmes with a heading before the first of each day other than today. */
private fun scheduleLines(programmes: List<EpgProgrammeEntity>, now: Long): List<ScheduleLine> {
    val today = dayStart(now)
    var lastDay = today
    val out = ArrayList<ScheduleLine>(programmes.size + 4)
    programmes.forEachIndexed { i, p ->
        val day = dayStart(p.startMs)
        // The first line gets a heading only when it is not today (a window opening on yesterday evening).
        if (if (i == 0) day != today else day != lastDay) out += ScheduleLine.Day(day)
        lastDay = day
        out += ScheduleLine.Programme(p)
    }
    return out
}

/**
 * The schedule column beside the in-player channel list, as TiviMate lays it out: the channel's logo,
 * "number name" and "playlist • category" on top, then a line per programme ("21:00  Who Do You Think
 * You Are?") with a heading where a new day starts. The programme on now is in the accent, ended ones
 * are dimmed. It opens scrolled to now.
 *
 * [active] true gives it focus, on the programme on now; Up/Down then move through the programmes and
 * [onFocusProgramme] follows them for the card. Left (or Back) hands focus back with [onExit]; OK on the
 * programme on now tunes the channel through [onTune]. Other programmes do nothing on OK yet (catch-up
 * comes later).
 */
@Composable
internal fun GambitSchedulePanel(
    channel: ChannelEntity,
    subtitle: String,
    programmes: List<EpgProgrammeEntity>,
    now: Long,
    showNumber: Boolean,
    active: Boolean,
    onFocusProgramme: (EpgProgrammeEntity) -> Unit,
    onTune: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layoutDirection = LocalLayoutDirection.current
    val formatTime = rememberSystemTimeFormatter()
    val accent = stageAccent.accent
    val lines = remember(programmes, now / 60_000) { scheduleLines(programmes, now) }
    // Focus lands on the programme on now; with none on, the next one; with nothing ahead, the last.
    val focusIndex = remember(lines, now / 60_000) {
        lines.indexOfFirst { it is ScheduleLine.Programme && now < it.programme.stopMs }
            .takeIf { it >= 0 } ?: lines.indexOfLast { it is ScheduleLine.Programme }
    }
    val focusNow = remember { FocusRequester() }
    BackHandler(enabled = active) { onExit() }
    LaunchedEffect(active) { if (active) runCatching { focusNow.requestFocus() } }

    Column(
        modifier
            .width(GambitSchedulePanelWidth)
            .fillMaxHeight()
            .background(GambitPanelFill.copy(alpha = 0.62f))
            .focusProperties { canFocus = active }
            .onPreviewKeyEvent { e ->
                if (!active || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key.horizontalDirection(layoutDirection)) {
                    HorizontalDirection.START -> { onExit(); true }
                    HorizontalDirection.END -> true // nothing further right to go to
                    else -> false
                }
            },
    ) {
        // Header: logo, "1  UK: BBC One FHD", "vocotv  •  ENTERTAINMENT | UK".
        Row(
            Modifier.fillMaxWidth().height(120.mpx).padding(horizontal = 28.mpx),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.mpx),
        ) {
            ChannelLogoTile(logoUrl = channel.displayLogoUrl, modifier = Modifier.size(112.mpx, 70.mpx), fill = Color.Transparent) {
                Box(Modifier.size(112.mpx, 70.mpx), contentAlignment = Alignment.Center) { OwnTVIcon(OwnTVIcon.LIVE_TV, tint = GambitColors.Dim, modifier = Modifier.size(32.mpx)) }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    channel.number?.takeIf { showNumber }?.let { stringResource(R.string.gambit_channel_number_name, it.toString(), channel.name) } ?: channel.name,
                    style = stageText(26, 600), color = GambitColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(subtitle, style = stageText(24, 400), color = GambitColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.mpx))
            }
        }
        if (lines.isEmpty()) {
            Text(
                stringResource(R.string.gambit_epg_no_information), style = stageText(26, 400), color = GambitColors.Dim,
                modifier = Modifier.padding(horizontal = 28.mpx, vertical = 20.mpx),
            )
            return@Column
        }
        // A fresh list per channel, opened with the programme on now a few lines down (as in TiviMate).
        key(channel.id) {
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (focusIndex - 5).coerceAtLeast(0))
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.mpx, vertical = 8.mpx),
                verticalArrangement = Arrangement.spacedBy(2.mpx),
            ) {
                // Keys: a programme's start (unique in one channel's schedule), a day heading's midnight negated.
                items(lines.size, key = { i -> lines[i].let { if (it is ScheduleLine.Programme) it.programme.startMs else -(it as ScheduleLine.Day).startMs } }) { i ->
                    when (val line = lines[i]) {
                        is ScheduleLine.Day -> DayHeading(line.startMs, now, accent)
                        is ScheduleLine.Programme -> {
                            val p = line.programme
                            val onNow = now in p.startMs until p.stopMs
                            ProgrammeLine(
                                time = formatTime(p.startMs),
                                title = p.title,
                                onNow = onNow,
                                ended = now >= p.stopMs,
                                accent = accent,
                                onClick = { if (onNow) onTune() },
                                modifier = (if (i == focusIndex) Modifier.focusRequester(focusNow) else Modifier)
                                    .onFocusChanged { if (it.isFocused) onFocusProgramme(p) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "Tomorrow" (or "Yesterday", or "Sat 11 Oct") in the accent, where a new day starts. */
@Composable
private fun DayHeading(dayStartMs: Long, now: Long, accent: Color) {
    val locale = LocalConfiguration.current.locales[0]
    val today = dayStart(now)
    val tomorrow = dayStart(today + 36 * 3_600_000L)
    val yesterday = dayStart(today - 12 * 3_600_000L)
    val label = when (dayStartMs) {
        tomorrow -> stringResource(R.string.gambit_schedule_tomorrow)
        yesterday -> stringResource(R.string.gambit_schedule_yesterday)
        else -> remember(dayStartMs, locale) {
            DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), Date(dayStartMs)).toString()
        }
    }
    Text(
        label, style = stageText(26, 500), color = gambitAccentLight(accent), maxLines = 1,
        modifier = Modifier.padding(start = 16.mpx, top = 22.mpx, bottom = 10.mpx),
    )
}

@Composable
private fun ProgrammeLine(
    time: String,
    title: String,
    onNow: Boolean,
    ended: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val color = when {
        onNow -> if (focused) gambitAccentLight(accent) else accent
        ended && !focused -> GambitColors.Dim
        else -> GambitColors.Text
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(74.mpx)
            .background(if (focused) gambitFocusFill(accent) else Color.Transparent, RoundedCornerShape(10.mpx))
            .onKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (ok && e.type == KeyEventType.KeyUp) { onClick(); true } else ok
            }
            .focusable(interactionSource = interaction)
            .padding(horizontal = 16.mpx),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.mpx),
    ) {
        Text(time, style = stageText(28, 400).copy(fontFeatureSettings = Tabular), color = color, maxLines = 1)
        Text(title, style = stageText(28, 400), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** The programme card, top right: title, "21:00 — 22:00 ▬▬ 58 min", then the description. */
@Composable
internal fun GambitProgrammeCard(
    programme: EpgProgrammeEntity?,
    fallbackTitle: String,
    now: Long,
    modifier: Modifier = Modifier,
) {
    GambitProgrammeBlock(
        programme = programme,
        fallbackTitle = fallbackTitle,
        now = now,
        titleSize = 34,
        descriptionLines = 4,
        modifier = modifier
            .width(GambitProgrammeCardWidth)
            .background(GambitPanelFill.copy(alpha = 0.8f), RoundedCornerShape(14.mpx))
            .padding(horizontal = 32.mpx, vertical = 28.mpx),
    )
}
