package tv.own.owntv.gambit.ui.player

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import tv.own.owntv.R
import tv.own.owntv.gambit.text.shownName
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.live.EpgNowNext
import tv.own.owntv.core.parser.XtEpgEntry
import tv.own.owntv.gambit.ui.theme.GambitColors
import tv.own.owntv.gambit.ui.theme.gambitAccentLight
import tv.own.owntv.gambit.ui.theme.gambitFocusFill
import tv.own.owntv.ui.components.ChannelLogoTile
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.format.rememberSystemTimeFormatter
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText
import java.util.Date

private const val Tabular = "tnum"

/**
 * What the shell knows about the playing live channel, for the info bar. [nowNext] is the playing
 * channel's guide; [recent] the recently watched channels for the tile row (without the one playing),
 * with [recentProgrammes] the programme on now for each.
 */
@Immutable
class GambitLiveInfo(
    val channel: ChannelEntity?,
    val header: String,
    val showNumber: Boolean,
    val nowNext: StateFlow<EpgNowNext?>,
    val recent: List<ChannelEntity>,
    val recentProgrammes: Map<Long, XtEpgEntry>,
    val onOpenGuide: () -> Unit,
    val onOpenHistory: (() -> Unit)?,
    val onTune: (ChannelEntity) -> Unit,
)

/**
 * The live info bar over the picture, in TiviMate's layout (OK during a live channel): "playlist •
 * category" top left and the date and time top right; at the bottom the channel logo, the programme on
 * now, "21:00 — 22:00 ▬ 27 min  1 UK: BBC One FHD [1920x1080] [25 FPS]", the next programme, a bar across
 * the screen, and a row of tiles: TV guide, History, then the recently watched channels.
 *
 * The HUD keeps its state and keys; this only draws. [firstFocus] goes on the TV guide tile (where the
 * HUD puts focus when the bar opens). On a channel with an archive the bar is the rewind timeline:
 * [timelineFocus] lands on it, Left/Right call [onScrub] (+ seconds = back), OK [onGoToLive] while behind.
 */
@Composable
internal fun GambitInfoBar(
    info: GambitLiveInfo,
    chips: List<String>,
    timeshiftOffsetSec: () -> Int?,
    onScrub: ((Int) -> Unit)?,
    scrubStepSec: Int,
    onGoToLive: (() -> Unit)?,
    firstFocus: FocusRequester,
    timelineFocus: FocusRequester,
    menu: List<GambitMenuItem> = emptyList(),
) {
    Box(Modifier.fillMaxSize()) { InfoBarContent(info, chips, timeshiftOffsetSec, onScrub, scrubStepSec, onGoToLive, firstFocus, timelineFocus, menu) }
}

