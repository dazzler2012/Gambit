@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package tv.own.owntv.gambit.ui.discovery

import android.content.ActivityNotFoundException
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import tv.own.owntv.R
import tv.own.owntv.features.search.VoiceSearch
import tv.own.owntv.gambit.discovery.DiscoveryType
import tv.own.owntv.gambit.discovery.SearchFeed
import tv.own.owntv.gambit.discovery.SearchRow
import tv.own.owntv.gambit.discovery.SearchRowFeed
import tv.own.owntv.gambit.discovery.SearchState
import tv.own.owntv.gambit.ui.theme.GambitColors
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StagePoster
import tv.own.owntv.ui.stage.StageSearchField
import tv.own.owntv.ui.stage.StageTool
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/** A row of posters, and its "Loading…" stand-in, are this tall, so nothing below moves when it fills. */
private const val ROW_HEIGHT_MPX = 366

/**
 * Discovery's search page: the field (OK opens the TV keyboard), the microphone where the TV has a speech
 * recogniser, and one row of posters per searchable catalogue. Only composed for an adult profile. Failed
 * addons are named, never their address.
 */
@Composable
internal fun GambitSearchPage(
    search: SearchState,
    fieldFocus: FocusRequester,
    listState: LazyListState,
    rowStates: MutableMap<String, LazyListState>,
    /** The result (row key, title id) to focus, after Back from its title page; null focuses the field. */
    initialResult: Pair<String, String>?,
    onType: (String) -> Unit,
    onSubmit: () -> Unit,
    onVoice: (String) -> Unit,
    onRetry: () -> Unit,
    onOpen: (rowKey: String, id: String) -> Unit,
    onChildFocused: () -> Unit,
    contentStart: Dp,
    modifier: Modifier = Modifier,
) {
    val results = search.feed as? SearchFeed.Results
    val posterFocus = remember { HashMap<Pair<String, String>, FocusRequester>() }
    val rowState: (String) -> LazyListState = { key -> rowStates.getOrPut(key) { LazyListState() } }

    LaunchedEffect(Unit) {
        val target = initialResult
        val rows = results?.visible.orEmpty()
        val rowIndex = rows.indexOfFirst { it.key == target?.first }
        val itemIndex = (rows.getOrNull(rowIndex)?.feed as? SearchRowFeed.Loaded)
            ?.items?.indexOfFirst { it.id == target?.second } ?: -1
        if (target != null && itemIndex >= 0) {
            listState.scrollToItem(rowIndex)
            withFrameNanos { }
            rowState(target.first).scrollToItem(itemIndex)
        }
        // The page has just been built: try for a few frames until the target is there.
        for (attempt in 0 until 10) {
            withFrameNanos { }
            val wanted = if (itemIndex >= 0) target?.let { posterFocus[it] } ?: continue else fieldFocus
            if (runCatching { wanted.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
        runCatching { fieldFocus.requestFocus() }
    }

    Column(
        modifier
            .padding(start = contentStart, top = 48.mpx, end = 64.mpx)
            .onFocusChanged { if (it.hasFocus) onChildFocused() }
            .focusGroup(),
    ) {
        Text(stringResource(R.string.gambit_search_title), style = stageText(46, 800), color = GambitColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 18.mpx).widthIn(max = 900.mpx), horizontalArrangement = Arrangement.spacedBy(12.mpx)) {
            StageSearchField(
                query = search.query,
                onQueryChange = onType,
                placeholder = stringResource(R.string.gambit_search_hint),
                modifier = Modifier.weight(1f).focusRequester(fieldFocus),
                // Closing the keyboard searches at once.
                onEditingChange = { editing -> if (!editing) onSubmit() },
            )
            VoiceButton(onVoice)
        }

        if (results != null && !results.allFailed && results.failedAddons.isNotEmpty()) {
            Row(Modifier.padding(top = 16.mpx), horizontalArrangement = Arrangement.spacedBy(20.mpx), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.gambit_search_failed_some, results.failedNames()),
                    style = stageText(19, 500), color = GambitColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 1000.mpx),
                )
                // The button goes away once pressed, so focus moves to the field first.
                StageTool(stringResource(R.string.gambit_discovery_retry), onClick = { runCatching { fieldFocus.requestFocus() }; onRetry() }, boxed = true)
            }
        }

        when {
            search.feed == SearchFeed.NoSearchAddons -> Message(
                stringResource(R.string.gambit_search_no_addons_title),
                stringResource(R.string.gambit_search_no_addons),
            )
            results == null -> Message(stringResource(R.string.gambit_search_start))
            results.allFailed -> {
                Message(
                    stringResource(R.string.gambit_search_failed_title),
                    stringResource(R.string.gambit_search_failed_some, results.failedNames()),
                )
                Row(Modifier.padding(top = 20.mpx)) {
                    StageTool(stringResource(R.string.gambit_discovery_retry), onClick = { runCatching { fieldFocus.requestFocus() }; onRetry() }, boxed = true)
                }
            }
            results.nothingFound -> Message(stringResource(R.string.gambit_search_nothing))
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.padding(top = 18.mpx).fillMaxSize().focusGroup(),
                contentPadding = PaddingValues(bottom = 40.mpx),
                verticalArrangement = Arrangement.spacedBy(10.mpx),
            ) {
                items(results.visible, key = { it.key }) { row ->
                    ResultRow(row, rowState(row.key), posterFocus, onOpen)
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    row: SearchRow,
    state: LazyListState,
    posterFocus: MutableMap<Pair<String, String>, FocusRequester>,
    onOpen: (rowKey: String, id: String) -> Unit,
) {
    val type = DiscoveryType(row.ref.catalog.type).label()
    val catalog = row.ref.catalog.name?.takeIf { it.isNotBlank() }
    val addon = row.ref.addon.manifest.name
    Column {
        Text(
            if (catalog != null) stringResource(R.string.gambit_search_row, type, catalog, addon)
            else stringResource(R.string.gambit_discovery_catalog_name, type, addon),
            style = stageText(22, 700), color = GambitColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        when (val feed = row.feed) {
            is SearchRowFeed.Loaded -> LazyRow(
                state = state,
                modifier = Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT_MPX.mpx).focusRestorer().focusGroup(),
                // Room for the focused poster's zoom and ring.
                contentPadding = PaddingValues(horizontal = 14.mpx, vertical = 18.mpx),
                horizontalArrangement = Arrangement.spacedBy(26.mpx),
            ) {
                items(feed.items, key = { it.id }) { meta ->
                    val requester = remember(row.key, meta.id) { posterFocus.getOrPut(row.key to meta.id) { FocusRequester() } }
                    StagePoster(
                        title = meta.name.orEmpty(),
                        onClick = { onOpen(row.key, meta.id) },
                        width = 196.mpx,
                        height = 294.mpx,
                        modifier = Modifier.focusRequester(requester),
                    ) {
                        if (!meta.poster.isNullOrBlank()) {
                            AsyncImage(model = meta.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
            // Failed rows are not drawn (see SearchFeed.Results.visible), so this is a row still loading.
            else -> Box(Modifier.fillMaxWidth().height(ROW_HEIGHT_MPX.mpx).padding(horizontal = 14.mpx, vertical = 18.mpx)) {
                Text(stringResource(R.string.gambit_discovery_loading), style = stageText(20, 500), color = GambitColors.Dim)
            }
        }
    }
}

/** The failed addons' names, "A, B". */
@Composable
private fun SearchFeed.Results.failedNames(): String =
    failedAddons.joinToString(stringResource(R.string.gambit_addons_list_separator))

/**
 * The microphone, absent where no speech recogniser answers (or once one has refused to start). The
 * recogniser app does the listening, so no microphone permission is needed.
 */
@Composable
private fun VoiceButton(onHeard: (String) -> Unit) {
    val context = LocalContext.current
    var available by remember(context) { mutableStateOf(VoiceSearch.isAvailable(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        // Temporary diagnostic: names and counts only, never what was said.
        android.util.Log.i("GambitVoice", "resultCode=${result.resultCode} dataNull=${result.data == null} extraKeys=${result.data?.extras?.keySet()} matches=${matches?.size}")
        VoiceSearch.spokenQuery(result.resultCode, matches)?.let(onHeard)
    }
    if (!available) return
    val label = stringResource(R.string.gambit_search_voice)
    val prompt = stringResource(R.string.gambit_search_voice_prompt)
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    StageTool(
        text = null,
        icon = OwnTVIcon.MIC,
        boxed = true,
        onClick = {
            try {
                // The recogniser is a screen the app opens itself, so it must not open picture-in-picture.
                tv.own.owntv.features.pip.startAsHandOff(VoiceSearch.intent(prompt, language)) { launcher.launch(it) }
            } catch (_: ActivityNotFoundException) {
                available = false
            }
        },
        modifier = Modifier.semantics { contentDescription = label },
    )
}
