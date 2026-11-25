@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package tv.own.owntv.gambit.ui.discovery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.gambit.addons.net.AddonError
import tv.own.owntv.gambit.discovery.DiscoveryCatalog
import tv.own.owntv.gambit.discovery.DiscoveryFeed
import tv.own.owntv.gambit.discovery.DiscoveryState
import tv.own.owntv.gambit.discovery.DiscoveryType
import tv.own.owntv.gambit.discovery.TraktAccount
import tv.own.owntv.gambit.discovery.TraktDiscoveryState
import tv.own.owntv.gambit.discovery.TraktListChoice
import tv.own.owntv.gambit.discovery.TraktListFeed
import tv.own.owntv.gambit.discovery.TraktListSource
import tv.own.owntv.gambit.discovery.TraktListState
import tv.own.owntv.gambit.ui.addons.displayLabel
import tv.own.owntv.gambit.ui.trakt.messageRes
import tv.own.owntv.gambit.ui.theme.GambitColors
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StagePopup
import tv.own.owntv.ui.stage.StagePopupOption
import tv.own.owntv.ui.stage.StagePoster
import tv.own.owntv.ui.stage.StageTool
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/** Which drop-down is open over the page. [TRAKT_LIST] is the second drop-down while on Trakt. */
private enum class DiscoveryMenu { TYPE, CATALOG, GENRE, TRAKT_LIST }

/** Ask for the next page once focus is this close to the end of what is loaded. */
private const val LOAD_MORE_AHEAD = 12

/** The grid key of the "loading more / couldn't load more" row; posters are keyed by their String id. */
private const val MORE_ROW_KEY = -1

/** Room, in design pixels, the grid leaves beyond the focused poster when it has to scroll. */
private const val SCROLL_MARGIN_MPX = 80

/** How far into that room the focused poster may be before the grid moves: the focus zoom shifts it about 11. */
private const val SCROLL_SLACK_MPX = 16

/**
 * How far a poster grid scrolls for the focused item (positive is down), all in pixels: [offset] is the
 * item's top in the grid's window, [size] its height. Nothing while it sits no more than [slack] into the
 * [margin] at either edge; otherwise just far enough to leave the whole [margin] on the side it left by.
 * A window too small for both margins centres the item; an item taller than the window aligns its top.
 */
internal fun discoveryScrollDistance(offset: Float, size: Float, containerSize: Float, margin: Float, slack: Float): Float {
    if (size > containerSize) return offset
    val room = margin.coerceAtMost((containerSize - size) / 2f)
    val allowed = slack.coerceAtMost(room)
    val lead = offset - room
    val trailing = offset + size + room - containerSize
    return when {
        lead < -allowed -> lead
        trailing > allowed -> trailing
        else -> 0f
    }
}

/**
 * Discovery, in the rail's Sports slot: a Type drop-down (Movies / Series), the addons' catalogues for it
 * (and a Genre drop-down when the catalogue has genres), and its titles as a poster grid that loads more
 * as focus nears the end. Nothing shows, and nothing is requested, until the profile is known to be an
 * adult one. Errors are worded here from their type; no addon address is ever shown.
 */
@Composable
fun GambitDiscoveryPage(
    onChildFocused: () -> Unit,
    onEntryHook: (((() -> Boolean)?) -> Unit)?,
    contentStart: Dp,
    modifier: Modifier = Modifier,
    /** Opens the full-screen player once a stream has started (the shell owns that). */
    onPlay: () -> Unit = {},
    /** The shell asks for focus back after the player closes; the stream list takes it itself. */
    restoreFocus: Boolean = false,
    onRestored: () -> Unit = {},
    vm: DiscoveryViewModel = koinViewModel(),
) {
    val access by vm.addonAccess.collectAsStateWithLifecycle()
    if (!access.isOpen) return
    DiscoveryContent(vm, onChildFocused, onEntryHook, contentStart, modifier, onPlay, restoreFocus, onRestored)
}

