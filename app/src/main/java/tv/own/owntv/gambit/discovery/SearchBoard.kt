package tv.own.owntv.gambit.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.own.owntv.gambit.addons.data.AddonRegistry
import tv.own.owntv.gambit.addons.data.AddonRepository
import tv.own.owntv.gambit.addons.data.CatalogRef
import tv.own.owntv.gambit.addons.model.MetaPreview
import tv.own.owntv.gambit.addons.net.AddonError
import tv.own.owntv.gambit.addons.net.AddonResult
import tv.own.owntv.gambit.addons.net.AddonUrl
import tv.own.owntv.gambit.addons.net.CatalogExtraArgs

/** What one searchable catalogue has answered so far. */
sealed interface SearchRowFeed {
    data object Loading : SearchRowFeed

    /** [items] is empty when the catalogue found nothing. */
    class Loaded(val items: List<MetaPreview>) : SearchRowFeed {
        override fun toString(): String = "Loaded(${items.size})"
    }

    data class Failed(val error: AddonError) : SearchRowFeed
}

/** One catalogue's results. */
data class SearchRow(val ref: CatalogRef, val feed: SearchRowFeed = SearchRowFeed.Loading) {
    val key: String get() = ref.key
}

sealed interface SearchFeed {
    /** Nothing asked: no text yet, too little of it, or still typing. */
    data object Idle : SearchFeed

    /** No enabled addon has a catalogue that can be searched. */
    data object NoSearchAddons : SearchFeed

    data class Results(val rows: List<SearchRow>) : SearchFeed {
        /** The rows to draw, in order: still loading, or with something found. */
        val visible: List<SearchRow>
            get() = rows.filter { row ->
                when (val feed = row.feed) {
                    SearchRowFeed.Loading -> true
                    is SearchRowFeed.Loaded -> feed.items.isNotEmpty()
                    is SearchRowFeed.Failed -> false
                }
            }

        val searching: Boolean get() = rows.any { it.feed == SearchRowFeed.Loading }

        /** The addons that failed, by name only: no host, no address. */
        val failedAddons: List<String>
            get() = rows.filter { it.feed is SearchRowFeed.Failed }.map { it.ref.addon.manifest.name }.distinct()

        val allFailed: Boolean get() = rows.all { it.feed is SearchRowFeed.Failed }

        /** Every answer is in, at least one addon did answer, and none found anything. */
        val nothingFound: Boolean get() = !searching && !allFailed && visible.isEmpty()
    }
}

/** The search page's data. Its text form never includes what was typed. */
class SearchState(val query: String = "", val feed: SearchFeed = SearchFeed.Idle) {
    override fun toString(): String = "SearchState(${feed.javaClass.simpleName})"
}

/** A result the user chose: the item and the addon it came from, which is asked first for details. */
class SearchPick(val preview: MetaPreview, val source: AddonUrl) {
    override fun toString(): String = "SearchPick(${source.host})"
}

/**
 * Search across the addons' searchable catalogues, each asked on its own so a slow or failing one holds
 * nothing else up. Every call is refused unless [allowed] says so (adult profiles only), and a refusal
 * forgets the text and results. It has no [KidsTitleGate], so a result can never open anything for kids.
 *
 * The typed text is kept in memory only and never logged. A change of text cancels the requests in
 * flight, and a late reply for old text is dropped.
 *
 * Calls come from one thread (the main one in the app).
 */
