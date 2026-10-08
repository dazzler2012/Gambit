package tv.own.owntv.gambit.ui.guide

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.features.epg.GuideGridDefaults
import tv.own.owntv.features.home.durationText
import tv.own.owntv.gambit.ui.theme.GambitColors
import tv.own.owntv.gambit.ui.theme.GambitRadii
import tv.own.owntv.gambit.ui.theme.gambitAccentLight
import tv.own.owntv.player.LivePreviewEngine
import tv.own.owntv.ui.components.ChannelLogoTile
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVSpinner
import tv.own.owntv.ui.components.drawStageGlyph
import tv.own.owntv.ui.format.rememberSystemTimeFormatter
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.mpxSp
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText
import java.util.Date

/*
 * The TV Guide in Gambit's look. Drop-in replacements for the drawing functions in GuideStage and
 * GuideCore: EpgScreen keeps every bit of state, focus and key handling, and calls these instead.
 * Layout: preview video top left, the programme beside it; a ruler led by the date and time; channel
 * number, logo and name; flat grey programme cells with the title only.
 */

private const val Tabular = "tnum"

/** "21:00 — 22:00". */
@Composable
private fun timeRange(formatTime: (Long) -> String, start: Long, stop: Long) = stringResource(R.string.gambit_time_range, formatTime(start), formatTime(stop))

/** The top-left preview: plain black, the logo until the picture starts, no badges or captions. */
@Composable
internal fun GambitGuideVideo(
    channel: ChannelEntity?,
    previewEngine: LivePreviewEngine,
    showVideo: Boolean,
    modifier: Modifier = Modifier,
) {
    val previewState by previewEngine.state.collectAsStateWithLifecycle()
    val playing = showVideo && previewState != LivePreviewEngine.State.ERROR && previewState != LivePreviewEngine.State.IDLE
    Box(
        modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(GambitRadii.Video)).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (channel != null && !playing) {
            ChannelLogoTile(logoUrl = channel.displayLogoUrl, modifier = Modifier.size(180.mpx), fill = Color.Transparent) {
                OwnTVIcon(OwnTVIcon.LIVE_TV, tint = GambitColors.Dim, modifier = Modifier.size(48.mpx))
            }
        }
        if (playing) tv.own.owntv.player.ExoPreviewSurface(engine = previewEngine, modifier = Modifier.fillMaxSize())
        if (showVideo && previewState == LivePreviewEngine.State.LOADING) OwnTVSpinner(sizeDp = 24)
    }
}

/**
 * The programme beside the video: the title, "21:00 — 22:00 ▬▬▬ 2 min" (the bar and minutes left only
 * while it is on), then the description. With no programme, the channel's name and "No information".
 */
@Composable
internal fun GambitProgrammeBlock(
    programme: EpgProgrammeEntity?,
    fallbackTitle: String,
    now: Long,
    modifier: Modifier = Modifier,
) {
    val formatTime = rememberSystemTimeFormatter()
    Column(modifier) {
        Text(
            programme?.title ?: fallbackTitle, style = stageText(44, 700), color = GambitColors.Text,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (programme == null) {
            Text(
                stringResource(R.string.gambit_epg_no_information), style = stageText(26, 400), color = GambitColors.Muted,
                maxLines = 1, modifier = Modifier.padding(top = 14.mpx),
            )
            return@Column
        }
        Row(
            Modifier.padding(top = 14.mpx),
            horizontalArrangement = Arrangement.spacedBy(24.mpx),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                timeRange(formatTime, programme.startMs, programme.stopMs),
                style = stageText(26, 400).copy(fontFeatureSettings = Tabular), color = GambitColors.Muted, maxLines = 1,
            )
            when {
                now in programme.startMs until programme.stopMs -> {
                    val progress = ((now - programme.startMs).toFloat() / (programme.stopMs - programme.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                    Box(
                        Modifier.width(80.mpx).height(5.mpx).drawBehind {
                            val r = CornerRadius(size.height / 2f)
                            drawRoundRect(GambitColors.ProgressTrack, cornerRadius = r)
                            drawRoundRect(GambitColors.Text, size = Size(size.width * progress, size.height), cornerRadius = r)
                        },
                    )
                    val left = ((programme.stopMs - now) + 59_999) / 60_000
                    Text(stringResource(R.string.player_duration_minutes, left.toInt()), style = stageText(26, 400), color = GambitColors.Muted, maxLines = 1)
                }
                programme.startMs > now -> Text(
                    stringResource(R.string.content_epg_starts_in, durationText(((programme.startMs - now) + 59_999) / 60_000)),
                    style = stageText(26, 400), color = GambitColors.Muted, maxLines = 1,
                )
            }
        }
        programme.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                it, style = stageText(24, 400).copy(lineHeight = (24 * 1.35f).mpxSp),
                color = GambitColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 14.mpx).widthIn(max = 1100.mpx),
            )
        }
    }
}

