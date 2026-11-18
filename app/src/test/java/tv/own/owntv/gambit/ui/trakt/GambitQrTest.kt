package tv.own.owntv.gambit.ui.trakt

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GambitQrTest {
    private fun decode(text: String, margin: Int = 4): String {
        val m = GambitQr.matrix(text, margin)
        val source = RGBLuminanceSource(m.width, m.height, GambitQr.pixels(m))
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.PURE_BARCODE to true)).text
    }

    @Test
    fun the_code_reads_back_as_the_activation_address() {
        assertEquals("https://auth.trakt.tv/activate", decode("https://auth.trakt.tv/activate"))
    }

    @Test
    fun a_fake_host_address_reads_back_too() {
        assertEquals("https://trakt.fake.test/activate", decode("https://trakt.fake.test/activate"))
    }

    @Test
    fun the_white_border_is_kept() {
        val m = GambitQr.matrix("https://auth.trakt.tv/activate", margin = 4)
        for (i in 0 until m.width) {
            assertFalse("top border at $i", m.get(i, 0))
            assertFalse("left border at $i", m.get(0, i))
        }
        assertTrue(m.width == m.height && m.width > 8)
    }

    @Test
    fun pixels_match_the_matrix_size() {
        val m = GambitQr.matrix("https://auth.trakt.tv/activate")
        assertEquals(m.width * m.height, GambitQr.pixels(m).size)
    }
}
