package tv.own.owntv.gambit.trakt

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TraktSyncTest {
    @Test
    fun `history parses movies and episodes with pagination`() = runBlocking {
        val h = Harness()
        val headers = mapOf(
            "X-Pagination-Page" to "1",
            "X-Pagination-Limit" to "50",
            "X-Pagination-Page-Count" to "3",
            "X-Pagination-Item-Count" to "120",
        )
        h.host.on("GET", "/sync/history", Canned(body = Fake.fixture("history.json"), headers = headers))

        val page = h.client.sync.history().getOrNull()!!

        assertEquals(TraktPage(page.items, page = 1, limit = 50, pageCount = 3, itemCount = 120), page)
        val (movie, episode) = page.items
        assertEquals(111L, movie.id)
        assertEquals(Instant.parse("2026-10-20T20:15:00Z"), movie.watchedAt)
        assertEquals("scrobble", movie.action)
        assertEquals("Fake Movie", (movie.item as TraktMediaItem.Movie).movie.title)
        val ep = (episode.item as TraktMediaItem.Episode).episode
        assertEquals(2, ep.season)
        assertEquals(7, ep.number)
        assertNull(ep.title)
        assertEquals(6001L, ep.show!!.ids.trakt)
        assertEquals("page=1&limit=50", h.host.seen.single().query)
    }

    @Test
    fun `history by type`() = runBlocking {
        val h = Harness()
        h.host.on("GET", "/sync/history/episodes", Canned(body = "[]"))

        val page = h.client.sync.history(TraktSync.HistoryType.EPISODES, page = 2, limit = 10).getOrNull()!!

        assertEquals(emptyList<TraktHistoryEntry>(), page.items)
        assertNull("no pagination headers sent", page.pageCount)
        assertEquals("page=2&limit=10", h.host.seen.single().query)
    }

    @Test
    fun `history with an item missing its media is malformed`() = runBlocking {
        val h = Harness()
        h.host.on("GET", "/sync/history", Canned(body = Fake.fixture("history_bad_item.json")))

        assertEquals(TraktError.Malformed("missing 'movie'"), h.client.sync.history().errorOrNull())
    }

    @Test
    fun `playback progress`() = runBlocking {
        val h = Harness()
        h.host.on("GET", "/sync/playback", Canned(body = Fake.fixture("playback.json")))

        val (movie, episode) = h.client.sync.playback().getOrNull()!!

        assertEquals(501L, movie.id)
        assertEquals(37.5, movie.progress, 0.0)
        assertEquals(Instant.parse("2026-10-21T08:30:00Z"), movie.pausedAt)
        assertEquals(502L, episode.id)
        assertEquals(3003L, (episode.item as TraktMediaItem.Episode).episode.ids.trakt)
    }

    @Test
    fun `playback by type`() = runBlocking {
        val h = Harness()
        h.host.on("GET", "/sync/playback/movies", Canned(body = "[]"))

        assertEquals(emptyList<TraktPlaybackEntry>(), h.client.sync.playback(TraktSync.PlaybackType.MOVIES).getOrNull())
    }

    @Test
    fun `watchlist reads every kind in rank order`() = runBlocking {
        val h = Harness()
        h.host.on("GET", "/sync/watchlist/all/rank/asc", Canned(body = Fake.fixture("watchlist.json")))

        val items = h.client.sync.watchlist().getOrNull()!!

        assertEquals(listOf(1, 2, 3, 4), items.map { it.rank })
        assertEquals(Instant.parse("2026-09-01T10:00:00Z"), items[0].listedAt)
        assertEquals(1001L, (items[0].item as TraktMediaItem.Movie).movie.ids.trakt)
        assertEquals(6001L, (items[1].item as TraktMediaItem.Show).show.ids.trakt)
        val season = (items[2].item as TraktMediaItem.Season).season
        assertEquals(2, season.number)
        assertEquals("Fake Show", season.show!!.title)
        assertEquals(3, (items[3].item as TraktMediaItem.Episode).episode.number)
    }

    @Test
    fun `watchlist by type`() = runBlocking {
        val h = Harness()
        h.host.on("GET", "/sync/watchlist/movies/rank/asc", Canned(body = "[]"))

        assertEquals(emptyList<TraktWatchlistEntry>(), h.client.sync.watchlist(TraktSync.WatchlistType.MOVIES).getOrNull())
    }

    @Test
    fun `add to watchlist sends grouped ids and reads the counts`() = runBlocking {
        val h = Harness()
        h.host.on("POST", "/sync/watchlist", Canned(status = 201, body = Fake.fixture("watchlist_add.json")))

        val change = h.client.sync.addToWatchlist(
            listOf(
                TraktWatchlistTarget.Movie(TraktIds(tmdb = 2001)),
                TraktWatchlistTarget.Movie(TraktIds(imdb = "tt0000999")),
                TraktWatchlistTarget.Show(TraktIds(trakt = 6001)),
                TraktWatchlistTarget.Episode(TraktIds(trakt = 3003)),
            ),
        ).getOrNull()!!

        assertEquals(TraktCounts(movies = 1, shows = 1), change.changed)
        assertEquals(TraktCounts(episodes = 1), change.existing)
        assertEquals(TraktCounts(movies = 1), change.notFound)

        val body = JSONObject(h.host.seen.single().body)
        assertEquals(2, body.getJSONArray("movies").length())
        assertEquals(2001L, body.getJSONArray("movies").getJSONObject(0).getJSONObject("ids").getLong("tmdb"))
        assertEquals(6001L, body.getJSONArray("shows").getJSONObject(0).getJSONObject("ids").getLong("trakt"))
        assertEquals(1, body.getJSONArray("episodes").length())
    }

    @Test
    fun `remove from watchlist`() = runBlocking {
        val h = Harness()
        h.host.on("POST", "/sync/watchlist/remove", Canned(body = Fake.fixture("watchlist_remove.json")))

        val change = h.client.sync.removeFromWatchlist(
            listOf(TraktWatchlistTarget.Movie(TraktIds(trakt = 1001)), TraktWatchlistTarget.Show(TraktIds(trakt = 999999))),
        ).getOrNull()!!

        assertEquals(TraktCounts(movies = 1), change.changed)
        assertEquals(TraktCounts(shows = 1), change.notFound)
    }

    @Test
    fun `watchlist changes with no items or no ids are refused without a request`() = runBlocking {
        val h = Harness()

        assertEquals(TraktError.Unprocessable, h.client.sync.addToWatchlist(emptyList()).errorOrNull())
        assertEquals(TraktError.Unprocessable, h.client.sync.removeFromWatchlist(listOf(TraktWatchlistTarget.Movie(TraktIds()))).errorOrNull())
        assertEquals(0, h.host.seen.size)
    }

    @Test
    fun `sync calls refresh on 401 too`() = runBlocking {
        val h = Harness()
        h.host.on("POST", "/sync/watchlist", Canned(status = 401), Canned(status = 201, body = Fake.fixture("watchlist_add.json")))
        h.host.on("POST", "/oauth/token", Canned(body = Fake.fixture("token_ok.json")))

        val result = h.client.sync.addToWatchlist(listOf(TraktWatchlistTarget.Movie(TraktIds(trakt = 1001))))

        assertEquals(TraktCounts(movies = 1, shows = 1), result.getOrNull()!!.changed)
        assertEquals(Fake.NEW_REFRESH, h.store.tokens!!.refreshToken)
    }
}
