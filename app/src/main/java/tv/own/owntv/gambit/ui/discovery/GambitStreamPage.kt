package tv.own.owntv.gambit.ui.discovery

import android.text.format.Formatter
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.gambit.addons.net.AddonError
import tv.own.owntv.gambit.discovery.StreamFeed
import tv.own.owntv.gambit.discovery.StreamListState
import tv.own.owntv.gambit.discovery.StreamQuality
import tv.own.owntv.gambit.discovery.StreamRow
import tv.own.owntv.gambit.discovery.StreamTarget
import tv.own.owntv.gambit.ui.addons.displayLabel
import tv.own.owntv.gambit.ui.theme.GambitColors
import tv.own.owntv.ui.format.localizedInteger
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.stage.StageTag
import tv.own.owntv.ui.stage.StageTool
import tv.own.owntv.ui.theme.StageRadii
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/** Name and detail lines shown per row; the rest are cut. */
private const val MAX_NAME_LINES = 3
private const val MAX_DETAIL_LINES = 4

/**
 * The stream list over the title page: every enabled stream addon's results for one movie or episode,
 * in addon order. Rows carry display text only (never an address). OK on a row shows a one-line note
 * until playing is added. [mainFocus] is the first row, or Try again; it takes focus on opening and
 * from the rail.
 */
