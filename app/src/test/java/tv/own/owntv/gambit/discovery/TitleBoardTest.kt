package tv.own.owntv.gambit.discovery

import java.net.SocketTimeoutException
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.gambit.addons.FakeAddonServer
import tv.own.owntv.gambit.addons.FakeAddonServer.Reply
import tv.own.owntv.gambit.addons.TestAddons
import tv.own.owntv.gambit.addons.TestAddons.META_BASE
import tv.own.owntv.gambit.addons.data.AddonRegistry
import tv.own.owntv.gambit.addons.data.AddonRepository
import tv.own.owntv.gambit.addons.model.AddonVideo
import tv.own.owntv.gambit.addons.model.MetaPreview
import tv.own.owntv.gambit.addons.net.AddonClient
import tv.own.owntv.gambit.addons.net.AddonError

class TitleBoardTest {
    private val server = FakeAddonServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val registry = AddonRegistry(listOf(TestAddons.meta))
    private val board = TitleBoard(AddonRepository(AddonClient(server.client)), { registry }, scope)

    @After
    fun tearDown() = scope.cancel()

    private val show = MetaPreview("tt0000100", "series", name = "Example Show")

    private suspend fun awaitFeed(predicate: (TitleFeed) -> Boolean): TitleFeed =
        withTimeout(5_000) { board.state.first { it != null && predicate(it.feed) }!!.feed }

    private fun ep(season: Int?, episode: Int?, id: String = "x:$season:$episode") = AddonVideo(id, season = season, episode = episode)

    @Test
    fun `a series loads with its seasons and opens on season 1`() = runBlocking {
        server.onFixture("$META_BASE/meta/series/tt0000100.json", "meta_series.json")
        board.open(show, null)
        val loaded = awaitFeed { it is TitleFeed.Loaded } as TitleFeed.Loaded
        assertEquals("Example Show", loaded.detail.name)
        assertEquals(listOf(1, 2), loaded.seasons.map { it.number })
        assertEquals(listOf("tt0000100:1:1", "tt0000100:1:2"), loaded.seasons[0].episodes.map { it.id })
        assertEquals(0, loaded.season)
        board.selectSeason(1)
        assertEquals(1, (board.state.value!!.feed as TitleFeed.Loaded).season)
        board.selectSeason(5)
        assertEquals(1, (board.state.value!!.feed as TitleFeed.Loaded).season)
    }

    @Test
    fun `a movie has no seasons`() = runBlocking {
        server.onJson("$META_BASE/meta/movie/tt1.json", """{"meta":{"id":"tt1","type":"movie","name":"A Film","videos":[{"id":"tt1","season":1,"episode":1}]}}""")
        board.open(MetaPreview("tt1", "movie", name = "A Film"), null)
        val loaded = awaitFeed { it is TitleFeed.Loaded } as TitleFeed.Loaded
        assertTrue(loaded.seasons.isEmpty())
    }

    @Test
    fun `a failure is typed, and retry asks again`() = runBlocking {
        server.on("$META_BASE/meta/series/tt0000100.json", Reply.Fail(SocketTimeoutException()))
        board.open(show, null)
        val failed = awaitFeed { it is TitleFeed.Failed } as TitleFeed.Failed
        assertTrue(failed.error is AddonError.Timeout)
        server.onFixture("$META_BASE/meta/series/tt0000100.json", "meta_series.json")
        board.retry()
        awaitFeed { it is TitleFeed.Loaded }
        Unit
    }

    @Test
    fun `close forgets the title`() = runBlocking {
        server.on("$META_BASE/meta/series/tt0000100.json", Reply.Slow(300, "{}"))
        board.open(show, null)
        board.close()
        assertNull(board.state.value)
        kotlinx.coroutines.delay(500)
        assertNull(board.state.value)
    }

    @Test
    fun `seasons in order, then Specials, then episodes with no season`() {
        val seasons = TitleSeasons.group(listOf(ep(null, 1), ep(0, 1), ep(2, 2), ep(1, 3), ep(2, 1), ep(1, null), ep(1, 1)))
        assertEquals(listOf(1, 2, 0, null), seasons.map { it.number })
        assertEquals(listOf(1, 3, null), seasons[0].episodes.map { it.episode })
        assertEquals(listOf(1, 2), seasons[1].episodes.map { it.episode })
    }

    @Test
    fun `the page opens on the first regular season`() {
        assertEquals(0, TitleSeasons.firstSeason(emptyList()))
        assertEquals(1, TitleSeasons.firstSeason(listOf(TitleSeason(null, emptyList()), TitleSeason(3, emptyList()))))
        assertEquals(0, TitleSeasons.firstSeason(TitleSeasons.group(listOf(ep(0, 1), ep(4, 1)))))
        assertEquals(0, TitleSeasons.firstSeason(listOf(TitleSeason(0, emptyList()))))
    }

    @Test
    fun `air dates read from either form, junk is ignored`() {
        assertEquals(LocalDate.of(2020, 1, 1), TitleSeasons.airDate("2020-01-01T00:00:00.000Z"))
        assertEquals(LocalDate.of(2021, 3, 4), TitleSeasons.airDate("2021-03-04"))
        assertNull(TitleSeasons.airDate("soon"))
        assertNull(TitleSeasons.airDate(null))
    }
}
