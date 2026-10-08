package tv.own.owntv.gambit.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.i18n.HorizontalDirection
import tv.own.owntv.core.i18n.horizontalDirection
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.gambit.ui.theme.GambitColors
import tv.own.owntv.gambit.ui.theme.gambitFocusFill
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/** The group panel's width, to the left of the channel list. */
internal val GambitCategoryPanelWidth = 480.mpx

/**
 * Every Live TV group over the playing video (the second Left), in Gambit's look. A drop-in for
 * OwnTV's CategoryBrowserOverlay: OK opens that group's channels ([onSelect]), and Back or Left goes
 * back ([onDismiss]) without changing anything. The playing channel's group
 * is in the accent and focused first.
 */
@Composable
fun GambitCategoryList(
    categories: List<Pair<LiveKey, String>>,
    currentKey: LiveKey?,
    onSelect: (LiveKey) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Full screen like the overlay it replaces; the panel sits at the start.
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { GambitCategoryListPanel(categories, currentKey, onSelect, onDismiss) }
}

/** The panel itself, without the full-screen box (laid out beside the channel list by GambitChannelPanels). */
@Composable
internal fun GambitCategoryListPanel(
    categories: List<Pair<LiveKey, String>>,
    currentKey: LiveKey?,
    onSelect: (LiveKey) -> Unit,
    onDismiss: () -> Unit,
    /** Right also leaves, onto the channel list beside it (only when there is one: GambitChannelPanels). */
    endDismisses: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val layoutDirection = LocalLayoutDirection.current
    val currentIndex = remember(categories, currentKey) { categories.indexOfFirst { it.first == currentKey }.coerceAtLeast(0) }
    val listState = rememberLazyListState()
    val focusCurrent = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { listState.scrollToItem(currentIndex) }
        runCatching { focusCurrent.requestFocus() }
    }
    BackHandler { onDismiss() }
    Column(
        modifier
            .width(GambitCategoryPanelWidth)
            .fillMaxHeight()
            .background(GambitPanelFill)
            .onPreviewKeyEvent { e ->
                // Left goes back to the channels; so does Right when they are on screen beside the groups.
                val dir = e.key.horizontalDirection(layoutDirection)
                val out = dir == HorizontalDirection.START || (endDismisses && dir == HorizontalDirection.END)
                if (e.type == KeyEventType.KeyDown && out) { onDismiss(); true } else false
            },
    ) {
        Box(Modifier.fillMaxWidth().height(76.mpx), contentAlignment = Alignment.CenterStart) {
            Text(stringResource(R.string.content_category_browser_title), style = stageText(26, 600), color = GambitColors.Muted, maxLines = 1, modifier = Modifier.padding(horizontal = 32.mpx))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.mpx, vertical = 6.mpx),
            verticalArrangement = Arrangement.spacedBy(4.mpx),
        ) {
            items(categories, key = { it.first.toString() }) { (key, name) ->
                GambitCategoryRow(
                    name = name,
                    current = key == currentKey,
                    onClick = { onSelect(key) },
                    modifier = if (key == categories.getOrNull(currentIndex)?.first) Modifier.focusRequester(focusCurrent) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun GambitCategoryRow(name: String, current: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val accent = stageAccent.accent
    Box(
        modifier
            .fillMaxWidth()
            .height(76.mpx)
            .background(if (focused) gambitFocusFill(accent) else Color.Transparent, RoundedCornerShape(10.mpx))
            .onKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (ok && e.type == KeyEventType.KeyUp) { onClick(); true } else ok
            }
            .focusable(interactionSource = interaction)
            .padding(horizontal = 32.mpx),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            name, style = stageText(26, 500),
            color = if (current && !focused) accent else GambitColors.Text,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