/**
 * The line above the grid: the date and time ("Tue, 6 Oct, 21:58") in the light accent over the channel
 * column, then a time every half hour across the timeline, a thin divider and the accent dot on now.
 */
@Composable
internal fun GambitGuideRuler(
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    scrollPx: Int,
    labelWidth: Dp,
    timelineWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val formatTime = rememberSystemTimeFormatter()
    val light = gambitAccentLight(stageAccent.accent)
    val locale = LocalConfiguration.current.locales[0]
    val datePattern = remember(locale) { DateFormat.getBestDateTimePattern(locale, "EEEdMMM") }
    val dateLabel = stringResource(R.string.gambit_date_time, DateFormat.format(datePattern, Date(now)), formatTime(now))
    val slotMs = GuideGridDefaults.SlotMin * 60_000L
    val first = windowStart + (slotMs - windowStart % slotMs) % slotMs
    val times = generateSequence(first) { it + slotMs }.takeWhile { it < windowEnd }.toList()
    val nowIn = now in windowStart..windowEnd
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            dateLabel, style = stageText(26, 500).copy(fontFeatureSettings = Tabular), color = light,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(labelWidth).padding(start = 8.mpx),
        )
        Layout(
            modifier = Modifier.width(timelineWidth).fillMaxHeight().drawBehind {
                val px = 1.mpx.toPx()
                drawRect(GambitColors.Divider, Offset(0f, size.height - 2 * px), Size(size.width, 2 * px))
                if (nowIn) {
                    val x = (now - windowStart) / 60_000f * GuideGridDefaults.PxPerMin.toPx() - scrollPx
                    if (x in 0f..size.width) drawCircle(light, 5 * px, Offset(x, size.height - px))
                }
            },
            content = {
                times.forEach { t ->
                    Text(formatTime(t), style = stageText(24, 400).copy(fontFeatureSettings = Tabular), color = GambitColors.Muted, maxLines = 1)
                }
            },
        ) { measurables, constraints ->
            val ppm = GuideGridDefaults.PxPerMin.toPx()
            val placeables = measurables.map { it.measure(Constraints()) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEachIndexed { i, pl ->
                    // Each label is centred on its half hour, as the cells below begin there.
                    val x = ((times[i] - windowStart) / 60_000f * ppm - scrollPx - pl.width / 2f).toInt()
                    if (x < 0 || x + pl.width > constraints.maxWidth) return@forEachIndexed
                    pl.place(IntOffset(x, (constraints.maxHeight - pl.height) / 2 - 4.mpx.roundToPx()))
                }
            }
        }
    }
}

/** The thin grey now-line over the rows (its dot sits on the ruler). */
internal fun Modifier.gambitNowLine(): Modifier = drawBehind {
    val w = 2 * 1.mpx.toPx()
    drawRect(GambitColors.NowLine, Offset((size.width - w) / 2f, 0f), Size(w, size.height))
}