@Composable
private fun BoxScope.InfoBarContent(
    info: GambitLiveInfo,
    chips: List<String>,
    timeshiftOffsetSec: () -> Int?,
    onScrub: ((Int) -> Unit)?,
    scrubStepSec: Int,
    onGoToLive: (() -> Unit)?,
    firstFocus: FocusRequester,
    timelineFocus: FocusRequester,
    menu: List<GambitMenuItem> = emptyList(),
) {
    val accent = stageAccent.accent
    val formatTime = rememberSystemTimeFormatter()
    val epg by info.nowNext.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(1_000); value = System.currentTimeMillis() } }
    // The guide's "now" is only refreshed now and then: once its programme has ended, the one on air is
    // found among next and upcoming (it was "No information" with the right programme on the next line).
    val known = remember(epg) { listOfNotNull(epg?.now, epg?.next) + epg?.upcoming.orEmpty() }
    val programme = known.firstOrNull { now >= it.startMs && now < it.stopMs }
    val next = known.filter { it.startMs >= (programme?.stopMs ?: now) }.minByOrNull { it.startMs }
    val channel = info.channel
    // Down from the tiles opens the menu row in place of the bottom block; Up comes back to the tiles.
    var menuOpen by remember { mutableStateOf(false) }
    var menuIndex by remember { mutableIntStateOf(0) }
    var menuWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(menuOpen) {
        if (menuOpen) menuWasOpen = true
        else if (menuWasOpen) runCatching { firstFocus.requestFocus() }
    }

    // Scrims: a light band behind the top line, a deep one behind the bottom block.
    Box(
        Modifier.align(Alignment.TopStart).fillMaxWidth().height(160.mpx)
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))),
    )
    Box(
        Modifier.align(Alignment.BottomStart).fillMaxWidth().height(620.mpx)
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.85f))),
    )

    // Top line.
    Row(
        Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 32.mpx, vertical = 28.mpx),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(info.header, style = stageText(30, 400), color = GambitColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        val locale = LocalConfiguration.current.locales[0]
        val datePattern = remember(locale) { DateFormat.getBestDateTimePattern(locale, "EEEdMMM") }
        Text(
            stringResource(R.string.gambit_date_time, DateFormat.format(datePattern, Date(now)).toString(), formatTime(now)),
            style = stageText(34, 400).copy(fontFeatureSettings = Tabular), color = GambitColors.Text, maxLines = 1,
            modifier = Modifier.padding(start = 24.mpx),
        )
    }

    if (menuOpen && menu.isNotEmpty()) {
        GambitMenuRow(
            items = menu,
            focus = firstFocus,
            lastIndex = menuIndex,
            onFocusIndex = { menuIndex = it },
            onUp = { menuOpen = false },
            modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 40.mpx),
        )
        return
    }

    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 16.mpx)) {
        // Logo, then the programme lines.
        Row(Modifier.padding(start = 80.mpx, end = 80.mpx), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogoTile(logoUrl = channel?.displayLogoUrl, modifier = Modifier.size(250.mpx, 170.mpx), fill = Color.Transparent) {
                Box(Modifier.size(250.mpx, 170.mpx), contentAlignment = Alignment.Center) { OwnTVIcon(OwnTVIcon.LIVE_TV, tint = GambitColors.Dim, modifier = Modifier.size(64.mpx)) }
            }
            Column(Modifier.padding(start = 30.mpx).weight(1f)) {
                Text(
                    programme?.title ?: stringResource(R.string.gambit_epg_no_information),
                    style = stageText(40, 700), color = if (programme != null) GambitColors.Text else GambitColors.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.padding(top = 12.mpx), verticalAlignment = Alignment.CenterVertically) {
                    if (programme != null) {
                        Text(
                            stringResource(R.string.gambit_time_range, formatTime(programme.startMs), formatTime(programme.stopMs)),
                            style = stageText(28, 400).copy(fontFeatureSettings = Tabular), color = GambitColors.Muted, maxLines = 1,
                        )
                        val progress = ((now - programme.startMs).toFloat() / (programme.stopMs - programme.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                        Box(
                            Modifier.padding(start = 24.mpx).width(80.mpx).height(5.mpx).drawBehind {
                                val r = CornerRadius(size.height / 2f)
                                drawRoundRect(GambitColors.ProgressTrack, cornerRadius = r)
                                drawRoundRect(GambitColors.Text, size = Size(size.width * progress, size.height), cornerRadius = r)
                            },
                        )
                        val left = ((programme.stopMs - now) + 59_999) / 60_000
                        Text(
                            stringResource(R.string.player_duration_minutes, left.toInt()), style = stageText(28, 400), color = GambitColors.Muted,
                            maxLines = 1, modifier = Modifier.padding(start = 24.mpx),
                        )
                    }
                    if (channel != null) {
                        Text(
                            channel.number?.takeIf { info.showNumber }?.let { stringResource(R.string.gambit_channel_number_name, it.toString(), channel.shownName) } ?: channel.shownName,
                            style = stageText(28, 600), color = GambitColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = if (programme != null) 40.mpx else 0.mpx).weight(1f, fill = false),
                        )
                    }
                    chips.forEach { chip ->
                        Text(
                            chip, style = stageText(20, 600), color = Color.Black, maxLines = 1,
                            modifier = Modifier.padding(start = 16.mpx)
                                .background(GambitColors.Muted, RoundedCornerShape(5.mpx))
                                .padding(horizontal = 8.mpx, vertical = 2.mpx),
                        )
                    }
                }
                if (next != null) {
                    Row(Modifier.padding(top = 10.mpx), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.gambit_time_range, formatTime(next.startMs), formatTime(next.stopMs)),
                            style = stageText(28, 400).copy(fontFeatureSettings = Tabular), color = GambitColors.Muted, maxLines = 1,
                        )
                        Text(next.title, style = stageText(28, 400), color = GambitColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 24.mpx))
                    }
                }
            }
        }

        // The bar across the screen: how far through the programme we are, and with an archive the
        // moment being watched (the dot), which Left/Right move.
        val offset = timeshiftOffsetSec()?.takeIf { it > 0 }
        GambitTimeline(
            programme = programme,
            now = now,
            offsetSec = offset,
            onScrub = onScrub,
            scrubStepSec = scrubStepSec,
            onGoToLive = onGoToLive,
            focus = timelineFocus,
            modifier = Modifier.padding(start = 80.mpx, end = 80.mpx, top = 22.mpx),
        )

        // Tiles: TV guide, History, then the recently watched channels.
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(top = 18.mpx).onPreviewKeyEvent { e ->
                if (menu.isNotEmpty() && e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) { menuOpen = true; true } else false
            },
            contentPadding = PaddingValues(horizontal = 64.mpx),
            horizontalArrangement = Arrangement.spacedBy(14.mpx),
        ) {
            item(key = -1L) {
                ActionTile(OwnTVIcon.EPG, stringResource(R.string.common_nav_guide), accent, info.onOpenGuide, Modifier.focusRequester(firstFocus))
            }
            info.onOpenHistory?.let { open ->
                item(key = -2L) { ActionTile(OwnTVIcon.HISTORY, stringResource(R.string.content_history), accent, open) }
            }
            items(info.recent, key = { it.id }) { ch ->
                ChannelTile(ch, info.recentProgrammes[ch.id]?.takeIf { now < it.stopMs }, now, accent) { info.onTune(ch) }
            }
        }
        // ⌄ — the menu row is below.
        if (menu.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 6.mpx), contentAlignment = Alignment.Center) {
                OwnTVIcon(OwnTVIcon.CHEVRON_DOWN, tint = GambitColors.Muted, modifier = Modifier.size(30.mpx))
            }
        }
    }
}

