package tv.own.owntv.gambit.discovery

import tv.own.owntv.gambit.addons.model.MetaPreview
import tv.own.owntv.gambit.trakt.TraktIds
import tv.own.owntv.gambit.trakt.TraktListEntry
import tv.own.owntv.gambit.trakt.TraktMediaItem

/**
 * Turns Trakt list entries into the titles Discovery shows, in Trakt's order.
 *
 *  - The id is the IMDb id; without one, the TMDB id as `tmdb:<n>`; without either, the entry is skipped.
 *  - A movie is a `movie`; a show is a `series`. A season or episode stands for its parent show, so the
 *    show appears where it is first listed. One without its show is skipped.
 *  - No id appears twice. [seen] holds the ids already shown (earlier pages) and is filled as it goes.
 */
object TraktTitles {
    fun from(entries: List<TraktListEntry>, seen: MutableSet<String>): List<MetaPreview> =
        entries.mapNotNull { entry -> title(entry.item)?.takeIf { seen.add(it.id) } }

    private fun title(item: TraktMediaItem): MetaPreview? = when (item) {
        is TraktMediaItem.Movie -> preview(item.movie.ids, MOVIE, item.movie.title, item.movie.year)
        is TraktMediaItem.Show -> preview(item.show.ids, SERIES, item.show.title, item.show.year)
        is TraktMediaItem.Season -> item.season.show?.let { preview(it.ids, SERIES, it.title, it.year) }
        is TraktMediaItem.Episode -> item.episode.show?.let { preview(it.ids, SERIES, it.title, it.year) }
    }

    private fun preview(ids: TraktIds, type: String, title: String?, year: Int?): MetaPreview? =
        idOf(ids)?.let { MetaPreview(id = it, type = type, name = title, releaseInfo = year?.toString()) }

    /** The IMDb id, else `tmdb:<n>`, else null. */
    internal fun idOf(ids: TraktIds): String? =
        ids.imdb?.trim()?.takeIf { it.isNotEmpty() } ?: ids.tmdb?.let { "$TMDB_PREFIX$it" }

    private const val MOVIE = "movie"
    private const val SERIES = "series"
    private const val TMDB_PREFIX = "tmdb:"
}
