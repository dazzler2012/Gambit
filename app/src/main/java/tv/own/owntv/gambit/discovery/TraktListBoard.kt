package tv.own.owntv.gambit.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.own.owntv.gambit.addons.model.MetaPreview
import tv.own.owntv.gambit.trakt.TraktError
import tv.own.owntv.gambit.trakt.TraktListPage
import tv.own.owntv.gambit.trakt.TraktLists
import tv.own.owntv.gambit.trakt.TraktResult

/** Which Trakt list a [TraktListBoard] shows. */
sealed interface TraktListSource {
    data object Watchlist : TraktListSource

    /** A personal list, by its Trakt id. */
    data class Personal(val listId: Long) : TraktListSource
}

/** What the poster grid shows for a Trakt list. Errors are typed; the screen words them. */
sealed interface TraktListFeed {
    data object Idle : TraktListFeed

    data object Loading : TraktListFeed

    /** The whole list was read and holds nothing Gambit can show. */
    data object Empty : TraktListFeed

    /** The first page failed. [TraktListBoard.retry] asks again. */
    data class Failed(val error: TraktError) : TraktListFeed

    /**
     * [items] in Trakt's order, no repeats. [complete] once the last page is in. While not complete and
     * [error] is null the next page is on its way; with [error] set, [TraktListBoard.retry] goes on from
     * the page that failed and the items stay.
     */
    data class Loaded(
        val items: List<MetaPreview>,
        val complete: Boolean,
        val error: TraktError? = null,
    ) : TraktListFeed
}

data class TraktListState(
    val source: TraktListSource? = null,
    val feed: TraktListFeed = TraktListFeed.Idle,
)

/**
 * The data behind a Trakt list on screen: reads the chosen list page by page, one request at a time,
 * to its end, keeping Trakt's order (see [TraktTitles]). Plain Kotlin; the view model hands it a scope.
 *
 * A finished list is kept in memory and shown again without a request for [REUSE_MILLIS]. Call
 * [forget] on a profile switch and on Trakt sign-out: it drops every kept list and the one on screen.
 * Choosing another list (or [forget]) cancels the request in flight, and a late reply is dropped.
 * Kids profiles never reach this board, so it takes no kids gate.
 *
 * Calls come from one thread (the main one in the app).
 */
class TraktListBoard(
    private val lists: TraktLists,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(TraktListState())
    val state: StateFlow<TraktListState> = _state.asStateFlow()

    private class Kept(val items: List<MetaPreview>, val atMillis: Long)

    /** Where reading stopped for the list on screen: what a retry goes on from. */
    private class Progress(val items: List<MetaPreview>, val seen: Set<String>, val nextPage: Int)

    private val cache = mutableMapOf<TraktListSource, Kept>()
    private var progress: Progress? = null
    private var job: Job? = null

    /** Bumped on every new read; a reply applies only while its number is still current. */
    @Volatile private var generation = 0

    /**
     * Shows [source]: from memory when it was read in full less than [REUSE_MILLIS] ago, otherwise read
     * again from the first page. Asking again for the list already being read changes nothing.
     */
    fun open(source: TraktListSource) {
        val s = _state.value
        if (source == s.source && job?.isActive == true) return
        val kept = cache[source]
        if (kept != null && nowMillis() - kept.atMillis < REUSE_MILLIS) {
            cancel()
            progress = null
            _state.value = TraktListState(source, finished(kept.items))
            return
        }
        cache.remove(source)
        read(source, Progress(emptyList(), emptySet(), nextPage = 1))
    }

    /** After a failure: goes on from the page that failed, keeping what was read. */
    fun retry() {
        val s = _state.value
        val source = s.source ?: return
        val failed = when (val feed = s.feed) {
            is TraktListFeed.Failed -> true
            is TraktListFeed.Loaded -> feed.error != null
            else -> false
        }
        if (!failed || job?.isActive == true) return
        read(source, progress ?: Progress(emptyList(), emptySet(), nextPage = 1))
    }

    /** Drops every kept list and the one on screen, and any reply still on its way. */
    fun forget() {
        cancel()
        cache.clear()
        progress = null
        _state.value = TraktListState()
    }

    private fun cancel() {
        generation++
        job?.cancel()
        job = null
    }

    private fun read(source: TraktListSource, from: Progress) {
        cancel()
        val gen = generation
        progress = from
        _state.value = TraktListState(source, if (from.items.isEmpty()) TraktListFeed.Loading else TraktListFeed.Loaded(from.items, complete = false))
        job = scope.launch {
            var at = from
            while (true) {
                val result = fetch(source, at.nextPage)
                if (gen != generation) return@launch
                val page = when (result) {
                    is TraktResult.Failure -> {
                        progress = at
                        val feed = if (at.items.isEmpty()) TraktListFeed.Failed(result.error) else TraktListFeed.Loaded(at.items, complete = false, error = result.error)
                        _state.value = TraktListState(source, feed)
                        return@launch
                    }
                    is TraktResult.Success -> result.value
                }
                val seen = at.seen.toMutableSet()
                val fresh = TraktTitles.from(page.entries, seen)
                at = Progress(at.items + fresh, seen, at.nextPage + 1)
                progress = at
                if (!page.hasMore || at.nextPage > MAX_PAGES) {
                    cache[source] = Kept(at.items, nowMillis())
                    _state.value = TraktListState(source, finished(at.items))
                    return@launch
                }
                _state.value = TraktListState(source, TraktListFeed.Loaded(at.items, complete = false))
            }
        }
    }

    private suspend fun fetch(source: TraktListSource, page: Int): TraktResult<TraktListPage> = when (source) {
        TraktListSource.Watchlist -> lists.watchlist(page)
        is TraktListSource.Personal -> lists.items(source.listId, page)
    }

    private fun finished(items: List<MetaPreview>): TraktListFeed =
        if (items.isEmpty()) TraktListFeed.Empty else TraktListFeed.Loaded(items, complete = true)

    companion object {
        /** How long a list read in full is shown again without asking Trakt. */
        const val REUSE_MILLIS = 10 * 60 * 1000L

        /** A guard against a server that never says the list has ended: 100 pages of 100. */
        internal const val MAX_PAGES = 100
    }
}
