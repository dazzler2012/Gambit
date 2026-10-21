package tv.own.owntv.gambit.trakt

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TraktScrobblerTest {
    private val movie = TraktScrobbleItem.Movie(TraktIds(trakt = 1001, tmdb = 2001), title = "Fake Movie", year = 2010)
    private val episode = TraktScrobbleItem.Episode(TraktIds(trakt = 3003))

    @Test
    fun `start a movie`() = runBlocking {
        val h = Harness()
        h.host.on("POST", "/scrobble/start", Canned(status = 201, body = Fake.fixture("scrobble_start_movie.json")))

        val result = h.client.scrobbler.start(movie, 1.25).getOrNull()!!

        assertEquals("start", result.action)
        assertEquals(1.25, result.progress, 0.0)
        assertEquals(TraktIds(trakt = 1001, slug = "fake-movie-2010", imdb = "tt0000001", tmdb = 2001), (result.item as TraktMediaItem.Movie).movie.ids)

        val sent = h.host.seen.single()
        assertEquals("Bearer ${Fake.ACCESS}", sent.headers["Authorization"])
        val body = JSONObject(sent.body)
        assertEquals(1.25, body.getDouble("progress"), 0.0)
        val sentMovie = body.getJSONObject("movie")
        assertEquals("Fake Movie", sentMovie.getString("title"))
        assertEquals(2010, sentMovie.getInt("year"))
        assertEquals(1001L, sentMovie.getJSONObject("ids").getLong("trakt"))
        assertEquals(2001L, sentMovie.getJSONObject("ids").getLong("tmdb"))
        assertFalse(sentMovie.getJSONObject("ids").has("imdb"))
    }

    @Test
    fun `pause an episode`() = runBlocking {
        val h = Harness()
        h.host.on("POST", "/scrobble/pause", Canned(status = 201, body = Fake.fixture("scrobble_pause_episode.json")))

        val result = h.client.scrobbler.pause(episode, 42.5).getOrNull()!!

        assertEquals("pause", result.action)
        val ep = (result.item as TraktMediaItem.Episode).episode
        assertEquals(1, ep.season)
        assertEquals(3, ep.number)
        assertEquals(null, ep.ids.imdb)
        assertEquals("Fake Show", ep.show!!.title)
        assertEquals(3003L, JSONObject(h.host.seen.single().body).getJSONObject("episode").getJSONObject("ids").getLong("trakt"))
    }

    @Test
    fun `stop a movie scrobbles it`() = runBlocking {
        val h = Harness()
        h.host.on("POST", "/scrobble/stop", Canned(status = 201, body = Fake.fixture("scrobble_stop_movie.json")))

        val result = h.client.scrobbler.stop(movie, 99.9).getOrNull()!!

        assertEquals("scrobble", result.action)
        assertEquals(9_876_543_210L, result.id)
    }

    @Test
    fun `stop an episode by show, season and number`() = runBlocking {
        val h = Harness()
        h.host.on("POST", "/scrobble/stop", Canned(status = 201, body = Fake.fixture("scrobble_stop_episode.json")))

        val result = h.client.scrobbler.stop(TraktScrobbleItem.EpisodeOfShow(TraktIds(tmdb = 8001), season = 1, number = 3), 95.0)

        assertEquals("scrobble", result.getOrNull()!!.action)
        val body = JSONObject(h.host.seen.single().body)
        assertEquals(8001L, body.getJSONObject("show").getJSONObject("ids").getLong("tmdb"))
        assertEquals(1, body.getJSONObject("episode").getInt("season"))
        assertEquals(3, body.getJSONObject("episode").getInt("number"))
    }

    @Test
    fun `progress is clamped to 0 to 100`() = runBlocking {
        val h = Harness()
        h.host.on("POST", "/scrobble/stop", Canned(status = 201, body = Fake.fixture("scrobble_stop_movie.json")))
        h.host.on("POST", "/scrobble/start", Canned(status = 201, body = Fake.fixture("scrobble_start_movie.json")))

        h.client.scrobbler.stop(movie, 140.0)
        h.client.scrobbler.start(movie, -3.0)

        assertEquals(100.0, JSONObject(h.host.seen("POST", "/scrobble/stop").single().body).getDouble("progress"), 0.0)
        assertEquals(0.0, JSONObject(h.host.seen("POST", "/scrobble/start").single().body).getDouble("progress"), 0.0)
    }

    @Test
    fun `progress under 1 percent is unprocessable`() = runBlocking {
        val h = Harness()
        h.host.on("POST", "/scrobble/stop", Canned(status = 422))

        assertEquals(TraktError.Unprocessable, h.client.scrobbler.stop(movie, 0.5).errorOrNull())
    }

    @Test
    fun `scrobble needs a sign-in`() = runBlocking {
        val h = Harness(tokens = null)

        assertEquals(TraktError.NotSignedIn, h.client.scrobbler.start(movie, 10.0).errorOrNull())
    }
}
