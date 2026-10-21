package tv.own.owntv.gambit.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity

/**
 * Provider names reach the screen through [GambitText.decode]. A mistake here does not crash anything:
 * a group simply reads "&#127875; Halloween" again, or a name loses text it should have kept.
 */
class GambitTextTest {

    @Test
    fun `text without an ampersand comes back as the same instance`() {
        val s = "BBC One HD"
        assertSame(s, GambitText.decode(s))
    }

    @Test
    fun `decimal numeric entities decode, including above the BMP`() {
        assertEquals("LIVE | 🎃 Halloween Movies", GambitText.decode("LIVE | &#127875; Halloween Movies"))
        assertEquals("A", GambitText.decode("&#65;"))
    }

    @Test
    fun `hex numeric entities decode with either x`() {
        assertEquals("🎃", GambitText.decode("&#x1F383;"))
        assertEquals("🎃", GambitText.decode("&#X1f383;"))
    }

    @Test
    fun `the common named entities decode`() {
        assertEquals("Tom & Jerry", GambitText.decode("Tom &amp; Jerry"))
        assertEquals("<b>", GambitText.decode("&lt;b&gt;"))
        assertEquals("\"Q\" 'A'", GambitText.decode("&quot;Q&quot; &apos;A&apos;"))
        assertEquals("a b", GambitText.decode("a&nbsp;b"))
    }

    @Test
    fun `several entities in one name all decode`() {
        assertEquals("UK & IE | 🎃", GambitText.decode("UK &amp; IE | &#x1F383;"))
    }

    @Test
    fun `decoding is single pass, so double-encoded text keeps one level`() {
        assertEquals("&amp;", GambitText.decode("&amp;amp;"))
    }

    @Test
    fun `unknown named entities and bare ampersands are left as sent`() {
        assertEquals("&copy; 2026", GambitText.decode("&copy; 2026"))
        assertEquals("Rock & Roll", GambitText.decode("Rock & Roll"))
        assertEquals("&amp no semicolon", GambitText.decode("&amp no semicolon"))
    }

    @Test
    fun `code points that are not characters are left as sent`() {
        assertEquals("&#0;", GambitText.decode("&#0;"))
        assertEquals("&#xD800;", GambitText.decode("&#xD800;"))      // lone surrogate
        assertEquals("&#55296;", GambitText.decode("&#55296;"))      // the same, decimal
        assertEquals("&#x110000;", GambitText.decode("&#x110000;"))  // past Unicode
        assertEquals("&#9999999;", GambitText.decode("&#9999999;"))  // seven digits, past Unicode
    }

    @Test
    fun `numbers too long for the pattern are not touched`() {
        assertEquals("&#12345678;", GambitText.decode("&#12345678;"))
        assertEquals("&#x1234567;", GambitText.decode("&#x1234567;"))
    }

    @Test
    fun `decodeOrNull passes null through`() {
        assertNull(GambitText.decodeOrNull(null))
        assertEquals("A & B", GambitText.decodeOrNull("A &amp; B"))
    }

    @Test
    fun `a channel's shown name is its stored name decoded`() {
        val channel = ChannelEntity(id = 1, sourceId = 1, categoryId = 10, name = "Film4 &#43;1", streamUrl = "http://x/1.ts", remoteId = "1")
        assertEquals("Film4 +1", channel.shownName)
        assertEquals("Film4 &#43;1", channel.name)
    }
}
