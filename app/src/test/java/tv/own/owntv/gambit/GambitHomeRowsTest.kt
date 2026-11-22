package tv.own.owntv.gambit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.model.HomeConfig
import tv.own.owntv.core.model.HomeRow

/**
 * Hiding Home's Live sports row once per profile. keepHidden itself needs a Context, the DataStore-backed
 * SettingsRepository and Room, so the pieces it is built from are checked here.
 */
class GambitHomeRowsTest {

    @Test
    fun `the hidden row is Live sports`() {
        assertEquals(HomeRow.LIVE_SPORTS, GambitHomeRows.HIDDEN_ROW)
    }

    @Test
    fun `hiding adds Live sports and keeps every other choice`() {
        val before = HomeConfig().let { it.copy(hidden = it.hidden + HomeRow.TRENDING) }
        val after = GambitHomeRows.hideLiveSports(before)
        assertTrue(HomeRow.LIVE_SPORTS in after.hidden)
        assertTrue(HomeRow.TRENDING in after.hidden)
        assertEquals(before.copy(hidden = after.hidden), after)
    }

    @Test
    fun `hiding twice changes nothing`() {
        val once = GambitHomeRows.hideLiveSports(HomeConfig())
        assertSame(once, GambitHomeRows.hideLiveSports(once))
    }

    @Test
    fun `the default config shows Live sports until hidden`() {
        // If core ever hides it by default, this migration is no longer needed.
        assertFalse(HomeRow.LIVE_SPORTS in HomeConfig().hidden)
    }

    @Test
    fun `every profile not yet handled is pending, adult and kids alike`() {
        assertEquals(listOf(1L, 2L, 3L), GambitHomeRows.pending(listOf(1L, 2L, 3L), emptySet()))
        assertEquals(listOf(3L), GambitHomeRows.pending(listOf(1L, 2L, 3L), setOf(1L, 2L)))
        assertEquals(emptyList<Long>(), GambitHomeRows.pending(listOf(1L, 2L), setOf(1L, 2L, 9L)))
    }

    @Test
    fun `a profile made later is pending once`() {
        val done = setOf(1L, 2L)
        assertEquals(listOf(7L), GambitHomeRows.pending(listOf(1L, 2L, 7L), done))
        assertEquals(emptyList<Long>(), GambitHomeRows.pending(listOf(1L, 2L, 7L), done + 7L))
    }

    @Test
    fun `stored profile ids read back the same, junk is dropped`() {
        val ids = setOf(1L, 42L, 1_000_000_000_123L)
        assertEquals(ids, GambitHomeRows.decode(GambitHomeRows.encode(ids)))
        assertEquals(setOf(5L), GambitHomeRows.decode(setOf("5", "x", "")))
        assertEquals(emptySet<Long>(), GambitHomeRows.decode(null))
    }
}
