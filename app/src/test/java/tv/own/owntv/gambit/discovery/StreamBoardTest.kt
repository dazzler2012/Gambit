package tv.own.owntv.gambit.discovery

import java.net.SocketTimeoutException
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.gambit.addons.FakeAddonServer
import tv.own.owntv.gambit.addons.FakeAddonServer.Reply
import tv.own.owntv.gambit.addons.TestAddons
import tv.own.owntv.gambit.addons.TestAddons.STREAMS_A_BASE
import tv.own.owntv.gambit.addons.TestAddons.STREAMS_B_BASE
import tv.own.owntv.gambit.addons.data.AddonRegistry
import tv.own.owntv.gambit.addons.data.AddonRepository
import tv.own.owntv.gambit.addons.net.AddonClient
import tv.own.owntv.gambit.addons.net.AddonError

class StreamBoardTest {
    private val server = FakeAddonServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repo = AddonRepository(AddonClient(server.client))
    private var registry = AddonRegistry(listOf(TestAddons.streamsA, TestAddons.streamsB))
    private val board = StreamBoard(repo, { registry }, scope)

    @After
    fun tearDown() = scope.cancel()

    private val film = StreamTarget("movie", "tt0000001", "Film")
    private fun url(base: String, id: String = "tt0000001") = "$base/stream/movie/$id.json"

    private suspend fun awaitFeed(predicate: (StreamFeed) -> Boolean): StreamFeed =
        withTimeout(5_000) { board.state.first { it != null && predicate(it.feed) }!!.feed }

    private fun streams(vararg names: String) =
        """{"streams":[${names.joinToString(",") { """{"name":"$it","url":"https://cdn.example/x/$it.mkv"}""" }}]}"""

    @Test
    fun `rows keep addon order, then each addon's own order`() = runBlocking {
        server.onJson(url(STREAMS_A_BASE), streams("a1", "a2"))
        server.onJson(url(STREAMS_B_BASE), streams("b1"))
        board.open(film)
        val loaded = awaitFeed { it is StreamFeed.Loaded } as StreamFeed.Loaded
        assertEquals(listOf("a1", "a2", "b1"), loaded.rows.map { it.nameLines.first() })
        assertEquals(listOf(0, 1, 2), loaded.rows.map { it.key })
        assertEquals("a2", board.streamFor(1)?.stream?.name)
    }

    @Test
    fun `unplayable streams are counted, not listed`() = runBlocking {
        server.onFixture(url(STREAMS_A_BASE), "streams_mixed.json")
        server.onFixture(url(STREAMS_B_BASE), "streams_torrent_only.json")
        board.open(film)
        val loaded = awaitFeed { it is StreamFeed.Loaded } as StreamFeed.Loaded
        assertEquals(2, loaded.rows.size)
        assertEquals(7, loaded.unplayable)
        val first = loaded.rows.first()
        assertEquals(listOf("[RD+] Example", "1080p"), first.nameLines)
        assertEquals(StreamQuality.FHD, first.quality)
        assertEquals(2254857830L, first.sizeBytes)
        assertEquals("Example Streams", first.addonName)
    }

    @Test
    fun `one failing addon leaves the others listed, with its error`() = runBlocking {
        server.on(url(STREAMS_A_BASE), Reply.Fail(SocketTimeoutException()))
        server.onJson(url(STREAMS_B_BASE), streams("b1"))
        board.open(film)
        val loaded = awaitFeed { it is StreamFeed.Loaded } as StreamFeed.Loaded
        assertEquals(listOf("b1"), loaded.rows.map { it.nameLines.first() })
        assertTrue(loaded.errors.single() is AddonError.Timeout)
    }

    @Test
    fun `every addon failing is an error, and retry asks again`() = runBlocking {
        server.on(url(STREAMS_A_BASE), Reply.Fail(SocketTimeoutException()))
        server.onJson(url(STREAMS_B_BASE), "{}", code = 500)
        board.open(film)
        val failed = awaitFeed { it is StreamFeed.Failed } as StreamFeed.Failed
        assertTrue(failed.error is AddonError.Timeout)
        server.onJson(url(STREAMS_A_BASE), streams("a1"))
        server.onJson(url(STREAMS_B_BASE), streams())
        board.retry()
        val loaded = awaitFeed { it is StreamFeed.Loaded } as StreamFeed.Loaded
        assertEquals(1, loaded.rows.size)
    }

