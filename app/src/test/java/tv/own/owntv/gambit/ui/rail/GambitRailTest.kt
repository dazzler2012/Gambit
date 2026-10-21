package tv.own.owntv.gambit.ui.rail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.settings.SettingsRepository.NavSize
import tv.own.owntv.ui.theme.mpx

/** The rail's widths decide where every Gambit screen starts its content. */
class GambitRailTest {

    @Test
    fun `the closed rail fits inside the 128 OwnTV reserves`() {
        assertTrue(GambitRailClosedWidth <= 128.mpx)
    }

    @Test
    fun `open widths grow with the Navigation Size setting`() {
        val widths = NavSize.entries.map(::gambitRailOpenWidth)
        assertEquals(widths.sortedBy { it.value }, widths)
        assertEquals("one width per size", widths.size, widths.toSet().size)
    }

    @Test
    fun `the open rail never covers more than OwnTV's open rail did at Normal`() {
        // OwnTV's open rail at Normal: 24 + 280.
        assertTrue(gambitRailOpenWidth(NavSize.NORMAL) <= 304.mpx)
    }

    @Test
    fun `the docked reserve is the open width plus the gap, for every size`() {
        for (size in NavSize.entries) {
            assertEquals(size.name, gambitRailOpenWidth(size) + 16.mpx, gambitRailDockedReserve(size))
        }
    }
}
