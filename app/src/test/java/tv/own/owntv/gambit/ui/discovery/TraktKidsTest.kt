package tv.own.owntv.gambit.ui.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.gambit.discovery.DiscoveryType
import tv.own.owntv.gambit.discovery.KidsTitleGate
import tv.own.owntv.gambit.discovery.TraktDiscovery
import tv.own.owntv.gambit.discovery.TraktDiscoveryState
import tv.own.owntv.gambit.discovery.TraktListFeed
import tv.own.owntv.gambit.discovery.TraktListSource
import tv.own.owntv.gambit.discovery.TraktListState
import tv.own.owntv.gambit.trakt.Canned
import tv.own.owntv.gambit.trakt.Fake
import tv.own.owntv.gambit.trakt.Harness

/** Trakt in Discovery is for adult profiles only. Every request would go to the fake Trakt host. */
class TraktKidsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val h = Harness()
    private var access: AddonAccess = AddonAccess.Adult
    private val trakt = TraktDiscovery(h.client, scope, { traktAllowed(access, configured = true) })

    private val notAdult = listOf(AddonAccess.Kids(setOf("kids-key"), profileId = 2), AddonAccess.Closed, AddonAccess.Unknown)

    @After
    fun tearDown() = scope.cancel()

    private fun routes() {
        h.host.on("GET", "/users/me/lists", Canned(body = Fake.fixture("lists.json")))
        h.host.on("GET", "/sync/watchlist/all", Canned(body = Fake.fixture("watchlist.json")))
        h.host.on("GET", "/users/me/lists/42/items/movie,show,season,episode", Canned(body = Fake.fixture("list_items.json")))
    }

    private suspend fun awaitWatchlist() = withTimeout(5_000) {
        trakt.list.first { (it.feed as? TraktListFeed.Loaded)?.complete == true }
    }

    @Test
    fun `kids, closed and unknown get no Trakt choice and send zero Trakt requests`() = runBlocking {
        routes()
        for (a in notAdult) {
            access = a
            assertFalse("$a", traktAllowed(a, configured = true))
            assertFalse("$a", trakt.offered)
            assertFalse("$a", trakt.enter())
            trakt.select(TraktListSource.Watchlist)
            trakt.select(TraktListSource.Personal(42))
            trakt.retry()
            trakt.retryList()
            trakt.refresh()
            trakt.accessChanged()
        }
        delay(300)
        assertTrue("no Trakt request at all", h.host.seen.isEmpty())
        assertEquals(TraktDiscoveryState(), trakt.state.value)
        assertEquals(TraktListState(), trakt.list.value)
    }

    @Test
    fun `only an adult profile in a configured build is offered Trakt`() {
        assertTrue(traktAllowed(AddonAccess.Adult, configured = true))
        assertFalse(traktAllowed(AddonAccess.Adult, configured = false))
        notAdult.forEach { assertFalse("$it", traktAllowed(it, configured = false)) }
        val noClient = TraktDiscovery(null, scope, { true })
        assertFalse(noClient.offered)
        assertFalse(noClient.enter())
    }

    @Test
    fun `adult to kids clears the board and Trakt stays refused`() = runBlocking {
        routes()
        assertTrue(trakt.enter())
        awaitWatchlist()
        assertTrue(trakt.state.value.active)

        for (a in notAdult) {
            access = AddonAccess.Adult
            if (!trakt.state.value.active) {
                trakt.enter()
                awaitWatchlist()
            }
            access = a
            trakt.accessChanged() // what the view model does on every access change
            assertEquals("$a", TraktDiscoveryState(), trakt.state.value)
            assertEquals("$a", TraktListState(), trakt.list.value)
            val before = h.host.seen.size
            assertFalse(trakt.enter())
            trakt.select(TraktListSource.Watchlist)
            delay(200)
            assertEquals("$a: nothing sent after the switch", before, h.host.seen.size)
        }
    }

    @Test
    fun `KidsTitleGate never allows a Trakt title`() = runBlocking {
        routes()
        val gate = KidsTitleGate()
        trakt.enter()
        val ids = (awaitWatchlist().feed as TraktListFeed.Loaded).items.map { it.id }
        assertTrue(ids.isNotEmpty())

        // Even a kids profile whose approvals somehow named Trakt's reserved id gets nothing from Trakt.
        gate.retainApproved(2, setOf(DiscoveryType.TRAKT_ID))
        access = AddonAccess.Kids(setOf(DiscoveryType.TRAKT_ID), profileId = 2)
        trakt.accessChanged()
        assertFalse(trakt.enter())
        delay(200)
        ids.forEach { id ->
            assertFalse(id, gate.allows(DiscoveryType.TRAKT_ID, id))
            assertFalse(id, gate.allows("kids-key", id))
        }
    }
}