@Composable
internal fun GambitStreamPage(
    list: StreamListState,
    mainFocus: FocusRequester,
    playNote: PlayNote?,
    /** The row that takes focus: the stream last played (after Back from the player), else the first. */
    initialKey: Int?,
    onPlay: (Int) -> Unit,
    onRetry: () -> Unit,
    onChildFocused: () -> Unit,
    contentStart: Dp,
    modifier: Modifier = Modifier,
) {
    val feed = list.feed

    LaunchedEffect(list.target, feed::class) {
        if (feed == StreamFeed.Loading) return@LaunchedEffect
        for (attempt in 0 until 10) {
            withFrameNanos { }
            if (runCatching { mainFocus.requestFocus() }.getOrDefault(false)) break
        }
    }

    Column(
        modifier.fillMaxSize()
            .padding(start = contentStart, top = 48.mpx, end = 64.mpx)
            .onFocusChanged { if (it.hasFocus) onChildFocused() }
            .focusGroup(),
    ) {
        Text(list.target.title, style = stageText(40, 800), color = GambitColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            list.target.subtitle(), style = stageText(22, 600), color = GambitColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.mpx),
        )
        if (playNote != null) {
            Text(playNote.message(), style = stageText(19, 600), color = GambitColors.Text, modifier = Modifier.padding(top = 10.mpx))
        }

        when (feed) {
            StreamFeed.Loading -> Note(stringResource(R.string.gambit_streams_loading))
            is StreamFeed.Failed -> {
                Note(stringResource(R.string.gambit_streams_failed_title), feed.error.streamMessage())
                RetryRow(mainFocus, onRetry)
            }
            is StreamFeed.Loaded -> if (feed.rows.isEmpty()) {
                Note(
                    stringResource(R.string.gambit_streams_empty),
                    stringResource(if (feed.unplayable > 0) R.string.gambit_streams_empty_unplayable else R.string.gambit_streams_empty_body),
                )
                feed.errors.forEach { ErrorLine(it) }
                RetryRow(mainFocus, onRetry)
            } else {
                val focusKey = initialKey?.takeIf { k -> feed.rows.any { it.key == k } } ?: feed.rows.first().key
                val startIndex = feed.rows.indexOfFirst { it.key == focusKey }.coerceAtLeast(0)
                LazyColumn(
                    state = rememberLazyListState(initialFirstVisibleItemIndex = startIndex),
                    verticalArrangement = Arrangement.spacedBy(12.mpx),
                    contentPadding = PaddingValues(top = 22.mpx, bottom = 40.mpx),
                    modifier = Modifier.fillMaxSize().focusRestorer().focusGroup(),
                ) {
                    itemsIndexed(feed.rows, key = { _, row -> row.key }) { _, row ->
                        StreamRowView(
                            row = row,
                            onClick = { onPlay(row.key) },
                            modifier = if (row.key == focusKey) Modifier.focusRequester(mainFocus) else Modifier,
                        )
                    }
                    item(key = -1) {
                        Column(Modifier.padding(top = 8.mpx)) {
                            if (feed.unplayable > 0) {
                                Text(
                                    pluralStringResource(R.plurals.gambit_streams_unplayable, feed.unplayable, localizedInteger(feed.unplayable)),
                                    style = stageText(18, 500), color = GambitColors.Dim,
                                )
                            }
                            feed.errors.forEach { ErrorLine(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamRowView(row: StreamRow, onClick: () -> Unit, modifier: Modifier) {
    val accent = stageAccent
    val context = LocalContext.current
    StageSurface(onClick = onClick, radius = StageRadii.Row, modifier = modifier.fillMaxWidth()) { focused ->
        val on = if (focused) accent.onAccent else null
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.mpx, vertical = 16.mpx),
            horizontalArrangement = Arrangement.spacedBy(24.mpx),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.width(300.mpx)) {
                row.nameLines.take(MAX_NAME_LINES).forEachIndexed { i, line ->
                    Text(
                        line, style = if (i == 0) stageText(20, 700) else stageText(18, 600),
                        color = on ?: if (i == 0) GambitColors.Text else GambitColors.Muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                row.detailLines.take(MAX_DETAIL_LINES).forEach { line ->
                    Text(line, style = stageText(18, 500), color = on ?: GambitColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.mpx)) {
                row.quality?.let { StageTag(it.label(), onAccent = on) }
                if (row.mayNotPlay) StageTag(stringResource(R.string.gambit_streams_may_not_play), onAccent = on)
                row.sizeBytes?.let {
                    Text(Formatter.formatShortFileSize(context, it), style = stageText(17, 600), color = on ?: GambitColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(row.addonName, style = stageText(15, 600), color = on ?: GambitColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.mpx))
            }
        }
    }
}

@Composable
private fun RetryRow(focus: FocusRequester, onRetry: () -> Unit) {
    Row(Modifier.padding(top = 20.mpx)) {
        StageTool(stringResource(R.string.gambit_discovery_retry), onClick = onRetry, boxed = true, modifier = Modifier.focusRequester(focus))
    }
}

@Composable
private fun Note(title: String, body: String? = null) {
    Column(Modifier.widthIn(max = 1000.mpx).padding(top = 30.mpx)) {
        Text(title, style = stageText(24, 600), color = GambitColors.Text)
        if (body != null) Text(body, style = stageText(19, 500), color = GambitColors.Muted, modifier = Modifier.padding(top = 8.mpx))
    }
}

@Composable
private fun ErrorLine(error: AddonError) {
    Text(error.streamMessage(), style = stageText(17, 500), color = GambitColors.Dim, modifier = Modifier.padding(top = 8.mpx).widthIn(max = 1000.mpx))
}

/** "Season 1 · Episode 2 · Title" for an episode; "Streams" for a movie. */
@Composable
private fun StreamTarget.subtitle(): String {
    val s = season
    val e = episode
    if (s == null || e == null) return stringResource(R.string.gambit_streams_heading)
    val sn = localizedInteger(s, grouping = false)
    val en = localizedInteger(e, grouping = false)
    return episodeTitle?.let { stringResource(R.string.gambit_streams_episode_titled, sn, en, it) }
        ?: stringResource(R.string.gambit_streams_episode, sn, en)
}

@Composable
private fun PlayNote.message(): String = stringResource(
    when (this) {
        PlayNote.STARTING -> R.string.gambit_play_starting
        PlayNote.HLS -> R.string.gambit_play_hls
        PlayNote.NOT_PLAYABLE -> R.string.gambit_play_not_playable
        PlayNote.FAILED -> R.string.gambit_play_failed
    },
)

@Composable
private fun StreamQuality.label(): String = stringResource(
    when (this) {
        StreamQuality.UHD -> R.string.gambit_streams_quality_uhd
        StreamQuality.FHD -> R.string.gambit_streams_quality_fhd
        StreamQuality.HD -> R.string.gambit_streams_quality_hd
        StreamQuality.SD -> R.string.gambit_streams_quality_sd
    },
)

/** Each error type worded for the stream list; the addon by its name and host only. */
@Composable
private fun AddonError.streamMessage(): String = when (this) {
    is AddonError.Timeout -> stringResource(R.string.gambit_discovery_err_timeout, addon.displayLabel())
    is AddonError.Network -> stringResource(R.string.gambit_discovery_err_network, addon.displayLabel())
    is AddonError.HttpStatus -> stringResource(R.string.gambit_discovery_err_status, addon.displayLabel(), code)
    is AddonError.MalformedJson -> stringResource(R.string.gambit_streams_err_unreadable, addon.displayLabel())
    is AddonError.MissingField -> stringResource(R.string.gambit_streams_err_unreadable, addon.displayLabel())
    is AddonError.NotFound -> stringResource(R.string.gambit_streams_err_not_found, addon.displayLabel())
    is AddonError.NoAddon -> stringResource(R.string.gambit_streams_err_no_addon)
    AddonError.InvalidUrl -> stringResource(R.string.gambit_streams_err_other)
}
