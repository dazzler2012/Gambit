package tv.own.owntv.gambit.ui.discovery

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Discovery grids' scroll rule: margin 80, slack 16, a window of 880 and posters 328 tall. */
class DiscoveryScrollTest {

    private fun scroll(offset: Float, size: Float = 328f, window: Float = 880f) =
        discoveryScrollDistance(offset, size, window, margin = 80f, slack = 16f)

    private fun assertScroll(expected: Float, actual: Float) = assertEquals(expected, actual, 0.001f)

    @Test
    fun `a poster inside both margins does not scroll`() {
        assertScroll(0f, scroll(80f))
        assertScroll(0f, scroll(276f))
        assertScroll(0f, scroll(472f)) // its bottom is 80 from the window's
    }

    @Test
    fun `the focus zoom's shift into a margin does not scroll`() {
        assertScroll(0f, scroll(69f))
        assertScroll(0f, scroll(483f))
    }

    @Test
    fun `further into a margin than the slack scrolls back to the whole margin`() {
        assertScroll(-20f, scroll(60f))
        assertScroll(20f, scroll(492f))
    }

    @Test
    fun `a row below lands 80 above the bottom`() {
        assertScroll(358f, scroll(830f))
    }

    @Test
    fun `a row above lands 80 below the top`() {
        assertScroll(-424f, scroll(-344f))
    }

    @Test
    fun `a window too small for both margins centres the poster`() {
        assertScroll(0f, scroll(36f, window = 400f))
        assertScroll(364f, scroll(400f, window = 400f))
    }

    @Test
    fun `a poster that exactly fills the window is not left partly outside it`() {
        assertScroll(10f, scroll(10f, window = 328f))
    }

    @Test
    fun `a poster taller than the window aligns its top`() {
        assertScroll(50f, scroll(50f, size = 500f, window = 400f))
        assertScroll(0f, scroll(0f, size = 500f, window = 400f))
    }
}
