package tv.own.owntv.gambit.ui.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import tv.own.owntv.R
import tv.own.owntv.gambit.addons.model.AddonVideo
import tv.own.owntv.gambit.addons.net.AddonError
import tv.own.owntv.gambit.discovery.TitleFeed
import tv.own.owntv.gambit.discovery.TitleSeason
import tv.own.owntv.gambit.discovery.TitleSeasons
import tv.own.owntv.gambit.discovery.TitleState
import tv.own.owntv.gambit.ui.addons.displayLabel
import tv.own.owntv.gambit.ui.theme.GambitColors
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.format.localizedInteger
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.StageEpisode
import tv.own.owntv.ui.stage.StagePopup
import tv.own.owntv.ui.stage.StagePopupOption
import tv.own.owntv.ui.stage.StageRatingChip
import tv.own.owntv.ui.stage.StageTag
import tv.own.owntv.ui.stage.StageTitleArt
import tv.own.owntv.ui.stage.StageTool
import tv.own.owntv.ui.theme.LocalStagePalette
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/** Genres shown as tags; the rest are left off. */
private const val MAX_GENRES = 6

/**
 * The title page, over Discovery: the backdrop (or poster), logo or title, year, rating, runtime, genres
 * and description, a "Find streams" button (Stage 4 fills it in) and, for a series, a season drop-down
 * and its episodes. The catalogue item's own name and art show at once; the rest arrives with the
 * addon's details. [mainFocus] is the button that takes focus on opening, and from the rail.
 */
@Composable
internal fun GambitTitlePage(
    title: TitleState,
    mainFocus: FocusRequester,
    onRetry: () -> Unit,
    onSelectSeason: (Int) -> Unit,
    onChildFocused: () -> Unit,
    contentStart: Dp,
    modifier: Modifier = Modifier,
) {
    val preview = title.preview
    val feed = title.feed
    val loaded = feed as? TitleFeed.Loaded
    val detail = loaded?.detail
    val name = detail?.name?.takeIf { it.isNotBlank() } ?: preview.name.orEmpty()
    val backdrop = listOf(detail?.background, preview.background, detail?.poster, preview.poster).firstOrNull { !it.isNullOrBlank() }
    val logo = listOf(detail?.logo, preview.logo).firstOrNull { !it.isNullOrBlank() }
    val year = listOf(detail?.releaseInfo, preview.releaseInfo).firstOrNull { !it.isNullOrBlank() }
    val rating = listOf(detail?.imdbRating, preview.imdbRating).firstOrNull { !it.isNullOrBlank() }
    val runtime = detail?.runtime?.takeIf { it.isNotBlank() }
    val genres = (detail?.genres?.takeIf { it.isNotEmpty() } ?: preview.genres).filter { it.isNotBlank() }.take(MAX_GENRES)
    val description = listOf(detail?.description, preview.description).firstOrNull { !it.isNullOrBlank() }
    val page = LocalStagePalette.current.page

    var logoFailed by remember(logo) { mutableStateOf(false) }
    var streamsNote by remember(preview.id) { mutableStateOf(false) }
    var seasonMenu by remember(preview.id) { mutableStateOf(false) }
    val seasonFocus = remember { FocusRequester() }

    // Focus the main button when the page opens, and again when it changes between Try again and Find streams.
    LaunchedEffect(preview.id, feed is TitleFeed.Failed) {
        for (attempt in 0 until 10) {
            withFrameNanos { }
            if (runCatching { mainFocus.requestFocus() }.getOrDefault(false)) break
        }
    }

    Box(modifier.fillMaxSize().onFocusChanged { if (it.hasFocus) onChildFocused() }.focusGroup()) {
        if (backdrop != null) {
            AsyncImage(model = backdrop, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to page, 0.42f to page.copy(alpha = 0.88f), 1f to page.copy(alpha = 0.3f)),
                ),
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to page.copy(alpha = 0f), 1f to page)))
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = contentStart, top = 56.mpx, end = 64.mpx, bottom = 40.mpx),
        ) {
            if (logo != null && !logoFailed) {
                StageTitleArt(
                    url = logo,
                    contentDescription = name,
                    alignment = Alignment.BottomStart,
                    onFailed = { logoFailed = true },
                    modifier = Modifier.widthIn(max = 640.mpx).fillMaxWidth().height(150.mpx),
                )
            } else {
                Text(name, style = stageText(52, 800), color = GambitColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 1100.mpx))
            }
            Row(Modifier.padding(top = 18.mpx), horizontalArrangement = Arrangement.spacedBy(18.mpx), verticalAlignment = Alignment.CenterVertically) {
                if (year != null) Text(year, style = stageText(22, 600), color = GambitColors.Text)
                if (rating != null) StageRatingChip(rating)
                if (runtime != null) Text(runtime, style = stageText(22, 600), color = GambitColors.Muted)
            }
            if (genres.isNotEmpty()) {
                Row(Modifier.padding(top = 14.mpx), horizontalArrangement = Arrangement.spacedBy(10.mpx)) {
                    genres.forEach { StageTag(it) }
                }
            }
            if (description != null) {
                Text(
                    description, style = stageText(20, 500), color = GambitColors.Muted, maxLines = 5, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 18.mpx).widthIn(max = 1100.mpx),
                )
            }

            when (feed) {
                is TitleFeed.Failed -> {
                    Text(stringResource(R.string.gambit_title_failed_title), style = stageText(24, 600), color = GambitColors.Text, modifier = Modifier.padding(top = 26.mpx))
                    Text(feed.error.titleMessage(), style = stageText(19, 500), color = GambitColors.Muted, modifier = Modifier.padding(top = 8.mpx).widthIn(max = 1000.mpx))
                    Row(Modifier.padding(top = 20.mpx)) {
                        StageTool(stringResource(R.string.gambit_discovery_retry), onClick = onRetry, boxed = true, modifier = Modifier.focusRequester(mainFocus))
                    }
                }
                else -> {
                    Row(Modifier.padding(top = 26.mpx), horizontalArrangement = Arrangement.spacedBy(20.mpx), verticalAlignment = Alignment.CenterVertically) {
                        StageButton(
                            text = stringResource(R.string.gambit_title_find_streams),
                            onClick = { streamsNote = true },
                            icon = OwnTVIcon.PLAY,
                            iconFilled = true,
                            modifier = Modifier.focusRequester(mainFocus),
                        )
                        if (streamsNote) Text(stringResource(R.string.gambit_title_streams_soon), style = stageText(19, 500), color = GambitColors.Muted)
                    }
                    if (feed == TitleFeed.Loading) {
                        Text(stringResource(R.string.gambit_title_loading), style = stageText(20, 500), color = GambitColors.Dim, modifier = Modifier.padding(top = 22.mpx))
                    }
                }
            }

            if (loaded != null && loaded.seasons.isNotEmpty()) {
                val season = loaded.seasons[loaded.season]
                Row(Modifier.padding(top = 30.mpx)) {
                    StageTool(
                        text = stringResource(R.string.gambit_title_season), value = season.label(),
                        trailingIcon = OwnTVIcon.CHEVRON_DOWN, boxed = true, valueAccent = true,
                        onClick = { seasonMenu = true }, modifier = Modifier.focusRequester(seasonFocus),
                    )
                }
                Episodes(season, loaded.season)
            } else if (loaded != null && preview.type == tv.own.owntv.gambit.discovery.DiscoveryType.SERIES.id) {
                Text(stringResource(R.string.gambit_title_no_episodes), style = stageText(20, 500), color = GambitColors.Dim, modifier = Modifier.padding(top = 26.mpx))
            }
        }
    }

    if (seasonMenu && loaded != null) {
        val close = {
            seasonMenu = false
            runCatching { seasonFocus.requestFocus() }
            Unit
        }
        StagePopup(onDismiss = close, title = stringResource(R.string.gambit_title_season), width = 560.mpx) {
            loaded.seasons.forEachIndexed { index, s ->
                StagePopupOption(title = s.label(), chosen = index == loaded.season, onClick = { onSelectSeason(index); close() })
            }
        }
    }
}

