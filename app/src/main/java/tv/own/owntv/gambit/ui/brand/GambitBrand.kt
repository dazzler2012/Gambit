package tv.own.owntv.gambit.ui.brand

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import tv.own.owntv.R

/*
 * Gambit's in-app logo, drawn wherever OwnTV draws its own: `BrandMark` and `Wordmark` in
 * ui/components/BrandLockup.kt hand over to these when GambitLook.Brand is on, so the rail, Home,
 * the setup wizard and the preview pane need no changes of their own.
 */

/** The pawn mark at [size] (square, no background). The icon pickers' colour and accent options do not apply. */
@Composable
internal fun GambitMark(size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.gambit_mark), contentDescription = null, modifier = modifier.size(size))
}

/** The "Gambit" wordmark at [width] (436 × 96 drawing), in [color]. */
@Composable
internal fun GambitWordmark(width: Dp, modifier: Modifier = Modifier, color: Color = Color.White) {
    Image(
        painterResource(R.drawable.gambit_wordmark), contentDescription = null,
        modifier = modifier.width(width).height(width * 96f / 436f),
        colorFilter = ColorFilter.tint(color),
    )
}
