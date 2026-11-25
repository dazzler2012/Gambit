package tv.own.owntv.gambit.discovery

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
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.gambit.trakt.Canned
import tv.own.owntv.gambit.trakt.Fake
import tv.own.owntv.gambit.trakt.Harness
import tv.own.owntv.gambit.trakt.TraktError
import tv.own.owntv.gambit.trakt.TraktTokens

/** Trakt in Discovery for an adult profile. Every request goes to the fake Trakt host. */
class TraktDiscoveryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private val listsPath = "/users/me/lists"
    private val watchPath = "/sync/watchlist/all"
    private val list42Path = "/users/me/lists/42/items/movie,show,season,episode"

    private fun setup(tokens: TraktTokens? = Harness.validTokens()): Pair<Harness, TraktDiscovery> {
        val h = Harness(tokens = tokens)
        return h to TraktDiscovery(h.client, scope, { true })
    }

    private fun Harness.routes() {
        host.on("GET", listsPath, Canned(body = Fake.fixture("lists.json")))
        host.on("GET", watchPath, Canned(body = Fake.fixture("watchlist.json")))
        host.on("GET", list42Path, Canned(body = Fake.fixture("list_items.json")))
    }

    private suspend fun TraktDiscovery.await(predicate: (TraktDiscoveryState) -> Boolean) =
        withTimeout(5_000) { state.first(predicate) }

    private suspend fun TraktDiscovery.awaitItems(): List<String> = withTimeout(5_000) {
        (list.first { (it.feed as? TraktListFeed.Loaded)?.complete == true }.feed as TraktListFeed.Loaded).items.map { it.id }
    }

    @Test
    fun `signed out says so and sends nothing`() = runBlocking {
        val (h, trakt) = setup(tokens = null)
        assertTrue(trakt.enter())
        trakt.await { it.account == TraktAccount.SignedOut }
        delay(100)
        assertTrue(h.host.seen.isEmpty())
        assertTrue(trakt.state.value.active)
    }

    @Test
    fun `watchlist first, then personal lists in Trakt's order, opening on the watchlist`() = runBlocking {
        val (h, trakt) = setup()
        h.routes()
        trakt.enter()
        val s = trakt.await { it.account == TraktAccount.SignedIn }
        assertEquals(
            listOf(
                TraktListChoice(TraktListSource.Watchlist, null),
                TraktListChoice(TraktListSource.Personal(42), "Fake Favourites"),
                TraktListChoice(TraktListSource.Personal(43), null),
            ),
            s.choices,
        )
        assertEquals(TraktListSource.Watchlist, s.selected)
        assertEquals(listOf("tt0000001", "tt0000002"), trakt.awaitItems())
    }

    @Test
    fun `choosing a personal list reads it, an unknown list is ignored`() = runBlocking {
        val (h, trakt) = setup()
        h.routes()
        trakt.enter()
        trakt.awaitItems()
        trakt.select(TraktListSource.Personal(99))
        assertEquals(TraktListSource.Watchlist, trakt.state.value.selected)
        trakt.select(TraktListSource.Personal(42))
        withTimeout(5_000) { trakt.list.first { it.source == TraktListSource.Personal(42) && (it.feed as? TraktListFeed.Loaded)?.complete == true } }
        assertEquals(listOf("tt0000001", "tt0000002"), (trakt.list.value.feed as TraktListFeed.Loaded).items.map { it.id })
        assertTrue(h.host.seen("GET", "/users/me/lists/99/items/movie,show,season,episode").isEmpty())
    }

    @Test
    fun `personal lists failing still offers the watchlist`() = runBlocking {
        val (h, trakt) = setup()
        h.host.on("GET", listsPath, Canned(status = 500))
        h.host.on("GET", watchPath, Canned(body = Fake.fixture("watchlist.json")))
        trakt.enter()
        val s = trakt.await { it.account == TraktAccount.SignedIn }
        assertEquals(listOf(TraktListChoice(TraktListSource.Watchlist, null)), s.choices)
        assertEquals(TraktError.Server(500), s.listsError)
        assertEquals(listOf("tt0000001", "tt0000002"), trakt.awaitItems())
    }

    @Test
    fun `a sign-in that ran out while reading shows signed out and keeps nothing`() = runBlocking {
        val (h, trakt) = setup()
        h.host.on("GET", listsPath, Canned(body = Fake.fixture("lists.json")))
        h.host.on("GET", watchPath, Canned(status = 401))
        h.host.on("POST", "/oauth/token", Canned(status = 401, body = Fake.fixture("token_invalid_grant.json")))
        trakt.enter()
        trakt.await { it.account == TraktAccount.SignedOut }
        assertTrue(trakt.state.value.choices.isEmpty())
        withTimeout(5_000) { trakt.list.first { it == TraktListState() } }
        Unit
    }

    @Test
    fun `leaving keeps a read list, forget drops it`() = runBlocking {
        val (h, trakt) = setup()
        h.routes()
        trakt.enter()
        trakt.awaitItems()
        trakt.leave()
        trakt.enter()
        trakt.awaitItems()
        delay(100)
        assertEquals("kept for ten minutes", 1, h.host.seen("GET", watchPath).size)

        trakt.forget() // profile switch
        assertEquals(TraktDiscoveryState(), trakt.state.value)
        assertEquals(TraktListState(), trakt.list.value)
        trakt.enter()
        trakt.awaitItems()
        assertEquals(2, h.host.seen("GET", watchPath).size)
    }

    @Test
    fun `sign-out drops the lists and the next check says signed out`() = runBlocking {
        val (h, trakt) = setup()
        h.routes()
        trakt.enter()
        trakt.awaitItems()
        h.store.tokens = null // More → Trakt signed out
        trakt.signedOut()
        assertEquals(TraktDiscoveryState(active = true), trakt.state.value)
        assertEquals(TraktListState(), trakt.list.value)
        trakt.refresh() // Discovery shown again
        trakt.await { it.account == TraktAccount.SignedOut }
        assertEquals(1, h.host.seen("GET", watchPath).size)
    }
}