@Composable
private fun DiscoveryContent(
    vm: DiscoveryViewModel,
    onChildFocused: () -> Unit,
    onEntryHook: (((() -> Boolean)?) -> Unit)?,
    contentStart: Dp,
    modifier: Modifier,
    onPlay: () -> Unit,
    restoreFocus: Boolean,
    onRestored: () -> Unit,
) {
    // Back from the player: the stream list focuses the stream that was playing (see GambitStreamPage).
    LaunchedEffect(restoreFocus) { if (restoreFocus) onRestored() }
    // Only composed once the profile is known to be an adult one: each visit fetches fresh lists.
    LaunchedEffect(Unit) { vm.open() }
    val state by vm.state.collectAsStateWithLifecycle()
    val title by vm.title.collectAsStateWithLifecycle()
    val streams by vm.streams.collectAsStateWithLifecycle()
    val playNote by vm.playNote.collectAsStateWithLifecycle()
    val trakt by vm.trakt.collectAsStateWithLifecycle()
    val traktList by vm.traktList.collectAsStateWithLifecycle()
    val traktShown by vm.traktShown.collectAsStateWithLifecycle()
    val onTrakt = trakt.active
    val access by vm.addonAccess.collectAsStateWithLifecycle()
    val searchOpen by vm.searchOpen.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val searchShown = searchAllowed(access)

    val typeFocus = remember { FocusRequester() }
    val catalogFocus = remember { FocusRequester() }
    val genreFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val titleFocus = remember { FocusRequester() }
    // Kept here, not in the grid, so the grid comes back where it was after the title page closes.
    val gridState = rememberLazyGridState()
    val posterFocus = remember { HashMap<String, FocusRequester>() }
    var lastPoster by remember { mutableStateOf<String?>(null) }
    val streamFocus = remember { FocusRequester() }
    // The title page's episode cards and rows, kept here so Back from the stream list finds them again.
    val episodeFocus = remember { HashMap<String, FocusRequester>() }
    val episodeRows = remember { HashMap<Int, LazyListState>() }
    val episodeFocusFor: (String) -> FocusRequester = remember { { id -> episodeFocus.getOrPut(id) { FocusRequester() } } }
    val episodeRowFor: (Int) -> LazyListState = remember { { season -> episodeRows.getOrPut(season) { LazyListState() } } }
    val searchButtonFocus = remember { FocusRequester() }
    val searchFieldFocus = remember { FocusRequester() }
    // Kept here, so the results come back where they were after a title page; new text starts at the top.
    val searchList = remember(search.query.trim()) { LazyListState() }
    val searchRows = remember(search.query.trim()) { HashMap<String, LazyListState>() }
    // Where focus goes back to when the stream list closes: an episode id, or null for "Find streams".
    var streamsFrom by remember { mutableStateOf<String?>(null) }
    var titleFocusOnOpen by remember { mutableStateOf(true) }
    // A new catalogue, genre or type starts at the top.
    LaunchedEffect(state.type, state.selected?.key, state.genre, onTrakt, trakt.selected) { gridState.scrollToItem(0) }
    var menu by remember { mutableStateOf<DiscoveryMenu?>(null) }
    // After a drop-down closes, focus goes back to the tool that opened it.
    var refocus by remember { mutableStateOf<FocusRequester?>(null) }
    var refocusTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(refocusTick) {
        val target = refocus ?: return@LaunchedEffect
        for (attempt in 0 until 10) {
            withFrameNanos { }
            if (runCatching { target.requestFocus() }.getOrDefault(false)) break
        }
        refocus = null
    }
    val closeMenu: (FocusRequester) -> Unit = { f -> menu = null; refocus = f; refocusTick++ }
    val openTitle: (tv.own.owntv.gambit.addons.model.MetaPreview) -> Unit = { meta ->
        menu = null
        lastPoster = meta.id
        episodeFocus.clear()
        episodeRows.clear()
        titleFocusOnOpen = true
        vm.openTitle(meta)
    }
    // Back from the stream list: the title page again, focus on what opened the list.
    val closeStreams: () -> Unit = {
        vm.closeStreams()
        titleFocusOnOpen = false
        refocus = streamsFrom?.let { episodeFocus[it] } ?: titleFocus
        refocusTick++
    }
    // Back from the title page: Discovery again, focus on the poster that opened it. Over search, the
    // search page comes back and focuses its own result.
    val closeTitle: () -> Unit = {
        vm.closeTitle()
        if (!vm.searchOpen.value) {
            refocus = lastPoster?.let { posterFocus[it] } ?: gridFocus
            refocusTick++
        }
    }
    // Back from search: the grid again, focus on the Search button.
    val closeSearch: () -> Unit = {
        vm.closeSearch()
        refocus = searchButtonFocus
        refocusTick++
    }

    // ▶ from the rail: the poster last focused (or the first), else Try again, else the Type drop-down.
    val enter: () -> Boolean = {
        val feed = vm.state.value.feed
        val tryFocus: (FocusRequester) -> Boolean = { f -> runCatching { f.requestFocus() }.getOrDefault(false) }
        if (vm.streams.value != null) tryFocus(streamFocus) || tryFocus(titleFocus)
        else if (vm.title.value != null) tryFocus(titleFocus)
        else if (vm.searchOpen.value) tryFocus(searchFieldFocus)
        else if (vm.trakt.value.active) when {
            vm.traktList.value.feed is TraktListFeed.Loaded -> tryFocus(gridFocus) || tryFocus(typeFocus)
            else -> tryFocus(retryFocus) || tryFocus(typeFocus)
        } else when (feed) {
            is DiscoveryFeed.Loaded -> tryFocus(gridFocus) || tryFocus(typeFocus)
            is DiscoveryFeed.Failed -> tryFocus(retryFocus) || tryFocus(typeFocus)
            else -> tryFocus(typeFocus)
        }
    }
    DisposableEffect(onEntryHook) {
        onEntryHook?.invoke(enter)
        onDispose { onEntryHook?.invoke(null) }
    }

    val list = streams
    if (list != null) {
        BackHandler(onBack = closeStreams)
        GambitStreamPage(
            list = list,
            mainFocus = streamFocus,
            playNote = playNote,
            initialKey = vm.lastPlayedKey,
            onPlay = { key -> vm.playStream(key, onPlay) },
            onRetry = vm::retryStreams,
            onChildFocused = onChildFocused,
            contentStart = contentStart,
            modifier = modifier,
        )
        return
    }

    val open = title
    if (open != null) {
        BackHandler(onBack = closeTitle)
        GambitTitlePage(
            title = open,
            mainFocus = titleFocus,
            onRetry = vm::retryTitle,
            onSelectSeason = vm::selectSeason,
            onFindStreams = { streamsFrom = null; vm.findStreams() },
            onOpenEpisode = { video -> streamsFrom = video.id; vm.findEpisodeStreams(video) },
            episodeFocus = episodeFocusFor,
            episodeRow = episodeRowFor,
            focusOnOpen = titleFocusOnOpen,
            onChildFocused = onChildFocused,
            contentStart = contentStart,
            modifier = modifier,
        )
        return
    }

    // In place of the TV default, which pins the focused poster a third down on every move.
    val scrollSpec = rememberDiscoveryScrollSpec()

    if (searchOpen && searchShown) {
        BackHandler(onBack = closeSearch)
        CompositionLocalProvider(LocalBringIntoViewSpec provides scrollSpec) {
            GambitSearchPage(
                search = search,
                fieldFocus = searchFieldFocus,
                listState = searchList,
                rowStates = searchRows,
                initialResult = vm.lastSearchResult,
                onType = vm::typeSearch,
                onSubmit = vm::submitSearch,
                onVoice = vm::voiceSearch,
                onRetry = vm::retrySearch,
                onOpen = { rowKey, id ->
                    if (vm.openSearchResult(rowKey, id)) {
                        episodeFocus.clear()
                        episodeRows.clear()
                        titleFocusOnOpen = true
                    }
                },
                onChildFocused = onChildFocused,
                contentStart = contentStart,
                modifier = modifier,
            )
        }
        return
    }

    Column(
        modifier
            .padding(start = contentStart, top = 48.mpx, end = 64.mpx)
            .onFocusChanged { if (it.hasFocus) onChildFocused() }
            .focusGroup(),
    ) {
        Text(stringResource(R.string.gambit_discovery_title), style = stageText(46, 800), color = GambitColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 18.mpx), horizontalArrangement = Arrangement.spacedBy(14.mpx)) {
            StageTool(
                text = stringResource(R.string.gambit_discovery_type), value = (if (onTrakt) DiscoveryType.TRAKT else state.type).label(),
                trailingIcon = OwnTVIcon.CHEVRON_DOWN, boxed = true, valueAccent = true,
                onClick = { menu = DiscoveryMenu.TYPE }, modifier = Modifier.focusRequester(typeFocus),
            )
            val selected = state.selected
            val traktChoice = trakt.choices.firstOrNull { it.source == trakt.selected }
            if (onTrakt) {
                // Trakt: the list drop-down only, never a genre.
                if (trakt.account == TraktAccount.SignedIn && traktChoice != null) {
                    StageTool(
                        text = stringResource(R.string.gambit_discovery_trakt_list), value = traktChoice.label(),
                        trailingIcon = OwnTVIcon.CHEVRON_DOWN, boxed = true, valueAccent = true,
                        onClick = { menu = DiscoveryMenu.TRAKT_LIST },
                        modifier = Modifier.focusRequester(catalogFocus).widthIn(max = 640.mpx),
                    )
                }
            } else if (state.catalogs.isNotEmpty() && selected != null) {
                StageTool(
                    text = stringResource(R.string.gambit_discovery_catalog), value = selected.label(),
                    trailingIcon = OwnTVIcon.CHEVRON_DOWN, boxed = true, valueAccent = true,
                    onClick = { menu = DiscoveryMenu.CATALOG },
                    modifier = Modifier.focusRequester(catalogFocus).widthIn(max = 640.mpx),
                )
                if (selected.genres.isNotEmpty()) {
                    StageTool(
                        text = stringResource(R.string.gambit_discovery_genre),
                        value = state.genre ?: stringResource(R.string.gambit_discovery_genre_all),
                        trailingIcon = OwnTVIcon.CHEVRON_DOWN, boxed = true, valueAccent = true,
                        onClick = { menu = DiscoveryMenu.GENRE },
                        modifier = Modifier.focusRequester(genreFocus).widthIn(max = 420.mpx),
                    )
                }
            }
            if (searchShown) {
                StageTool(
                    text = stringResource(R.string.gambit_search_title), icon = OwnTVIcon.SEARCH, boxed = true,
                    onClick = { menu = null; vm.openSearch() },
                    modifier = Modifier.focusRequester(searchButtonFocus),
                )
            }
        }
        Column(Modifier.padding(top = 22.mpx).fillMaxSize()) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides scrollSpec) {
                if (onTrakt) TraktBody(trakt, traktList, vm, gridState, gridFocus, retryFocus, posterFocus, openTitle)
                else DiscoveryBody(state, vm, gridState, gridFocus, retryFocus, posterFocus, openTitle)
            }
        }
    }

    when (menu) {
        DiscoveryMenu.TYPE -> StagePopup(onDismiss = { closeMenu(typeFocus) }, title = stringResource(R.string.gambit_discovery_type), width = 560.mpx) {
            val types = if (traktShown) state.types + DiscoveryType.TRAKT else state.types // Gambit: Trakt last, adults only
            types.forEach { t ->
                val chosen = if (onTrakt) t == DiscoveryType.TRAKT else t == state.type
                StagePopupOption(title = t.label(), chosen = chosen, onClick = { vm.selectType(t); closeMenu(typeFocus) })
            }
        }
        DiscoveryMenu.TRAKT_LIST -> StagePopup(onDismiss = { closeMenu(catalogFocus) }, title = stringResource(R.string.gambit_discovery_trakt_list), width = 760.mpx) {
            trakt.choices.forEach { c ->
                StagePopupOption(title = c.label(), chosen = c.source == trakt.selected, onClick = { vm.selectTraktList(c.source); closeMenu(catalogFocus) })
            }
        }
        DiscoveryMenu.CATALOG -> StagePopup(onDismiss = { closeMenu(catalogFocus) }, title = stringResource(R.string.gambit_discovery_catalog), width = 760.mpx) {
            state.catalogs.forEach { c ->
                StagePopupOption(title = c.label(), chosen = c.key == state.selected?.key, onClick = { vm.selectCatalog(c.key); closeMenu(catalogFocus) })
            }
        }
        DiscoveryMenu.GENRE -> StagePopup(onDismiss = { closeMenu(genreFocus) }, title = stringResource(R.string.gambit_discovery_genre), width = 560.mpx) {
            if (state.selected?.genreRequired != true) { // Gambit: no "All" when the addon needs a genre
                StagePopupOption(title = stringResource(R.string.gambit_discovery_genre_all), chosen = state.genre == null, onClick = { vm.selectGenre(null); closeMenu(genreFocus) })
            }
            state.selected?.genres.orEmpty().forEach { g ->
                StagePopupOption(title = g, chosen = g == state.genre, onClick = { vm.selectGenre(g); closeMenu(genreFocus) })
            }
        }
        null -> Unit
    }
}

