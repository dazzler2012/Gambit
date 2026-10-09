package tv.own.owntv.gambit.ui.player

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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.i18n.HorizontalDirection
import tv.own.owntv.core.i18n.horizontalDirection
import tv.own.owntv.core.parser.XtEpgEntry
import tv.own.owntv.gambit.ui.theme.GambitColors
import tv.own.owntv.gambit.ui.theme.gambitAccentLight
import tv.own.owntv.gambit.ui.theme.gambitFocusFill
import tv.own.owntv.ui.components.ChannelLogoTile
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/** The channel panel's width over the video (TiviMate-style: about a third of the screen). */
internal val GambitChannelPanelWidth = 620.mpx

/** Translucent near-black behind the panels, so the picture still shows through. */
internal val GambitPanelFill = Color(0xFF0E0E0E).copy(alpha = 0.86f)

/**
 * The channel list over the playing video, in Gambit's look: "playlist • category" on top, then a row
 * per channel with its logo, "number name", the programme on now (or "No information") and its
 * progress. The playing channel carries a ▶. A drop-in for OwnTV's ChannelListOverlay — same inputs,
 * same keys (OK tunes, Back closes, pushing outward opens the groups or closes the Right-hand list) —
 * plus [programmes], the programme on now per channel, for the times and progress.
 *
 * [active] false shows the list without taking focus (the group list beside it has it); turning it
 * back on puts focus on the playing channel again.
 */
@Composable
fun GambitChannelList(
    channels: List<ChannelEntity>,
    currentId: Long?,
    onSelect: (ChannelEntity) -> Unit,
    onDismiss: () -> Unit,
    programmes: Map<Long, XtEpgEntry> = emptyMap(),
    title: String? = null,
    alignEnd: Boolean = false,
    showNumbers: Boolean = true,
    providerNames: Map<Long, String> = emptyMap(),
    onOpenCategories: (() -> Unit)? = null,
    active: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // Full screen like the overlay it replaces; the panel sits at the start (or the end, for history).
    Box(modifier.fillMaxSize(), contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart) {
        GambitChannelListPanel(channels, currentId, onSelect, onDismiss, programmes, title, alignEnd, showNumbers, providerNames, onOpenCategories, active)
    }
}

/** The panel itself, without the full-screen box (laid out beside the group list by GambitChannelPanels). */
@Composable
internal fun GambitChannelListPanel(
    channels: List<ChannelEntity>,
    currentId: Long?,
    onSelect: (ChannelEntity) -> Unit,
    onDismiss: () -> Unit,
    programmes: Map<Long, XtEpgEntry>,
    title: String?,
    alignEnd: Boolean,
    showNumbers: Boolean,
    providerNames: Map<Long, String>,
    onOpenCategories: (() -> Unit)?,
    active: Boolean,
    modifier: Modifier = Modifier,
    onFocusChannel: ((ChannelEntity) -> Unit)? = null,
    onOpenSchedule: (() -> Unit)? = null,
    focusId: Long? = null,
) {
    val layoutDirection = LocalLayoutDirection.current
    val dismissDirection = if (alignEnd) HorizontalDirection.END else HorizontalDirection.START
    val currentIndex = remember(channels, currentId) { channels.indexOfFirst { it.id == currentId }.coerceAtLeast(0) }
    // Where focus lands when the list takes it: [focusId] (the channel the schedule was opened from)
    // while it is still in the list, otherwise the playing channel.
    val focusTarget = focusId?.takeIf { id -> channels.any { it.id == id } } ?: channels.getOrNull(currentIndex)?.id
    val listState = rememberLazyListState()
    val focusCurrent = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { listState.scrollToItem(currentIndex) } }
    LaunchedEffect(active) { if (active) runCatching { focusCurrent.requestFocus() } }
    BackHandler(enabled = active) { onDismiss() }
    // Progress moves on the minute; nothing here needs to be finer than that.
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val header = gambitListHeader(channels.firstOrNull(), title, providerNames)

    Column(
        modifier
            .width(GambitChannelPanelWidth)
            .fillMaxHeight()
            .background(GambitPanelFill)
            .focusProperties { canFocus = active }
            .onPreviewKeyEvent { e ->
                if (!active || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val direction = e.key.horizontalDirection(layoutDirection)
                if (direction == dismissDirection) {
                    if (!alignEnd && onOpenCategories != null) onOpenCategories() else onDismiss()
                    true
                } else if (!alignEnd && direction == HorizontalDirection.END && onOpenSchedule != null) {
                    onOpenSchedule()
                    true
                } else false
            },
    ) {
        Box(Modifier.fillMaxWidth().height(76.mpx).background(Color.White.copy(alpha = 0.05f)), contentAlignment = Alignment.CenterStart) {
            Text(header, style = stageText(26, 600), color = GambitColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 32.mpx))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.mpx, vertical = 14.mpx),
            verticalArrangement = Arrangement.spacedBy(4.mpx),
        ) {
            items(channels, key = { it.id }) { ch ->
                GambitChannelRow(
                    channel = ch,
                    playing = ch.id == currentId,
                    programme = programmes[ch.id]?.takeIf { now < it.stopMs },
                    now = now,
                    showNumber = showNumbers,
                    onClick = { onSelect(ch) },
                    modifier = (if (ch.id == focusTarget) Modifier.focusRequester(focusCurrent) else Modifier)
                        .onFocusChanged { if (it.isFocused) onFocusChannel?.invoke(ch) },
                )
            }
        }
    }
}

