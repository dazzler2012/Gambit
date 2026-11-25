package tv.own.owntv.gambit.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.gambit.trakt.TraktEpisode
import tv.own.owntv.gambit.trakt.TraktIds
import tv.own.owntv.gambit.trakt.TraktListEntry
import tv.own.owntv.gambit.trakt.TraktMediaItem
import tv.own.owntv.gambit.trakt.TraktMovie
import tv.own.owntv.gambit.trakt.TraktSeason
import tv.own.owntv.gambit.trakt.TraktShow

class TraktTitlesTest {
    private fun entry(item: TraktMediaItem) = TraktListEntry(rank = null, listedAt = null, item = item)
    private fun movie(title: String, ids: TraktIds, year: Int? = 2000) = entry(TraktMediaItem.Movie(TraktMovie(title, year, ids)))
    private fun show(s: TraktShow) = entry(TraktMediaItem.Show(s))
    private fun season(s: TraktShow?) = entry(TraktMediaItem.Season(TraktSeason(1, TraktIds(trakt = 70), s)))
    private fun episode(s: TraktShow?) = entry(TraktMediaItem.Episode(TraktEpisode(1, 2, null, TraktIds(trakt = 80), s)))

    private val showA = TraktShow("Show A", 2015, TraktIds(trakt = 1, imdb = "tt0000010", tmdb = 10))
    private val showB = TraktShow("Show B", null, TraktIds(trakt = 2, tmdb = 20))

    @Test
    fun `imdb id first, tmdb as tmdb colon n, no id skipped`() {
        val out = TraktTitles.from(
            listOf(
                movie("Both", TraktIds(imdb = "tt0000001", tmdb = 1)),
                movie("Tmdb only", TraktIds(trakt = 9, tmdb = 2)),
                movie("Trakt and tvdb only", TraktIds(trakt = 3, tvdb = 3, slug = "x")),
                movie("Blank imdb", TraktIds(imdb = " ", tmdb = 4)),
            ),
            mutableSetOf(),
        )
        assertEquals(listOf("tt0000001", "tmdb:2", "tmdb:4"), out.map { it.id })
        assertEquals(listOf("movie", "movie", "movie"), out.map { it.type })
        assertEquals("Both", out[0].name)
        assertEquals("2000", out[0].releaseInfo)
    }

    @Test
    fun `seasons and episodes stand for their show at its first position, with no repeats`() {
        val out = TraktTitles.from(
            listOf(
                episode(showA), // Show A first appears here
                movie("M", TraktIds(imdb = "tt0000005")),
                show(showA),
                season(showB),
                show(showB),
                episode(showA),
                season(null), // no show: nothing to point at
                episode(null),
            ),
            mutableSetOf(),
        )
        assertEquals(listOf("tt0000010", "tt0000005", "tmdb:20"), out.map { it.id })
        assertEquals(listOf("series", "movie", "series"), out.map { it.type })
        assertEquals(listOf("Show A", "M", "Show B"), out.map { it.name })
        assertNull(out[2].releaseInfo)
    }

    @Test
    fun `ids seen on an earlier page are not repeated`() {
        val seen = mutableSetOf<String>()
        TraktTitles.from(listOf(show(showA)), seen)
        val second = TraktTitles.from(listOf(episode(showA), show(showB)), seen)
        assertEquals(listOf("tmdb:20"), second.map { it.id })
        assertEquals(setOf("tt0000010", "tmdb:20"), seen)
    }
}