    @Test
    fun `nothing found is an empty list, not an error`() = runBlocking {
        server.onJson(url(STREAMS_A_BASE), streams())
        server.onJson(url(STREAMS_B_BASE), streams())
        board.open(film)
        val loaded = awaitFeed { it is StreamFeed.Loaded } as StreamFeed.Loaded
        assertTrue(loaded.rows.isEmpty())
        assertEquals(0, loaded.unplayable)
        assertTrue(loaded.errors.isEmpty())
    }

    @Test
    fun `no stream addon is a typed error`() = runBlocking {
        registry = AddonRegistry(listOf(TestAddons.meta))
        board.open(film)
        val failed = awaitFeed { it is StreamFeed.Failed } as StreamFeed.Failed
        assertTrue(failed.error is AddonError.NoAddon)
    }

    @Test
    fun `close forgets the list and the streams`() = runBlocking {
        server.onJson(url(STREAMS_A_BASE), streams("a1"))
        server.onJson(url(STREAMS_B_BASE), streams())
        board.open(film)
        awaitFeed { it is StreamFeed.Loaded }
        board.close()
        assertNull(board.state.value)
        assertNull(board.streamFor(0))
    }

    @Test
    fun `an answer for a closed list is dropped`() = runBlocking {
        server.on(url(STREAMS_A_BASE), Reply.Slow(300, streams("a1")))
        server.onJson(url(STREAMS_B_BASE), streams())
        board.open(film)
        board.close()
        delay(500)
        assertNull(board.state.value)
    }

    @Test
    fun `quality is read from names and filenames`() {
        assertEquals(StreamQuality.UHD, StreamRows.quality(listOf("AIOStreams 4K")))
        assertEquals(StreamQuality.UHD, StreamRows.quality(listOf("x", "Film.2160p.HDR.mkv")))
        assertEquals(StreamQuality.FHD, StreamRows.quality(listOf("Film.1080p.WEB")))
        assertEquals(StreamQuality.HD, StreamRows.quality(listOf("720p")))
        assertEquals(StreamQuality.SD, StreamRows.quality(listOf("Film 480p")))
        assertNull(StreamRows.quality(listOf("Film.2001.mkv", "14K views")))
    }

    @Test
    fun `lines are split, trimmed and blanks dropped`() {
        assertEquals(listOf("a", "b"), StreamRows.lines(" a \n\n b\n"))
        assertTrue(StreamRows.lines(null).isEmpty())
    }

    /** Stream addresses, header values and subtitle addresses can hold debrid tokens. */
    @Test
    fun `stream secrets never reach a string, the state or the rows`() = runBlocking {
        val secrets = listOf("TOKEN-123", "SECRET-456", "KEY-789")
        server.onJson(
            url(STREAMS_A_BASE),
            """{"streams":[{"name":"AIO\n4K","title":"Film.2160p.mkv","url":"https://cdn.example/dl/TOKEN-123/film.mkv",
               "behaviorHints":{"filename":"Film.2160p.mkv","videoSize":1000,"proxyHeaders":{"request":{"Authorization":"Bearer SECRET-456"}}},
               "subtitles":[{"id":"en","url":"https://subs.example/KEY-789/en.srt","lang":"eng"}]}]}""",
        )
        server.onJson(url(STREAMS_B_BASE), streams())
        board.open(film)
        val loaded = awaitFeed { it is StreamFeed.Loaded } as StreamFeed.Loaded
        val sourced = board.streamFor(0)!!
        // The stream is held, so Stage 5 can play it ...
        assertTrue(sourced.stream.url!!.contains("TOKEN-123"))
        // ... but nothing printable or shown carries a secret.
        val texts = listOf(
            sourced.stream.toString(),
            sourced.stream.behaviorHints.toString(),
            sourced.stream.subtitles.toString(),
            sourced.toString(),
            repo.streams(registry, "movie", "tt0000001").toString(),
            board.state.value.toString(),
            loaded.toString(),
            loaded.rows.toString(),
        )
        for (text in texts) for (secret in secrets) assertFalse("$secret in $text", text.contains(secret))
        assertTrue(sourced.stream.behaviorHints.toString().contains("Authorization"))
    }
}