/** "vocotv  •  ENTERTAINMENT | UK" — the playlist's name only when there is one to show. */
@Composable
internal fun gambitListHeader(channel: ChannelEntity?, title: String?, providerNames: Map<Long, String>): String {
    val listTitle = title ?: stringResource(R.string.content_channel_overlay_title)
    return channel?.let { providerNames[it.sourceId] }
        ?.let { stringResource(R.string.gambit_channel_list_header, it, listTitle) } ?: listTitle
}

@Composable
private fun GambitChannelRow(
    channel: ChannelEntity,
    playing: Boolean,
    programme: XtEpgEntry?,
    now: Long,
    showNumber: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val accent = stageAccent.accent
    Row(
        modifier
            .fillMaxWidth()
            .height(116.mpx)
            .background(if (focused) gambitFocusFill(accent) else Color.Transparent, RoundedCornerShape(10.mpx))
            .onKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (ok && e.type == KeyEventType.KeyUp) { onClick(); true } else ok
            }
            .focusable(interactionSource = interaction)
            .padding(horizontal = 16.mpx),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.mpx),
    ) {
        ChannelLogoTile(logoUrl = channel.displayLogoUrl, modifier = Modifier.size(104.mpx, 64.mpx), fill = Color.Transparent) {
            Box(Modifier.size(104.mpx, 64.mpx), contentAlignment = Alignment.Center) { OwnTVIcon(OwnTVIcon.LIVE_TV, tint = GambitColors.Dim, modifier = Modifier.size(30.mpx)) }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    channel.number?.takeIf { showNumber }?.let { stringResource(R.string.gambit_channel_number_name, it.toString(), channel.name) } ?: channel.name,
                    style = stageText(26, 600), color = GambitColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (playing) OwnTVIcon(OwnTVIcon.PLAY, tint = if (focused) Color.White else accent, modifier = Modifier.padding(start = 10.mpx).size(26.mpx), filled = true)
            }
            Text(
                programme?.title ?: stringResource(R.string.gambit_epg_no_information),
                style = stageText(25, 400),
                color = when {
                    programme == null -> GambitColors.Dim
                    focused -> gambitAccentLight(accent)
                    else -> accent
                },
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.mpx),
            )
            // The programme's progress: a thin line, white over a faint track.
            val progress = programme?.let { ((now - it.startMs).toFloat() / (it.stopMs - it.startMs).coerceAtLeast(1)).coerceIn(0f, 1f) } ?: 0f
            Box(
                Modifier.padding(top = 10.mpx).fillMaxWidth().height(3.mpx).drawBehind {
                    drawRect(Color.White.copy(alpha = 0.22f))
                    if (progress > 0f) drawRect(Color.White, size = Size(size.width * progress, size.height))
                },
            )
        }
    }
}
