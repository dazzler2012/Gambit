package tv.own.owntv.gambit.ui.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.gambit.ui.theme.GambitColors
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/*
 * The group list (Live TV, the TV Guide's category sheet, Movies, Series) in TiviMate's look, drawn for
 * OwnTV's LiveCategories, which keeps search, focus, CH± and long-press as they are: plain rows with the
 * provider's full group name, no icons and no counts; the focused row on the green focus fill, the open
 * group on grey; the playlist's name as the heading over the groups.
 */

/** The sheets' panel (Live TV's, the TV Guide's): flat, in place of the glass. */
fun gambitGroupSheetPanel(): Modifier = Modifier.background(GambitColors.Rail, RoundedCornerShape(14.mpx))

/**
 * One group. [label] is what to show — the provider's full name for a group ("UK | ENTERTAINMENT"), the
 * entry's own name for the fixed ones (Favorites, All channels …). [mark] is the playlist's colour dot,
 * shown only when several playlists are mixed in the list.
 */
@Composable
fun GambitGroupRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    mark: Color? = null,
) {
    val r = 10.mpx
    StageSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        radius = r,
        modifier = modifier.fillMaxWidth().padding(bottom = 2.mpx).heightIn(min = 64.mpx),
        idle = if (selected) Modifier.background(GambitColors.CellNow, RoundedCornerShape(r)) else Modifier,
    ) { _ ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.mpx, vertical = 8.mpx),
            horizontalArrangement = Arrangement.spacedBy(12.mpx),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tv.own.owntv.gambit.text.GambitText.decode(label), style = stageText(26, 400), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (mark != null) Box(Modifier.size(8.mpx).background(mark, RoundedCornerShape(50)))
        }
    }
}

/** The heading over the groups: the playlist's name, or "Groups" when several playlists are active. */
@Composable
fun GambitGroupHeading() {
    val liveVm: LiveViewModel = koinViewModel()
    val names by liveVm.sourceNames.collectAsStateWithLifecycle()
    val heading = names.values.singleOrNull() ?: stringResource(R.string.content_live_groups)
    Text(
        heading, style = stageText(26, 700), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 22.mpx, top = 18.mpx, bottom = 10.mpx),
    )
}