/** Scrolls only as far as the focused poster needs (see [discoveryScrollDistance]). */
@Composable
private fun rememberDiscoveryScrollSpec(): BringIntoViewSpec {
    val density = LocalDensity.current
    return remember(density) {
        val margin = with(density) { SCROLL_MARGIN_MPX.mpx.toPx() }
        val slack = with(density) { SCROLL_SLACK_MPX.mpx.toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                discoveryScrollDistance(offset, size, containerSize, margin, slack)
        }
    }
}

@Composable
private fun DiscoveryBody(
    state: DiscoveryState,
    vm: DiscoveryViewModel,
    gridState: LazyGridState,
    gridFocus: FocusRequester,
    retryFocus: FocusRequester,
    posterFocus: MutableMap<String, FocusRequester>,
    onOpen: (tv.own.owntv.gambit.addons.model.MetaPreview) -> Unit,
) {
    when (val feed = state.feed) {
        DiscoveryFeed.Idle, DiscoveryFeed.Loading -> Message(stringResource(R.string.gambit_discovery_loading))
        DiscoveryFeed.Empty -> if (state.catalogs.isEmpty()) {
            Message(
                stringResource(R.string.gambit_discovery_no_catalogs_title),
                when (state.type) {
                    DiscoveryType.MOVIE -> stringResource(R.string.gambit_discovery_no_catalogs_movies)
                    DiscoveryType.SERIES -> stringResource(R.string.gambit_discovery_no_catalogs_series)
                    else -> stringResource(R.string.gambit_discovery_no_catalogs_other, state.type.readableName)
                },
            )
        } else {
            Message(stringResource(R.string.gambit_discovery_empty))
        }
        is DiscoveryFeed.Failed -> {
            Message(stringResource(R.string.gambit_discovery_failed_title), feed.error.message())
            Row(Modifier.padding(top = 20.mpx)) {
                StageTool(stringResource(R.string.gambit_discovery_retry), onClick = vm::retry, boxed = true, modifier = Modifier.focusRequester(retryFocus))
            }
        }
        is DiscoveryFeed.Loaded -> {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.FixedSize(196.mpx),
                horizontalArrangement = Arrangement.spacedBy(26.mpx),
                verticalArrangement = Arrangement.spacedBy(30.mpx),
                contentPadding = PaddingValues(top = 14.mpx, bottom = 24.mpx),
                modifier = Modifier.fillMaxSize().focusRequester(gridFocus).focusRestorer().focusGroup(),
            ) {
                itemsIndexed(feed.items, key = { _, m -> m.id }) { index, meta ->
                    val posterRequester = remember(meta.id) { posterFocus.getOrPut(meta.id) { FocusRequester() } }
                    StagePoster(
                        title = meta.name.orEmpty(),
                        onClick = { onOpen(meta) },
                        width = 196.mpx,
                        height = 294.mpx,
                        modifier = Modifier.focusRequester(posterRequester).onFocusChanged {
                            if (it.isFocused && index >= feed.items.size - LOAD_MORE_AHEAD &&
                                feed.canLoadMore && !feed.loadingMore && feed.moreError == null
                            ) vm.loadMore()
                        },
                    ) {
                        if (!meta.poster.isNullOrBlank()) {
                            AsyncImage(model = meta.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
                if (feed.loadingMore || feed.moreError != null) {
                    item(key = MORE_ROW_KEY, span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth().padding(top = 6.mpx)) {
                            if (feed.loadingMore) {
                                Text(stringResource(R.string.gambit_discovery_loading_more), style = stageText(20, 500), color = GambitColors.Dim)
                            } else {
                                Text(stringResource(R.string.gambit_discovery_more_failed), style = stageText(20, 600), color = GambitColors.Text)
                                feed.moreError?.let { Text(it.message(), style = stageText(18, 500), color = GambitColors.Muted, modifier = Modifier.padding(top = 4.mpx)) }
                                Row(Modifier.padding(top = 14.mpx)) {
                                    StageTool(stringResource(R.string.gambit_discovery_retry), onClick = vm::loadMore, boxed = true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Trakt in Discovery: the sign-in note, or the chosen list as a poster grid (read in full, no paging on focus). */
@Composable
private fun TraktBody(
    trakt: TraktDiscoveryState,
    list: TraktListState,
    vm: DiscoveryViewModel,
    gridState: LazyGridState,
    gridFocus: FocusRequester,
    retryFocus: FocusRequester,
    posterFocus: MutableMap<String, FocusRequester>,
    onOpen: (tv.own.owntv.gambit.addons.model.MetaPreview) -> Unit,
) {
    when (val account = trakt.account) {
        TraktAccount.Checking -> Message(stringResource(R.string.gambit_discovery_loading))
        TraktAccount.SignedOut -> Message(stringResource(R.string.gambit_discovery_trakt_signed_out_title), stringResource(R.string.gambit_discovery_trakt_signed_out))
        is TraktAccount.Failed -> {
            Message(stringResource(R.string.gambit_discovery_trakt_failed_title), stringResource(account.error.messageRes()))
            Row(Modifier.padding(top = 20.mpx)) {
                StageTool(stringResource(R.string.gambit_discovery_retry), onClick = vm::retryTrakt, boxed = true, modifier = Modifier.focusRequester(retryFocus))
            }
        }
        TraktAccount.SignedIn -> Column {
            if (trakt.listsError != null) {
                Text(stringResource(R.string.gambit_discovery_trakt_lists_failed), style = stageText(18, 500), color = GambitColors.Muted, modifier = Modifier.padding(bottom = 6.mpx))
            }
            TraktFeed(list.feed, vm, gridState, gridFocus, retryFocus, posterFocus, onOpen)
        }
    }
}

@Composable
private fun TraktFeed(
    feed: TraktListFeed,
    vm: DiscoveryViewModel,
    gridState: LazyGridState,
    gridFocus: FocusRequester,
    retryFocus: FocusRequester,
    posterFocus: MutableMap<String, FocusRequester>,
    onOpen: (tv.own.owntv.gambit.addons.model.MetaPreview) -> Unit,
) {
    when (feed) {
        TraktListFeed.Idle, TraktListFeed.Loading -> Message(stringResource(R.string.gambit_discovery_loading))
        TraktListFeed.Empty -> Message(stringResource(R.string.gambit_discovery_trakt_empty))
        is TraktListFeed.Failed -> {
            Message(stringResource(R.string.gambit_discovery_trakt_failed_title), stringResource(feed.error.messageRes()))
            Row(Modifier.padding(top = 20.mpx)) {
                StageTool(stringResource(R.string.gambit_discovery_retry), onClick = vm::retryTraktList, boxed = true, modifier = Modifier.focusRequester(retryFocus))
            }
        }
        is TraktListFeed.Loaded -> LazyVerticalGrid(
            state = gridState,
            columns = GridCells.FixedSize(196.mpx),
            horizontalArrangement = Arrangement.spacedBy(26.mpx),
            verticalArrangement = Arrangement.spacedBy(30.mpx),
            contentPadding = PaddingValues(top = 14.mpx, bottom = 24.mpx),
            modifier = Modifier.fillMaxSize().focusRequester(gridFocus).focusRestorer().focusGroup(),
        ) {
            itemsIndexed(feed.items, key = { _, m -> m.id }) { _, meta ->
                val posterRequester = remember(meta.id) { posterFocus.getOrPut(meta.id) { FocusRequester() } }
                StagePoster(
                    title = meta.name.orEmpty(),
                    onClick = { onOpen(meta) },
                    width = 196.mpx,
                    height = 294.mpx,
                    modifier = Modifier.focusRequester(posterRequester),
                ) {
                    if (!meta.poster.isNullOrBlank()) {
                        AsyncImage(model = meta.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            if (!feed.complete) {
                item(key = MORE_ROW_KEY, span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(top = 6.mpx)) {
                        val error = feed.error
                        if (error == null) {
                            Text(stringResource(R.string.gambit_discovery_loading_more), style = stageText(20, 500), color = GambitColors.Dim)
                        } else {
                            Text(stringResource(R.string.gambit_discovery_more_failed), style = stageText(20, 600), color = GambitColors.Text)
                            Text(stringResource(error.messageRes()), style = stageText(18, 500), color = GambitColors.Muted, modifier = Modifier.padding(top = 4.mpx))
                            Row(Modifier.padding(top = 14.mpx)) {
                                StageTool(stringResource(R.string.gambit_discovery_retry), onClick = vm::retryTraktList, boxed = true)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A personal list by its own name; the watchlist by Gambit's word for it. */
@Composable
private fun TraktListChoice.label(): String = when (source) {
    TraktListSource.Watchlist -> stringResource(R.string.gambit_discovery_trakt_watchlist)
    is TraktListSource.Personal -> name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.gambit_discovery_trakt_unnamed)
}

@Composable
internal fun Message(title: String, body: String? = null) {
    Column(Modifier.widthIn(max = 1000.mpx).padding(top = 30.mpx)) {
        Text(title, style = stageText(24, 600), color = GambitColors.Text)
        if (body != null) Text(body, style = stageText(19, 500), color = GambitColors.Muted, modifier = Modifier.padding(top = 8.mpx))
    }
}

@Composable
internal fun DiscoveryType.label(): String = when (this) {
    DiscoveryType.MOVIE -> stringResource(R.string.gambit_discovery_movies)
    DiscoveryType.SERIES -> stringResource(R.string.gambit_discovery_series)
    DiscoveryType.TRAKT -> stringResource(R.string.gambit_discovery_trakt)
    else -> readableName
}

/** "Popular · Cinemeta", or the addon's name alone when the catalogue has none. Never an address. */
@Composable
private fun DiscoveryCatalog.label(): String =
    title?.let { stringResource(R.string.gambit_discovery_catalog_name, it, addonName) } ?: addonName

/** Each error type worded from gambit_strings.xml; the addon by its name and host only. */
@Composable
private fun AddonError.message(): String = when (this) {
    is AddonError.Timeout -> stringResource(R.string.gambit_discovery_err_timeout, addon.displayLabel())
    is AddonError.Network -> stringResource(R.string.gambit_discovery_err_network, addon.displayLabel())
    is AddonError.HttpStatus -> stringResource(R.string.gambit_discovery_err_status, addon.displayLabel(), code)
    is AddonError.MalformedJson -> stringResource(R.string.gambit_discovery_err_unreadable, addon.displayLabel())
    is AddonError.MissingField -> stringResource(R.string.gambit_discovery_err_unreadable, addon.displayLabel())
    is AddonError.NotFound -> stringResource(R.string.gambit_discovery_err_not_found, addon.displayLabel())
    is AddonError.NoAddon -> stringResource(R.string.gambit_discovery_err_no_addon)
    AddonError.InvalidUrl -> stringResource(R.string.gambit_discovery_err_other)
}