class SearchBoard(
    private val repository: AddonRepository,
    private val registry: () -> AddonRegistry,
    private val scope: CoroutineScope,
    private val allowed: () -> Boolean,
    private val debounceMs: Long = DEBOUNCE_MS,
) {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var pending: Job? = null
    private val requests = mutableListOf<Job>()

    /** The text the current results are for; null when there are none. */
    private var searched: String? = null

    /** Bumped whenever requests are dropped; a reply applies only while its number is still current. */
    @Volatile private var generation = 0

    /** The field's text changed: search once typing pauses. */
    fun type(text: String) {
        if (!allowed()) return clear()
        val term = text.trim()
        pending?.cancel()
        if (term == searched) {
            _state.update { SearchState(text, it.feed) }
            return
        }
        drop()
        _state.value = SearchState(text)
        if (term.length < MIN_LENGTH) return
        pending = scope.launch {
            delay(debounceMs)
            search(term)
        }
    }

    /** The keyboard was closed or voice input finished: search now. */
    fun submit() {
        if (!allowed()) return clear()
        pending?.cancel()
        val term = _state.value.query.trim()
        if (term.length >= MIN_LENGTH && term != searched) search(term)
    }

    /** Asks the failed catalogues again; the others keep their results. */
    fun retryFailed() {
        if (!allowed()) return clear()
        val term = searched ?: return
        val results = _state.value.feed as? SearchFeed.Results ?: return
        val failed = results.rows.filter { it.feed is SearchRowFeed.Failed }.map { it.ref }
        failed.forEach { show(it.key, SearchRowFeed.Loading, generation) }
        failed.forEach { ask(it, term) }
    }

    /** The result [id] of the row [rowKey], or null when it isn't there or search is not allowed. */
    fun pick(rowKey: String, id: String): SearchPick? {
        if (!allowed()) return null
        val row = (_state.value.feed as? SearchFeed.Results)?.rows?.firstOrNull { it.key == rowKey } ?: return null
        val item = (row.feed as? SearchRowFeed.Loaded)?.items?.firstOrNull { it.id == id } ?: return null
        return SearchPick(item, row.ref.addon.url)
    }

    /** Call when the profile's access may have changed. */
    fun accessChanged() {
        if (!allowed()) clear()
    }

    /** Call when the enabled addons changed: results on screen are asked for again. */
    fun onRegistryChanged() {
        if (!allowed()) return clear()
        searched?.let(::search)
    }

    /** Cancels everything and forgets the text and results. */
    fun clear() {
        pending?.cancel()
        drop()
        _state.value = SearchState()
    }

    private fun drop() {
        generation++
        requests.forEach { it.cancel() }
        requests.clear()
        searched = null
    }

    private fun search(term: String) {
        if (!allowed()) return clear()
        drop()
        searched = term
        // Movies, then Series, then other types; within a type, addon order then manifest order.
        val refs = registry().searchCatalogs().distinctBy { it.key }
        val types = (FIRST_TYPES + refs.map { it.catalog.type }).distinct()
        val ordered = refs.sortedBy { types.indexOf(it.catalog.type) }
        val feed = if (ordered.isEmpty()) SearchFeed.NoSearchAddons else SearchFeed.Results(ordered.map { SearchRow(it) })
        _state.update { SearchState(it.query, feed) }
        ordered.forEach { ask(it, term) }
    }

    private fun ask(ref: CatalogRef, term: String) {
        val gen = generation
        requests += scope.launch {
            val result = repository.catalog(ref, CatalogExtraArgs(search = term))
            if (!allowed()) return@launch
            val feed = when (result) {
                is AddonResult.Ok -> SearchRowFeed.Loaded(result.value.distinctBy { it.id }.take(MAX_PER_ROW))
                is AddonResult.Err -> SearchRowFeed.Failed(result.error)
            }
            show(ref.key, feed, gen)
        }
    }

    private fun show(rowKey: String, feed: SearchRowFeed, gen: Int) {
        _state.update { now ->
            val results = now.feed as? SearchFeed.Results
            if (gen != generation || results == null) {
                now
            } else {
                SearchState(now.query, SearchFeed.Results(results.rows.map { if (it.key == rowKey) it.copy(feed = feed) else it }))
            }
        }
    }

    companion object {
        /** Short enough for titles like "Up". */
        const val MIN_LENGTH = 2

        /** A pause this long after the last letter starts the search. */
        const val DEBOUNCE_MS = 700L

        const val MAX_PER_ROW = 50

        private val FIRST_TYPES = listOf(DiscoveryType.MOVIE.id, DiscoveryType.SERIES.id)
    }
}