/** A channel's label: the playlist dot (several playlists only), the number, the logo as it is, the name in bold. */
@Composable
internal fun GambitChannelLabel(channel: ChannelEntity, name: String, dot: Color?, numberWidth: Dp, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 8.mpx), horizontalArrangement = Arrangement.spacedBy(10.mpx), verticalAlignment = Alignment.CenterVertically) {
        if (dot != null) Box(Modifier.size(7.mpx).background(dot, RoundedCornerShape(50)))
        Text(
            channel.number?.toString().orEmpty(),
            style = stageText(20, 400).copy(fontFeatureSettings = Tabular), color = GambitColors.Text,
            // EpgScreen sizes [numberWidth] for its 17 px digits; these are 20.
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(numberWidth * 1.2f),
        )
        ChannelLogoTile(logoUrl = channel.displayLogoUrl, modifier = Modifier.size(64.mpx, 40.mpx), fill = Color.Transparent) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { OwnTVIcon(OwnTVIcon.LIVE_TV, tint = GambitColors.Dim, modifier = Modifier.size(22.mpx)) }
        }
        Text(name, style = stageText(22, 700), color = GambitColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/**
 * One channel's programmes as flat grey cells with the title only. [focusTime] marks the focused cell
 * (the row has been entered); [rowCursor] marks the cell under the cursor while the row is only
 * selected. Recording, reminder and catch-up icons sit before the title. Empty stretches read
 * "No information". One Canvas per row, as in GuideCore, so a week of guide scrolls light.
 */
@Composable
internal fun GambitProgrammeStrip(
    programmes: List<EpgProgrammeEntity>,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    focusTime: Long?,
    rowCursor: Long?,
    catchupIds: Set<Long>,
    recordingStarts: Set<Long>,
    reminderStarts: Set<Long>,
    scrollPx: Int,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val px = with(density) { 1.mpx.toPx() }
    val pxPerMin = with(density) { GuideGridDefaults.PxPerMin.toPx() }
    val titleStyle = stageText(24, 400).copy(textDirection = TextDirection.Content)
    val emptyText = stringResource(R.string.gambit_epg_no_information)
    val accentLight = gambitAccentLight(stageAccent.accent)
    val recRed = Color(0xFFFF5B5B)
    Canvas(Modifier.fillMaxSize()) { clipRect {
        val viewW = size.width
        val h = size.height
        val r = CornerRadius(GambitRadii.Cell.toPx())
        val gap = 3 * px
        fun cellColor(focused: Boolean, cursor: Boolean, isNow: Boolean) = when {
            focused -> GambitColors.CellFocused
            cursor -> GambitColors.CellRow
            isNow -> GambitColors.CellNow
            else -> GambitColors.Cell
        }
        fun x(ms: Long) = ((ms - windowStart) / 60_000f) * pxPerMin - scrollPx
        fun label(text: String, color: Color, left: Float, right: Float) {
            val w = (right - left - 32 * px).toInt()
            if (w <= 4) return
            val t = measurer.measure(text, titleStyle.copy(color = color), overflow = TextOverflow.Ellipsis, maxLines = 1, softWrap = false, constraints = Constraints(maxWidth = w))
            drawText(t, topLeft = Offset(left + 16 * px, (h - t.size.height) / 2f))
        }
        // An empty stretch is a cell of its own, so it can be focused like any programme.
        fun emptyCell(from: Long, to: Long) {
            val x0 = (x(from) + gap).coerceAtLeast(0f)
            val x1 = (x(to) - gap).coerceAtMost(viewW)
            if (x1 <= x0) return
            val focused = focusTime != null && focusTime in from until to
            val cursor = rowCursor != null && rowCursor in from until to
            drawRoundRect(cellColor(focused, cursor, false), Offset(x0, 0f), Size(x1 - x0, h), r)
            label(emptyText, if (focused || cursor) GambitColors.Text else GambitColors.Dim, x0, x1)
        }
        var shownUntil = windowStart
        programmes.forEach { p ->
            val s0 = p.startMs.coerceIn(windowStart, windowEnd).coerceAtLeast(shownUntil)
            val e0 = p.stopMs.coerceIn(windowStart, windowEnd)
            if (e0 <= s0) return@forEach
            if (s0 > shownUntil) emptyCell(shownUntil, s0)
            shownUntil = e0
            val x0 = x(s0) + gap
            val x1 = x(e0) - gap
            if (x1 <= x0 || x1 <= 0f || x0 >= viewW) return@forEach
            val focused = focusTime != null && focusTime in p.startMs until p.stopMs
            val cursor = !focused && rowCursor != null && rowCursor in p.startMs until p.stopMs
            drawRoundRect(cellColor(focused, cursor, now in p.startMs until p.stopMs), Offset(x0, 0f), Size(x1 - x0, h), r)
            // Text starts at the visible part of a cell that began before the left edge.
            var tx = maxOf(x0, 0f) + 16 * px
            val icon = 18 * px
            fun glyph(g: OwnTVIcon, c: Color, filled: Boolean = false) {
                if (tx + icon > x1 - 16 * px) return
                drawStageGlyph(g, c, Offset(tx, (h - icon) / 2f), icon, filled)
                tx += icon + 8 * px
            }
            if (p.startMs in recordingStarts) glyph(OwnTVIcon.REC, recRed, filled = true)
            if (p.startMs in reminderStarts) glyph(OwnTVIcon.BELL, accentLight)
            if (p.id in catchupIds) glyph(OwnTVIcon.REWIND, accentLight)
            label(p.title, GambitColors.Text, tx - 16 * px, x1)
        }
        if (shownUntil < windowEnd) emptyCell(shownUntil, windowEnd)
    } }
}
