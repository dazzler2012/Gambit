package tv.own.owntv.gambit.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.parser.XtEpgEntry
import tv.own.owntv.ui.theme.mpx

/**
 * The in-player Left panels: the channel list, and on the second Left the group list beside it (not in
 * place of it), as TiviMate lays them out. While the groups are open they hold focus and the channel
 * list stays on screen without taking it; leaving the groups hands focus back to the playing channel.
 *
 * Beside the channel list, the schedule of the channel under the cursor (about [ScheduleSettleMs] after
 * the cursor stops, so a quick scroll reads nothing), with the programme card top right. Right moves
 * into the schedule; Left or Back comes back to the same channel. The schedule hides while the groups
 * are open. [loadSchedule] reads one channel's programmes for a window; [loadDescription] a programme's
 * description, which the schedule read leaves out.
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
    loadSchedule: (suspend (ChannelEntity, Long, Long) -> List<EpgProgrammeEntity>)? = null,
    loadDescription: (suspend (Long) -> String?)? = null,
) {
    // The channel under the cursor, and the one the schedule shows: the first at once, later ones once
    // the cursor has rested on them.
    var focusedChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    var scheduleChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    LaunchedEffect(focusedChannel) {
        val ch = focusedChannel ?: return@LaunchedEffect
        if (scheduleChannel != null) delay(ScheduleSettleMs)
        scheduleChannel = ch
    }
    var scheduleActive by remember { mutableStateOf(false) }
    var focusedProgramme by remember { mutableStateOf<EpgProgrammeEntity?>(null) }
    // The channel the schedule was left from, for focus to come back to. The groups reset it, so that
    // leaving them still lands on the playing channel.
    var returnTo by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(showCategories) { if (showCategories) { scheduleActive = false; returnTo = null } }

    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val cache = remember { ScheduleCache() }
    // Kept while the next channel's schedule loads, so the column does not blink empty in between.
    val schedule by produceState<Pair<ChannelEntity, List<EpgProgrammeEntity>>?>(null, scheduleChannel, loadSchedule) {
        val ch = scheduleChannel ?: return@produceState
        val load = loadSchedule ?: return@produceState
        cache[ch.id]?.let { value = ch to it; return@produceState }
        val t = System.currentTimeMillis()
        val list = runCatching { load(ch, t - ScheduleBackMs, t + ScheduleAheadMs) }.getOrDefault(emptyList())
            .sortedBy { it.startMs }
        if (list.isNotEmpty()) cache[ch.id] = list
        value = ch to list
    }
    val shown = schedule
    val showSchedule = !showCategories && channels.isNotEmpty() && shown != null

    // The card: the programme under the cursor in the schedule, otherwise the one on now.
    val cardProgramme = if (scheduleActive) focusedProgramme
        else shown?.second?.firstOrNull { now in it.startMs until it.stopMs }
    val described by produceState(cardProgramme, cardProgramme?.id, cardProgramme?.startMs) {
        value = cardProgramme
        val p = cardProgramme ?: return@produceState
        val load = loadDescription ?: return@produceState
        if (p.description.isNullOrBlank() && p.id > 0) {
            runCatching { load(p.id) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { value = p.copy(description = it) }
        }
    }

    Box(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxHeight()) {
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
                    active = !showCategories && !scheduleActive,
                    onFocusChannel = { focusedChannel = it },
                    onOpenSchedule = {
                        // Only into the schedule of the channel under the cursor, and only one with programmes.
                        val ch = focusedChannel
                        if (showSchedule && ch != null && shown?.first?.id == ch.id && shown.second.isNotEmpty()) {
                            returnTo = ch.id
                            scheduleActive = true
                        }
                    },
                    focusId = returnTo,
                )
            }
            if (showSchedule && shown != null) {
                GambitSchedulePanel(
                    channel = shown.first,
                    subtitle = gambitListHeader(shown.first, title, providerNames),
                    programmes = shown.second,
                    now = now,
                    showNumber = showNumbers,
                    active = scheduleActive,
                    onFocusProgramme = { focusedProgramme = it },
                    onTune = { scheduleActive = false; onSelectChannel(shown.first) },
                    onExit = { scheduleActive = false; focusedProgramme = null },
                )
            }
        }
        if (showSchedule && shown != null) {
            GambitProgrammeCard(
                programme = described,
                fallbackTitle = shown.first.name,
                now = now,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 48.mpx, end = 40.mpx),
            )
        }
    }
}

/** How long the cursor rests on a channel before the schedule follows it. */
private const val ScheduleSettleMs = 300L

/** The schedule's window: from a few hours back (as TiviMate opens it) to three days ahead. */
private const val ScheduleBackMs = 3 * 3_600_000L
private const val ScheduleAheadMs = 3 * 24 * 3_600_000L

/** The last few channels' schedules, so going back and forth reads nothing again. */
private class ScheduleCache : LinkedHashMap<Long, List<EpgProgrammeEntity>>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<EpgProgrammeEntity>>?) = size > 24
}
