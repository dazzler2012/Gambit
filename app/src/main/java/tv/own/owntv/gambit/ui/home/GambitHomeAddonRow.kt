package tv.own.owntv.gambit.ui.home

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.features.home.HomeRowHeader
import tv.own.owntv.features.home.tracked
import tv.own.owntv.gambit.addons.model.MetaPreview
import tv.own.owntv.gambit.discovery.DiscoveryCatalog
import tv.own.owntv.gambit.ui.discovery.DiscoveryViewModel
import tv.own.owntv.gambit.ui.theme.GambitColors
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StagePoster
import tv.own.owntv.ui.theme.mpx

/*
 * Home's "Live sports" row slot shows addon posters in Gambit. Home's row types are Core's (HomeRow), so
 * the app cannot add one; HomeScreen asks here for the slot's titles and draws this row in its place.
 */

/** The `see all` card's key in the row; posters are keyed by their String id. */
private const val SEE_ALL_KEY = -1

/**
 * The row's titles, fetched again each time Home is shown. Empty (Home then hides the row) on a kids
 * profile, before the profile is read, with no addon catalogue, or when no catalogue answers.
 */
@Composable
fun gambitHomeRowItems(vm: HomeAddonRowViewModel = koinViewModel()): List<MetaPreview> {
    LaunchedEffect(Unit) { vm.refresh() }
    return vm.state.collectAsStateWithLifecycle().value.items
}

/**
 * The row: "Discovery · <catalogue>", its posters, then a "See all" card. OK on a poster opens its title
 * page in Discovery; "See all" opens Discovery on the same catalogue. [onOpenDiscovery] is the shell's
 * switch to the Discovery section, called after Discovery has been told what to show.
 */
@Composable
fun GambitHomeAddonRow(
    start: Dp,
    onOpenDiscovery: () -> Unit,
    onFocus: () -> Unit,
    firstItemFocusRequester: FocusRequester?,
    track: (FocusRequester) -> Unit,
    modifier: Modifier = Modifier,
    vm: HomeAddonRowViewModel = koinViewModel(),
    discovery: DiscoveryViewModel = koinViewModel(),
) {
    val row by vm.state.collectAsStateWithLifecycle()
    val catalog = row.catalog ?: return
    if (row.items.isEmpty()) return
    val open: (MetaPreview?) -> Unit = { preview ->
        discovery.openFromHome(catalog, preview)
        onOpenDiscovery()
    }
    Column(modifier) {
        HomeRowHeader(stringResource(R.string.gambit_home_row_title, catalog.homeName()), small = null, start = start)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(20.mpx),
            contentPadding = PaddingValues(start = start, end = 64.mpx),
            modifier = Modifier.focusRestorer().focusGroup(),
        ) {
            itemsIndexed(row.items, key = { _, item -> item.id }) { index, item ->
                StagePoster(
                    title = item.name.orEmpty(),
                    onClick = { open(item) },
                    width = 196.mpx,
                    height = 294.mpx,
                    modifier = Modifier
                        .then(if (index == 0 && firstItemFocusRequester != null) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                        .tracked(track = track)
                        .onFocusChanged { if (it.hasFocus) onFocus() },
                ) {
                    if (!item.poster.isNullOrBlank()) {
                        AsyncImage(model = item.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            item(key = SEE_ALL_KEY) {
                StagePoster(
                    title = stringResource(R.string.gambit_home_row_see_all),
                    onClick = { open(null) },
                    width = 196.mpx,
                    height = 294.mpx,
                    modifier = Modifier
                        .tracked(track = track)
                        .onFocusChanged { if (it.hasFocus) onFocus() },
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        OwnTVIcon(OwnTVIcon.GRID, tint = GambitColors.Dim, modifier = Modifier.size(64.mpx))
                    }
                }
            }
        }
    }
}

/** The catalogue's own name, else its addon's name (the same fallback as Discovery's drop-down). */
private fun DiscoveryCatalog.homeName(): String = title ?: addonName