@Composable
private fun Episodes(season: TitleSeason, seasonIndex: Int) {
    val listState = remember(seasonIndex) { androidx.compose.foundation.lazy.LazyListState() }
    val locale = LocalConfiguration.current.locales[0]
    val dates = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(24.mpx),
        contentPadding = PaddingValues(top = 20.mpx, bottom = 12.mpx, end = 40.mpx),
        modifier = Modifier.fillMaxWidth().focusRestorer().focusGroup(),
    ) {
        itemsIndexed(season.episodes, key = { _, v -> v.id }) { _, video ->
            StageEpisode(
                title = video.title?.takeIf { it.isNotBlank() } ?: video.episodeLabel(),
                line = TitleSeasons.airDate(video.released)?.format(dates),
                number = video.episode?.let { localizedInteger(it, grouping = false) }.orEmpty(),
                watched = false,
                progress = null,
                onClick = {},
                width = 336.mpx,
                height = 189.mpx,
            ) { filter ->
                Box(Modifier.fillMaxSize().background(GambitColors.Cell), contentAlignment = Alignment.Center) {
                    if (!video.thumbnail.isNullOrBlank()) {
                        AsyncImage(model = video.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, colorFilter = filter, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun AddonVideo.episodeLabel(): String =
    episode?.let { stringResource(R.string.gambit_title_episode_number, localizedInteger(it, grouping = false)) }.orEmpty()

@Composable
private fun TitleSeason.label(): String = when (number) {
    null -> stringResource(R.string.gambit_title_other_episodes)
    0 -> stringResource(R.string.gambit_title_specials)
    else -> stringResource(R.string.gambit_title_season_number, localizedInteger(number, grouping = false))
}

/** Each error type worded for the title page; the addon by its name and host only. */
@Composable
private fun AddonError.titleMessage(): String = when (this) {
    is AddonError.Timeout -> stringResource(R.string.gambit_discovery_err_timeout, addon.displayLabel())
    is AddonError.Network -> stringResource(R.string.gambit_discovery_err_network, addon.displayLabel())
    is AddonError.HttpStatus -> stringResource(R.string.gambit_discovery_err_status, addon.displayLabel(), code)
    is AddonError.MalformedJson -> stringResource(R.string.gambit_title_err_unreadable, addon.displayLabel())
    is AddonError.MissingField -> stringResource(R.string.gambit_title_err_unreadable, addon.displayLabel())
    is AddonError.NotFound -> stringResource(R.string.gambit_title_err_not_found, addon.displayLabel())
    is AddonError.NoAddon -> stringResource(R.string.gambit_title_err_no_addon)
    AddonError.InvalidUrl -> stringResource(R.string.gambit_title_err_other)
}
