package tv.own.owntv.gambit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.model.HomeConfig
import tv.own.owntv.core.model.HomeRow

/**
 * Setting Home's Live sports row slot once per profile: shown on adult profiles, hidden on kids ones.
 * keepSet itself needs a Context, the DataStore-backed SettingsRepository and Room, so the pieces it is
 * built from are checked here.
 */
class GambitHomeRowsTest {

    private val hiddenBefore = HomeConfig().let { it.copy(hidden = it.hidden + HomeRow.LIVE_SPORTS + HomeRow.TRENDING) }

    @Test
    fun `the row slot is Live sports`() {
        assertEquals(HomeRow.LIVE_SPORTS, GambitHomeRows.ROW)
    }

    @Test
    fun `an adult profile hidden by the first version gets the row back, other choices kept`() {
        val after = GambitHomeRows.change(isKids = false, hiddenBefore)
        assertFalse(HomeRow.LIVE_SPORTS in after.hidden)
        assertTrue(HomeRow.TRENDING in after.hidden)
        assertEquals(hiddenBefore.copy(hidden = after.hidden), after)
    }

    @Test
    fun `a kids profile keeps the row hidden`() {
        assertSame(hiddenBefore, GambitHomeRows.change(isKids = true, hiddenBefore))
    }

    @Test
    fun `a new kids profile gets the row hidden, other choices kept`() {
        val before = HomeConfig().let { it.copy(hidden = it.hidden + HomeRow.TRENDING) }
        val after = GambitHomeRows.change(isKids = true, before)
        assertTrue(HomeRow.LIVE_SPORTS in after.hidden)
        assertTrue(HomeRow.TRENDING in after.hidden)
        assertEquals(before.copy(hidden = after.hidden), after)
    }

    @Test
    fun `showing or hiding twice changes nothing`() {
        val shown = GambitHomeRows.showRow(hiddenBefore)
        assertSame(shown, GambitHomeRows.showRow(shown))
        val hidden = GambitHomeRows.hideRow(HomeConfig())
        assertSame(hidden, GambitHomeRows.hideRow(hidden))
    }

    @Test
    fun `the default config shows Live sports`() {
        // A new adult profile needs no change; if core ever hides the row by default, showRow handles it.
        assertFalse(HomeRow.LIVE_SPORTS in HomeConfig().hidden)
        val fresh = HomeConfig()
        assertSame(fresh, GambitHomeRows.change(isKids = false, fresh))
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
