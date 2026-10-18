package tv.own.owntv.gambit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import tv.own.owntv.gambit.text.GambitText
import tv.own.owntv.gambit.ui.theme.gambitFocusFill
import tv.own.owntv.ui.components.MoveOrderOverlay
import tv.own.owntv.ui.components.OwnTVPopup
import tv.own.owntv.ui.theme.LocalOwnTVColors

/**
 * OwnTV's [MoveOrderOverlay] in a popup window, so it covers the whole app — the top-right cluster (Last
 * channel, the playlist, the clock) is drawn by the shell after the screens and showed through it.
 * Full screen at the app's own scale, as the overlay was drawn before; its own keys and focus are kept
 * (Back is its own, so the window does not also dismiss on it). Names are shown with entities decoded, and
 * the active row and Save are Gambit's darkened green with white text.
 */
@Composable
fun GambitMoveOverlay(
    title: String,
    itemNames: List<String>,
    activeIndex: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
) {
    OwnTVPopup(
        onDismissRequest = onCancel,
        dismissOnBackPress = false,
        dismissOnClickOutside = false,
        stageLayout = true,
        stageScaled = false,
    ) {
        // The active row and Save fill with OwnTV's primary colour, and Cancel focused with its
        // container: all three become Gambit's darkened green with white text.
        val colors = LocalOwnTVColors.current
        val fill = gambitFocusFill(colors.primary)
        CompositionLocalProvider(
            LocalOwnTVColors provides colors.copy(primary = fill, onPrimary = Color.White, primaryContainer = fill, onPrimaryContainer = Color.White),
        ) {
            MoveOrderOverlay(
                title = title,
                itemNames = itemNames.map(GambitText::decode),
                activeIndex = activeIndex,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onCommit = onCommit,
                onCancel = onCancel,
            )
        }
    }
}