private val TileWidth = 236.mpx
private val TileHeight = 180.mpx

/**
 * OK (centre, Enter) on release — but only a release whose press this element saw. The press that opens
 * the info bar lands before focus does, so without this its release clicked the first tile (TV guide).
 * Losing focus forgets a half-made press.
 */
@Composable
internal fun Modifier.gambitOnOk(action: () -> Unit): Modifier {
    var pressed by remember { mutableStateOf(false) }
    return onFocusChanged { if (!it.isFocused) pressed = false }
        .onKeyEvent { e ->
            val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
            when {
                !ok -> false
                e.type == KeyEventType.KeyDown -> { pressed = true; true }
                e.type == KeyEventType.KeyUp -> { if (pressed) { pressed = false; action() }; true }
                else -> true
            }
        }
}

@Composable
private fun ActionTile(icon: OwnTVIcon, label: String, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        modifier.size(TileWidth, TileHeight)
            .background(if (focused) gambitFocusFill(accent) else GambitPanelFill, RoundedCornerShape(10.mpx))
            .gambitOnOk(onClick)
            .focusable(interactionSource = interaction),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OwnTVIcon(icon, tint = Color.White, modifier = Modifier.size(64.mpx))
        Text(label, style = stageText(30, 500), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.mpx, start = 12.mpx, end = 12.mpx))
    }
}

