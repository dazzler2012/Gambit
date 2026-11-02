package tv.own.owntv.gambit.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two shades derived from the accent: the dark focus fill and the light text tint. */
class GambitTokensTest {

    private val accent = Color(0xFF66BB6A)

    @Test
    fun `the focus fill is darker than the accent`() {
        assertTrue(gambitFocusFill(accent).luminance() < accent.luminance())
    }

    @Test
    fun `the light tint is lighter than the accent`() {
        assertTrue(gambitAccentLight(accent).luminance() > accent.luminance())
    }

    @Test
    fun `white text on the focus fill of the default accent meets 4,5 to 1`() {
        val fill = gambitFocusFill(accent).luminance()
        val ratio = (1.0 + 0.05) / (fill + 0.05)
        assertTrue("contrast $ratio", ratio >= 4.5)
    }

    @Test
    fun `both shades stay opaque`() {
        assertEquals(1f, gambitFocusFill(accent).alpha, 0.001f)
        assertEquals(1f, gambitAccentLight(accent).alpha, 0.001f)
    }
}
