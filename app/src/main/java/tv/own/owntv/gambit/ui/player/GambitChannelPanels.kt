package tv.own.owntv.gambit.ui.player

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.parser.XtEpgEntry

/**
 * The in-player Left panels: the channel list, and on the second Left the group list beside it (not in
 * place of it), as TiviMate lays them out. While the groups are open they hold focus and the channel
 * list stays on screen without taking it; leaving the groups hands focus back to the playing channel.
 */
@Composable
fun GambitChannelPanels(
    showCategories: Boolean,
    categories: List<Pair<LiveKey, String>>,
    currentCategory: LiveKey?,
    onSelectCategory: (LiveKey) -> Unit,
    onHideCategories: () -> Unit,
    channels: List<ChannelEntity>,
    currentId: Long?,
    onSelectChannel: (ChannelEntity) -> Unit,
    onDismiss: () -> Unit,
    onOpenCategories: () -> Unit,
    programmes: Map<Long, XtEpgEntry>,
    title: String?,
    showNumbers: Boolean,
    providerNames: Map<Long, String>,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxHeight()) {
        if (showCategories) {
            GambitCategoryListPanel(
                categories = categories,
                currentKey = currentCategory,
                onSelect = onSelectCategory,
                onDismiss = onHideCategories,
                endDismisses = channels.isNotEmpty(),
            )
        }
        if (channels.isNotEmpty()) {
            GambitChannelListPanel(
                channels = channels,
                currentId = currentId,
                onSelect = onSelectChannel,
                onDismiss = onDismiss,
                programmes = programmes,
                title = title,
                alignEnd = false,
                showNumbers = showNumbers,
                providerNames = providerNames,
                onOpenCategories = onOpenCategories,
                active = !showCategories,
            )
        }
    }
}
