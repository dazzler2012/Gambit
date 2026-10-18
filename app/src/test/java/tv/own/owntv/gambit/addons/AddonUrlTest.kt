package tv.own.owntv.gambit.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.gambit.addons.net.AddonUrl
import tv.own.owntv.gambit.addons.net.CatalogExtraArgs

class AddonUrlTest {
    private fun url(raw: String): AddonUrl = AddonUrl.parse(raw) ?: throw AssertionError("rejected a valid url")

    @Test
    fun `manifest suffix and trailing slashes are dropped`() {
        assertEquals("https://addon.example/abc", url("https://addon.example/abc/manifest.json").base)
        assertEquals("https://addon.example/abc", url("https://addon.example/abc/").base)
        assertEquals("https://addon.example/abc", url("  https://addon.example/abc/manifest.json/  ").base)
        assertEquals("https://addon.example", url("https://addon.example/manifest.json").base)
    }

    @Test
    fun `stremio scheme becomes https`() {
        val u = url("stremio://addon.example/abc/manifest.json")
        assertEquals("https://addon.example/abc", u.base)
        assertEquals("https://addon.example/abc/manifest.json", u.manifestUrl)
    }

    @Test
    fun `query is kept and re-appended to every request`() {
        val u = url("https://addon.example/abc/manifest.json?config=xyz&b=2")
        assertEquals("config=xyz&b=2", u.query)
        assertEquals("https://addon.example/abc/manifest.json?config=xyz&b=2", u.manifestUrl)
        assertEquals("https://addon.example/abc/meta/movie/tt1.json?config=xyz&b=2", u.metaUrl("movie", "tt1"))
    }

    @Test
    fun `non http input is rejected`() {
        assertNull(AddonUrl.parse(""))
        assertNull(AddonUrl.parse("addon.example/manifest.json"))
        assertNull(AddonUrl.parse("ftp://addon.example/manifest.json"))
        assertNull(AddonUrl.parse("javascript:alert(1)"))
        assertNull(AddonUrl.parse("https://"))
    }

    @Test
    fun `resource urls follow the stremio patterns`() {
        val u = url("https://addon.example/abc/manifest.json")
        assertEquals("https://addon.example/abc/catalog/movie/popular.json", u.catalogUrl("movie", "popular"))
        assertEquals("https://addon.example/abc/meta/series/tt100.json", u.metaUrl("series", "tt100"))
        assertEquals("https://addon.example/abc/stream/series/tt100%3A1%3A2.json", u.streamUrl("series", "tt100:1:2"))
        assertEquals("https://addon.example/abc/subtitles/movie/tt1.json", u.subtitlesUrl("movie", "tt1"))
    }

    @Test
    fun `catalog extras are encoded into the last path segment`() {
        val u = url("https://addon.example/abc")
        assertEquals("https://addon.example/abc/catalog/movie/popular/skip=100.json",
            u.catalogUrl("movie", "popular", CatalogExtraArgs(skip = 100)))
        assertEquals("https://addon.example/abc/catalog/movie/search/search=the%20matrix%20%26%20co.json",
            u.catalogUrl("movie", "search", CatalogExtraArgs(search = "the matrix & co")))
        assertEquals("https://addon.example/abc/catalog/movie/popular/genre=Sci-Fi&skip=50.json",
            u.catalogUrl("movie", "popular", CatalogExtraArgs(genre = "Sci-Fi", skip = 50)))
        assertEquals("https://addon.example/abc/catalog/movie/popular.json",
            u.catalogUrl("movie", "popular", CatalogExtraArgs(search = " ", skip = 0)))
    }

    @Test
    fun `ids and non ascii text are percent encoded`() {
        val u = url("https://addon.example/abc")
        assertEquals("https://addon.example/abc/catalog/movie/list%2Fone/search=caf%C3%A9.json",
            u.catalogUrl("movie", "list/one", CatalogExtraArgs(search = "café")))
    }

    @Test
    fun `toString shows the host only`() {
        val u = url("https://addon.example/SECRETKEY123/manifest.json?token=SECRETKEY123")
        assertEquals("AddonUrl(addon.example)", u.toString())
        assertFalse(u.toString().contains("SECRETKEY123"))
    }

    @Test
    fun `equal addresses compare equal`() {
        assertEquals(url("https://addon.example/abc/manifest.json"), url("stremio://addon.example/abc/"))
    }
}
