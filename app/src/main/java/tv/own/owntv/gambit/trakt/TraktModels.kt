package tv.own.owntv.gambit.trakt

import java.time.Instant

/** The IDs Trakt knows an item by. Requests need at least one; Trakt prefers [trakt] when it has it. */
data class TraktIds(
    val trakt: Long? = null,
    val slug: String? = null,
    val imdb: String? = null,
    val tmdb: Long? = null,
    val tvdb: Long? = null,
) {
    val isEmpty: Boolean get() = trakt == null && slug == null && imdb == null && tmdb == null && tvdb == null
}

data class TraktMovie(val title: String?, val year: Int?, val ids: TraktIds)

data class TraktShow(val title: String?, val year: Int?, val ids: TraktIds)

data class TraktSeason(val number: Int, val ids: TraktIds, val show: TraktShow?)

data class TraktEpisode(
    val season: Int,
    val number: Int,
    val title: String?,
    val ids: TraktIds,
    /** The parent show, when the response included it (history, playback and watchlist do). */
    val show: TraktShow?,
)

/** One item in a history, playback or watchlist response. */
sealed interface TraktMediaItem {
    data class Movie(val movie: TraktMovie) : TraktMediaItem

    data class Show(val show: TraktShow) : TraktMediaItem

    data class Season(val season: TraktSeason) : TraktMediaItem

    data class Episode(val episode: TraktEpisode) : TraktMediaItem
}

/** A page of results, with Trakt's X-Pagination-* headers when it sent them. */
data class TraktPage<T>(
    val items: List<T>,
    val page: Int?,
    val limit: Int?,
    val pageCount: Int?,
    val itemCount: Int?,
)

// ---- Device sign-in ----

/**
 * What to show while the user signs in: send them to [verificationUrl] to enter [userCode].
 * [toString] never prints [deviceCode] or [userCode].
 */
class TraktDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
) {
    override fun toString(): String =
        "TraktDeviceCode(deviceCode=$REDACTED, userCode=$REDACTED, verificationUrl=$verificationUrl, " +
            "expiresInSeconds=$expiresInSeconds, intervalSeconds=$intervalSeconds)"
}

// ---- Scrobble ----

/** What is playing. */
sealed interface TraktScrobbleItem {
    data class Movie(val ids: TraktIds, val title: String? = null, val year: Int? = null) : TraktScrobbleItem

    /** An episode by its own IDs (Trakt or TVDB, per Trakt's current docs). */
    data class Episode(val ids: TraktIds) : TraktScrobbleItem

    /**
     * An episode by its show's IDs plus season and episode number, for when only the show is matched.
     * Taken from Trakt's legacy (Apiary) docs; the current OpenAPI spec lists only [Episode]. Unverified live.
     */
    data class EpisodeOfShow(val showIds: TraktIds, val season: Int, val number: Int) : TraktScrobbleItem
}

/** Trakt's answer to a scrobble call. [action] is "start", "pause" or "scrobble". */
data class TraktScrobbleResult(
    val id: Long?,
    val action: String,
    val progress: Double,
    val item: TraktMediaItem?,
)

// ---- Sync ----

data class TraktHistoryEntry(
    val id: Long,
    val watchedAt: Instant,
    /** "scrobble", "checkin" or "watch". */
    val action: String?,
    val item: TraktMediaItem,
)

data class TraktPlaybackEntry(
    /** The playback ID; pass it to Trakt to remove this progress. */
    val id: Long,
    /** 0 to 100. */
    val progress: Double,
    val pausedAt: Instant,
    val item: TraktMediaItem,
)

data class TraktWatchlistEntry(
    val id: Long?,
    val rank: Int?,
    val listedAt: Instant?,
    val item: TraktMediaItem,
)

/** Something to add to or remove from the watchlist. */
sealed interface TraktWatchlistTarget {
    val ids: TraktIds

    data class Movie(override val ids: TraktIds) : TraktWatchlistTarget

    data class Show(override val ids: TraktIds) : TraktWatchlistTarget

    data class Season(override val ids: TraktIds) : TraktWatchlistTarget

    data class Episode(override val ids: TraktIds) : TraktWatchlistTarget
}

data class TraktCounts(val movies: Int = 0, val shows: Int = 0, val seasons: Int = 0, val episodes: Int = 0)

/** What a watchlist add or remove changed. For a remove, [changed] holds the deleted counts and [existing] is empty. */
data class TraktWatchlistChange(
    val changed: TraktCounts,
    val existing: TraktCounts,
    val notFound: TraktCounts,
)
