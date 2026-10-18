package tv.own.owntv.gambit.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.gambit.addons.model.StreamSupport
import tv.own.owntv.gambit.addons.net.AddonError
import tv.own.owntv.gambit.addons.net.AddonJson
import tv.own.owntv.gambit.addons.net.AddonResult

class AddonContentJsonTest {
    private fun <T> ok(result: AddonResult<T>): T = (result as AddonResult.Ok).value

    @Test
    fun `catalog items are read and unreadable items dropped`() {
        val metas = ok(AddonJson.catalog(Fixtures.read("catalog_movies.json"), "test", "movie"))
        assertEquals(listOf("tt0000001", "tt0000002", "tt0000003"), metas.map { it.id })
        val first = metas[0]
        assertEquals("First Film", first.name)
        assertEquals("7.8", first.imdbRating)
        assertEquals(listOf("Action", "Drama"), first.genres)
        val second = metas[1]
        assertEquals("movie", second.type)
        assertEquals("2002", second.releaseInfo)
        assertEquals(listOf("Comedy"), second.genres)
        assertNull(metas[2].name)
        assertNull(metas[2].poster)
    }

    @Test
    fun `empty catalog is ok and missing metas is a typed error`() {
        assertTrue(ok(AddonJson.catalog(Fixtures.read("catalog_empty.json"), "test", "movie")).isEmpty())
        assertEquals(AddonResult.Err(AddonError.MissingField("test", "metas")), AddonJson.catalog("{}", "test", "movie"))
    }

    @Test
    fun `series meta with episodes is read`() {
        val meta = ok(AddonJson.meta(Fixtures.read("meta_series.json"), "test", "series"))
        assertEquals("tt0000100", meta.id)
        assertEquals("Example Show", meta.name)
        assertEquals(listOf("Actor One", "Actor Two"), meta.cast)
        assertEquals(3, meta.videos.size)
        assertEquals("tt0000100:1:1", meta.videos[0].id)
        assertEquals(1, meta.videos[0].season)
        assertEquals(1, meta.videos[0].episode)
        assertEquals("Second", meta.videos[1].title)
        assertEquals(2, meta.videos[1].episode)
        assertEquals(1, meta.videos[2].episode)
        assertEquals("Back again.", meta.videos[2].overview)
    }

    @Test
    fun `null meta is not found and malformed meta is typed`() {
        assertEquals(AddonResult.Err(AddonError.NotFound("test")), AddonJson.meta(Fixtures.read("meta_null.json"), "test", "movie"))
        assertEquals(AddonResult.Err(AddonError.MissingField("test", "meta.id")), AddonJson.meta("""{"meta":{"name":"x"}}""", "test", "movie"))
        assertEquals(AddonResult.Err(AddonError.MalformedJson("test")), AddonJson.meta(Fixtures.read("malformed.json"), "test", "movie"))
    }

    @Test
    fun `streams are classified by source`() {
        val streams = ok(AddonJson.streams(Fixtures.read("streams_mixed.json"), "test"))
        assertEquals(
            listOf(
                StreamSupport.PLAYABLE, StreamSupport.PLAYABLE, StreamSupport.TORRENT, StreamSupport.YOUTUBE,
                StreamSupport.EXTERNAL, StreamSupport.UNSUPPORTED_SCHEME, StreamSupport.NO_SOURCE,
            ),
            streams.map { it.support },
        )
        val first = streams[0]
        assertEquals("example|1080p", first.behaviorHints.bingeGroup)
        assertEquals("Film.2001.1080p.mkv", first.behaviorHints.filename)
        assertEquals(2254857830L, first.behaviorHints.videoSize)
        assertTrue(first.behaviorHints.notWebReady)
        assertEquals(mapOf("User-Agent" to "Example/1.0", "Referer" to "https://debrid.example/"), first.behaviorHints.requestHeaders)
        assertEquals(1, first.subtitles.size)
        assertEquals("eng", first.subtitles[0].lang)
        assertEquals("Film.2001.720p.mkv", streams[1].description)
        assertEquals(0, streams[2].fileIdx)
    }

    @Test
    fun `torrent only results parse without crashing and none are playable`() {
        val streams = ok(AddonJson.streams(Fixtures.read("streams_torrent_only.json"), "test"))
        assertEquals(2, streams.size)
        assertTrue(streams.all { it.support == StreamSupport.TORRENT })
        assertFalse(streams.any { it.isPlayable })
    }

    @Test
    fun `missing streams array is a typed error`() {
        assertEquals(AddonResult.Err(AddonError.MissingField("test", "streams")), AddonJson.streams("""{"streams":null}""", "test"))
    }

    @Test
    fun `stream toString leaves out the link`() {
        val streams = ok(AddonJson.streams(Fixtures.read("streams_mixed.json"), "test"))
        streams.forEach { assertFalse(it.toString().contains("TOKEN")) }
        streams.flatMap { it.subtitles }.forEach { assertFalse(it.toString().contains("subs.example")) }
    }
}
