package tv.own.owntv.gambit.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.gambit.ui.theme.GambitColors
import tv.own.owntv.player.HudDialog
import tv.own.owntv.player.PlaybackEngine
import tv.own.owntv.player.SleepTimer
import tv.own.owntv.player.ZoomMode
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.theme.OwnTVTheme
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/** One entry of the player menu row: an icon over a label, tinted while [active]. */
@Immutable
class GambitMenuItem(
    val icon: OwnTVIcon,
    val label: String,
    val active: Boolean = false,
    val activeTint: Color? = null,
    val onClick: () -> Unit,
)

/**
 * The live player's tools for the menu row — the same set, conditions and actions as OwnTV's dock
 * (PlayerHudChrome.BottomBar) on a live channel; only the drawing is Gambit's. Each is present exactly
 * when the dock would show it.
 */
@Composable
internal fun gambitLiveMenu(
    player: PlaybackEngine,
    volume: Int,
    audioCount: Int,
    subCount: Int,
    zoomMode: ZoomMode,
    speedLabel: String,
    favorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
    onOpenJumpBack: (() -> Unit)?,
    onPreviousChannel: (() -> Unit)?,
    compatMode: Boolean?,
    onToggleCompatMode: (() -> Unit)?,
    onPip: (() -> Unit)?,
    onAudioMode: (() -> Unit)?,
    onMultiview: (() -> Unit)?,
    onRecordThis: (() -> Unit)?,
    recordingThis: Boolean,
    infoOn: Boolean,
    onInfo: () -> Unit,
    onReport: () -> Unit,
    onOpenDialog: (HudDialog) -> Unit,
): List<GambitMenuItem> {
    val qualities by player.videoQualities.collectAsStateWithLifecycle()
    val pick by player.videoQualityPick.collectAsStateWithLifecycle()
    val sleepLeft by org.koin.compose.koinInject<SleepTimer>().remainingMs.collectAsStateWithLifecycle()
    val normalSpeed = stringResource(R.string.player_speed_normal_short)
    val favoriteTint = OwnTVTheme.colors.favorite
    val labels = MenuLabels(
        audio = stringResource(R.string.player_tool_audio),
        subtitles = stringResource(R.string.player_tool_subtitles),
        volume = stringResource(R.string.player_tool_volume),
        speed = stringResource(R.string.player_tool_speed),
        favorite = stringResource(R.string.player_tool_favorite),
        catchUp = stringResource(R.string.player_tool_catchup),
        previous = stringResource(R.string.player_previous_channel),
        record = stringResource(if (recordingThis) R.string.recording_stop else R.string.recording_record),
        multiview = stringResource(R.string.multiview_button),
        pip = stringResource(R.string.player_tool_mini),
        audioOnly = stringResource(R.string.player_tool_audio_only),
        aspect = stringResource(R.string.player_tool_aspect),
        quality = stringResource(R.string.player_tool_quality),
        engine = stringResource(if (compatMode == true) R.string.player_engine_mpv else R.string.player_engine_exo),
        sleep = stringResource(R.string.player_sleep_timer),
        info = stringResource(R.string.player_tool_info),
        report = stringResource(R.string.player_tool_report),
    )
    return buildList {
        add(GambitMenuItem(OwnTVIcon.AUDIO, labels.audio, active = audioCount > 1) { onOpenDialog(HudDialog.AUDIO) })
        add(GambitMenuItem(OwnTVIcon.SUBTITLE, labels.subtitles, active = subCount > 0) { onOpenDialog(HudDialog.SUBS) })
        add(GambitMenuItem(volumeIcon(volume), labels.volume) { onOpenDialog(HudDialog.VOLUME) })
        add(GambitMenuItem(OwnTVIcon.FORWARD, if (speedLabel != normalSpeed) speedLabel else labels.speed, active = speedLabel != normalSpeed) { onOpenDialog(HudDialog.SPEED) })
        onToggleFavorite?.let { add(GambitMenuItem(OwnTVIcon.FAVORITE, labels.favorite, active = favorite, activeTint = favoriteTint, onClick = it)) }
        onOpenJumpBack?.let { add(GambitMenuItem(OwnTVIcon.CATCHUP, labels.catchUp, onClick = it)) }
        onPreviousChannel?.let { add(GambitMenuItem(OwnTVIcon.HISTORY, labels.previous, onClick = it)) }
        onRecordThis?.let { add(GambitMenuItem(OwnTVIcon.LIVE_DOT, labels.record, active = recordingThis, activeTint = Color(0xFFEF4444), onClick = it)) }
        onMultiview?.let { add(GambitMenuItem(OwnTVIcon.LIST_GRID, labels.multiview, onClick = it)) }
        onPip?.let { add(GambitMenuItem(OwnTVIcon.PIP, labels.pip, onClick = it)) }
        onAudioMode?.let { add(GambitMenuItem(OwnTVIcon.HEADPHONES, labels.audioOnly, onClick = it)) }
        add(GambitMenuItem(OwnTVIcon.ASPECT, labels.aspect, active = zoomMode != ZoomMode.FIT) { onOpenDialog(HudDialog.ZOOM) })
        if (qualities.isNotEmpty()) add(GambitMenuItem(OwnTVIcon.VIDEO, labels.quality, active = pick != null) { onOpenDialog(HudDialog.QUALITY) })
        onToggleCompatMode?.let { add(GambitMenuItem(OwnTVIcon.SWAP, labels.engine, active = compatMode == true, onClick = it)) }
        add(GambitMenuItem(OwnTVIcon.BEDTIME, labels.sleep, active = sleepLeft != null) { onOpenDialog(HudDialog.SLEEP_TIMER) })
        add(GambitMenuItem(OwnTVIcon.INFO, labels.info, active = infoOn, onClick = onInfo))
        // As on the dock: only while the stream info is up, there being nothing to report otherwise.
        if (infoOn) add(GambitMenuItem(OwnTVIcon.SHARE, labels.report, onClick = onReport))
    }
}

