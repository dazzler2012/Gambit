package tv.own.owntv.gambit.ui.trakt

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code made on the TV with ZXing (already in the app for Remote setup), black modules on white.
 * One pixel per module plus the white [margin]: a small bitmap that the screen scales up without
 * smoothing, so the squares stay sharp.
 */
internal object GambitQr {
    private const val BLACK = -0x1000000
    private const val WHITE = -0x1

    /** The modules of [text], including the white border; true is a dark module. */
    fun matrix(text: String, margin: Int = 4): BitMatrix {
        val hints = mapOf(
            EncodeHintType.MARGIN to margin,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        // Width and height 0: the smallest matrix, one pixel per module.
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    }

    /** [matrix] as ARGB pixels, row by row. */
    fun pixels(matrix: BitMatrix): IntArray {
        val out = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) out[y * matrix.width + x] = if (matrix.get(x, y)) BLACK else WHITE
        return out
    }

    /** The code as a bitmap, or null when [text] can't be encoded (too long). */
    fun bitmap(text: String, margin: Int = 4): Bitmap? = runCatching {
        val m = matrix(text, margin)
        Bitmap.createBitmap(pixels(m), m.width, m.height, Bitmap.Config.ARGB_8888)
    }.getOrNull()
}
