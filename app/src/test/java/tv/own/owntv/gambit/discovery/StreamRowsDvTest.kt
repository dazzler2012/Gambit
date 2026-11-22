package tv.own.owntv.gambit.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spotting likely Dolby Vision Profile 7 streams from addon text, and listing them lower. */
class StreamRowsDvTest {

    private fun p7(vararg texts: String) = StreamRows.likelyDvProfile7(texts.toList())

    @Test
    fun `Blu-ray REMUX with DV is flagged, as listed by the addons`() {
        // The End of Oak Street, as shown on the Shield.
        assertTrue(p7("[PM] MediaFusion pm 2160p", "BluRay REMUX HEVC BTM\nHDR | DV\n66 GB (88 Mbps)\nMulti"))
        assertTrue(p7("[RD] StremThru Torz 2160p", "BluRay REMUX HEVC\nDV | HDR10 TrueHD | Atmos | DD | DD+ 7.1 | 5.1"))
        assertTrue(p7("x", "y", "The.End.of.Oak.Street.2026.2160p.UHDRemux.HDR.DV-TheEqualizer.mkv"))
        assertTrue(p7("Film.2026.2160p.BluRay.REMUX.HEVC.DoVi.TrueHD.mkv"))
        assertTrue(p7("Film 2160p BDRemux Dolby Vision"))
    }

    @Test
    fun `an explicit Profile 7 counts even without REMUX`() {
        assertTrue(p7("Film.2160p.DV.P7.mkv"))
        assertTrue(p7("Film 2160p DV FEL"))
        assertTrue(p7("Film 2160p DoVi MEL"))
        assertTrue(p7("Film 2160p DV Profile 7"))
    }

    @Test
    fun `Movies Anywhere hybrids, WEB sources and Profile 5 or 8 are not flagged`() {
        // The Odyssey 98 GB, which played on the Shield.
        assertFalse(p7("[PM] MediaFusion pm 2160p", "BluRay REMUX HEVC BTM Movies Anywhere\nHDR10+ | DV DTS"))
        assertFalse(p7("Film.2026.2160p.WEB-DL.DV.HDR.H265.mkv"))
        assertFalse(p7("Film 2160p WEBRip DoVi"))
        assertFalse(p7("Film 2160p WEB DV"))
        assertFalse(p7("Film.2026.2160p.BluRay.REMUX.DV.P8.mkv"))
        assertFalse(p7("Film 2160p REMUX DV Profile 8"))
        assertFalse(p7("Film 2160p REMUX DV P5"))
    }

    @Test
    fun `no Dolby Vision, no flag`() {
        assertFalse(p7("BluRay REMUX HEVC\nHDR10 DTS-HD MA 5.1"))
        assertFalse(p7("Film.2026.2160p.UHD.BluRay.REMUX.HDR.HEVC.DTS-HD.MA.5.1-d3g.mkv"))
        assertFalse(p7("Film 1080p DVDRip REMUX"))
        assertFalse(p7("Mel Brooks Collection REMUX 1080p"))
    }

    @Test
    fun `DTS-HD MA is not Movies Anywhere`() {
        assertTrue(p7("BluRay REMUX HEVC\nDV | HDR10 DTS-HD MA 7.1"))
    }

    @Test
    fun `MEL on its own is a name, not a layer`() {
        assertFalse(p7("Mel's Film 2160p WEB-DL DV"))
    }

    private fun row(key: Int, quality: StreamQuality?, flagged: Boolean = false) =
        StreamRow(key, listOf("s$key"), emptyList(), quality, null, "Addon", mayNotPlay = flagged)

    private fun keys(rows: List<StreamRow>) = rows.map { it.key }

    @Test
    fun `flagged rows move below the other streams of their quality`() {
        val rows = listOf(
            row(0, StreamQuality.UHD, flagged = true),
            row(1, StreamQuality.UHD),
            row(2, StreamQuality.UHD, flagged = true),
            row(3, StreamQuality.UHD),
            row(4, StreamQuality.FHD),
        )
        assertEquals(listOf(1, 3, 0, 2, 4), keys(StreamRows.demoteMayNotPlay(rows)))
    }

    @Test
    fun `a flagged row never jumps past another quality`() {
        val rows = listOf(
            row(0, StreamQuality.FHD, flagged = true),
            row(1, StreamQuality.UHD),
            row(2, StreamQuality.FHD),
            row(3, StreamQuality.UHD),
        )
        // Placed right after the last clean 1080p row; 4K rows keep their places.
        assertEquals(listOf(1, 2, 0, 3), keys(StreamRows.demoteMayNotPlay(rows)))
    }

    @Test
    fun `with nothing clean of that quality, flagged rows stay where they were`() {
        val rows = listOf(
            row(0, StreamQuality.UHD, flagged = true),
            row(1, StreamQuality.UHD, flagged = true),
            row(2, StreamQuality.FHD),
        )
        assertEquals(listOf(0, 1, 2), keys(StreamRows.demoteMayNotPlay(rows)))
    }

    @Test
    fun `a flagged row already below the clean ones stays put, and nothing is dropped`() {
        val rows = listOf(row(0, null), row(1, StreamQuality.UHD), row(2, StreamQuality.UHD, flagged = true), row(3, null, flagged = true))
        val out = StreamRows.demoteMayNotPlay(rows)
        assertEquals(listOf(0, 1, 2, 3), keys(out))
        assertEquals(rows.toSet(), out.toSet())
    }

    @Test
    fun `no flagged rows leaves the list untouched`() {
        val rows = listOf(row(0, StreamQuality.UHD), row(1, StreamQuality.FHD))
        assertEquals(rows, StreamRows.demoteMayNotPlay(rows))
    }
}
