package tv.own.owntv.gambit.ui

import androidx.compose.runtime.Composable
import tv.own.owntv.gambit.text.GambitText
import tv.own.owntv.ui.components.MoveOrderOverlay
import tv.own.owntv.ui.components.OwnTVPopup

/**
 * OwnTV's [MoveOrderOverlay] in a popup window, so it covers the whole app — the top-right cluster (Last
 * channel, the playlist, the clock) is drawn by the shell after the screens and showed through it.
 * Full screen at the app's own scale, as the overlay was drawn before; its own keys and focus are kept
 * (Back is its own, so the window does not also dismiss on it). Names are shown with entities decoded.
 * The overlay is built from Stage parts, so Gambit's flat focus fill already colours it.
 */
@Composable
fun GambitMoveOverlay(
    title: String,
    itemNames: List<String>,
    activeIndex: Int,
    onMove: (from: Int, to: Int) -> Unit,
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
        MoveOrderOverlay(
            title = title,
            itemNames = itemNames.map(GambitText::decode),
            activeIndex = activeIndex,
            onMove = onMove,
            onCommit = onCommit,
            onCancel = onCancel,
        )
    }
}