@Composable
private fun ChannelTile(channel: ChannelEntity, programme: XtEpgEntry?, now: Long, accent: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        Modifier.size(TileWidth, TileHeight)
            .background(if (focused) gambitFocusFill(accent) else GambitPanelFill, RoundedCornerShape(10.mpx))
            .gambitOnOk(onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 14.mpx, vertical = 14.mpx),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ChannelLogoTile(logoUrl = channel.displayLogoUrl, modifier = Modifier.size(150.mpx, 96.mpx), fill = Color.Transparent) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(channel.shownName, style = stageText(22, 600), color = GambitColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            programme?.title ?: stringResource(R.string.gambit_epg_no_information),
            style = stageText(24, 400),
            color = when {
                programme == null -> GambitColors.Dim
                focused -> gambitAccentLight(accent)
                else -> accent
            },
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 14.mpx),
        )
        val progress = programme?.let { ((now - it.startMs).toFloat() / (it.stopMs - it.startMs).coerceAtLeast(1)).coerceIn(0f, 1f) } ?: 0f
        Box(
            Modifier.padding(top = 8.mpx).fillMaxWidth().height(3.mpx).drawBehind {
                drawRect(Color.White.copy(alpha = 0.22f))
                if (progress > 0f) drawRect(Color.White, size = Size(size.width * progress, size.height))
            },
        )
    }
}

/**
 * The bar across the screen: green up to now through the programme, a dot on the moment being watched.
 * Focusable only on a channel with an archive ([onScrub] set): Left/Right step back and forward, OK goes
 * back to live while behind it.
 */
@Composable
private fun GambitTimeline(
    programme: XtEpgEntry?,
    now: Long,
    offsetSec: Int?,
    onScrub: ((Int) -> Unit)?,
    scrubStepSec: Int,
    onGoToLive: (() -> Unit)?,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val accent = stageAccent.accent
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val watching = now - (offsetSec ?: 0) * 1000L
    fun fraction(t: Long) = programme?.let { ((t - it.startMs).toFloat() / (it.stopMs - it.startMs).coerceAtLeast(1)).coerceIn(0f, 1f) } ?: 1f
    val live = fraction(now)
    val dot = fraction(watching)
    val scrubbable = onScrub != null
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).height(24.mpx)
                .then(
                    if (scrubbable) Modifier.focusRequester(focus)
                        .onKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (e.key) {
                                Key.DirectionLeft -> { onScrub?.invoke(scrubStepSec); true }
                                Key.DirectionRight -> { onScrub?.invoke(-scrubStepSec); true }
                                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (offsetSec != null) onGoToLive?.invoke(); true }
                                else -> false
                            }
                        }
                        .focusable(interactionSource = interaction)
                    else Modifier,
                )
                .drawBehind {
                    val h = (if (focused) 6 else 4).mpx.toPx()
                    val y = (size.height - h) / 2f
                    drawRect(Color.White.copy(alpha = 0.35f), Offset(0f, y), Size(size.width, h))
                    drawRect(accent, Offset(0f, y), Size(size.width * live, h))
                    // Behind live: the stretch still to be watched up to live, a lighter accent.
                    if (dot < live) drawRect(Color.White.copy(alpha = 0.55f), Offset(size.width * dot, y), Size(size.width * (live - dot), h))
                    drawCircle(if (focused) Color.White else GambitColors.Muted, radius = (if (focused) 11 else 9).mpx.toPx(), center = Offset(size.width * dot, size.height / 2f))
                },
        )
        if (offsetSec != null) {
            // "−12:34" behind live, then the way back.
            val m = offsetSec / 60
            val s = offsetSec % 60
            Text(
                stringResource(R.string.gambit_behind_live, m, s), style = stageText(24, 600).copy(fontFeatureSettings = Tabular),
                color = GambitColors.Text, maxLines = 1, modifier = Modifier.padding(start = 20.mpx),
            )
            if (focused && onGoToLive != null) {
                Text(
                    stringResource(R.string.player_go_live), style = stageText(24, 600), color = gambitAccentLight(accent),
                    maxLines = 1, modifier = Modifier.padding(start = 16.mpx),
                )
            }
        }
    }
}