private class MenuLabels(
    val audio: String, val subtitles: String, val volume: String, val speed: String, val favorite: String,
    val catchUp: String, val previous: String, val record: String, val multiview: String, val pip: String,
    val audioOnly: String, val aspect: String, val quality: String, val engine: String, val sleep: String,
    val info: String, val report: String,
)

private fun volumeIcon(volume: Int): OwnTVIcon = when {
    volume == 0 -> OwnTVIcon.VOLUME_MUTE
    volume < 50 -> OwnTVIcon.VOLUME_LOW
    else -> OwnTVIcon.VOLUME_HIGH
}

/**
 * The menu row, TiviMate-style: icons over labels along the bottom, the focused one on an accent disc
 * with a white label, the rest grey. [focus] sits on the entry last used (the first at the start), so
 * the HUD's focus request lands back on it when a dialog the entry opened closes. Up hands back with
 * [onUp].
 */
@Composable
internal fun GambitMenuRow(
    items: List<GambitMenuItem>,
    focus: FocusRequester,
    lastIndex: Int,
    onFocusIndex: (Int) -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = lastIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (index - 3).coerceAtLeast(0))
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LazyRow(
        state = state,
        modifier = modifier.fillMaxWidth().onKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) { onUp(); true } else false
        },
        contentPadding = PaddingValues(horizontal = 48.mpx),
        horizontalArrangement = Arrangement.spacedBy(8.mpx),
    ) {
        itemsIndexed(items, key = { i, it -> it.icon.ordinal * 100 + i }) { i, item ->
            MenuEntry(
                item,
                Modifier.then(if (i == index) Modifier.focusRequester(focus) else Modifier)
                    .onFocusChanged { if (it.isFocused) onFocusIndex(i) },
            )
        }
    }
}

@Composable
private fun MenuEntry(item: GambitMenuItem, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val accent = stageAccent.accent
    val tint = when {
        focused -> Color.White
        item.active -> item.activeTint ?: accent
        else -> GambitColors.Muted
    }
    Column(
        modifier.width(210.mpx)
            .gambitOnOk(item.onClick)
            .focusable(interactionSource = interaction)
            .padding(vertical = 8.mpx),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(80.mpx).background(if (focused) accent.copy(alpha = 0.55f) else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            OwnTVIcon(item.icon, tint = tint, modifier = Modifier.size(40.mpx))
        }
        Text(
            item.label, style = stageText(26, 400), color = if (focused) Color.White else GambitColors.Muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.mpx).fillMaxWidth(),
        )
    }
}
