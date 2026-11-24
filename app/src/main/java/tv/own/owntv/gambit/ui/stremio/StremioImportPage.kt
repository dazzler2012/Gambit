package tv.own.owntv.gambit.ui.stremio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.gambit.addons.data.AddonImportFailReason
import tv.own.owntv.gambit.addons.data.AddonImportFailure
import tv.own.owntv.gambit.stremio.ImportCandidate
import tv.own.owntv.gambit.stremio.ImportKind
import tv.own.owntv.gambit.ui.theme.GambitColors
import tv.own.owntv.gambit.ui.trakt.GambitQr
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageTool
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/**
 * More → Addons → Import from Stremio. The TV shows a QR code for Stremio's own sign-in page; once the
 * phone approves, the account's addons are listed by name only (never an address) with a tick each, and
 * Import adds the ticked ones after the addons already on this TV. Back steps back to the start while
 * waiting or choosing, and leaves the page otherwise; leaving the page stops any wait.
 */
@Composable
internal fun StremioImportPage(onClose: () -> Unit, vm: StremioImportViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }

    BackHandler {
        when (state) {
            StremioImportState.Starting, is StremioImportState.Waiting, is StremioImportState.Choosing -> vm.cancel()
            StremioImportState.Importing -> Unit
            else -> onClose()
        }
    }
    DisposableEffect(vm) { onDispose { vm.cancel() } }
    LaunchedEffect(state::class) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    Column(Modifier.fillMaxSize().focusGroup().widthIn(max = 1100.mpx).verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.gambit_stremio_title), style = stageText(46, 800), color = GambitColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            stringResource(R.string.gambit_stremio_description), style = stageText(18, 500), color = GambitColors.Muted,
            modifier = Modifier.padding(top = 10.mpx),
        )
        when (val s = state) {
            is StremioImportState.Choosing -> ChoosingList(s, first, onToggle = vm::toggle)
            else -> Box(
                Modifier
                    .padding(top = 26.mpx)
                    .fillMaxWidth()
                    .background(GambitColors.Rail, RoundedCornerShape(16.mpx))
                    .padding(28.mpx),
            ) {
                Panel(s)
            }
        }
        Row(Modifier.padding(top = 24.mpx), horizontalArrangement = Arrangement.spacedBy(12.mpx)) {
            Buttons(state, first, vm, onClose)
        }
    }
}

/** The buttons under the panel. The first one takes focus whenever the state changes (except while choosing). */
@Composable
private fun Buttons(state: StremioImportState, first: FocusRequester, vm: StremioImportViewModel, onClose: () -> Unit) {
    val firstButton = Modifier.focusRequester(first)
    when (state) {
        StremioImportState.Idle -> {
            StageTool(stringResource(R.string.gambit_stremio_start), onClick = vm::start, icon = OwnTVIcon.PERSON, boxed = true, modifier = firstButton)
            StageTool(stringResource(R.string.gambit_stremio_back), onClick = onClose, boxed = true)
        }
        StremioImportState.Starting, is StremioImportState.Waiting ->
            StageTool(stringResource(R.string.gambit_stremio_cancel), onClick = vm::cancel, icon = OwnTVIcon.CLOSE, boxed = true, modifier = firstButton)
        is StremioImportState.Choosing -> {
            StageTool(
                pluralStringResource(R.plurals.gambit_stremio_import_count, state.ticked.size, state.ticked.size),
                onClick = vm::import, boxed = true,
            )
            StageTool(stringResource(R.string.gambit_stremio_cancel), onClick = vm::cancel, icon = OwnTVIcon.CLOSE, boxed = true)
        }
        StremioImportState.Importing ->
            StageTool(stringResource(R.string.gambit_stremio_importing_button), onClick = { }, boxed = true, modifier = firstButton)
        is StremioImportState.Done ->
            StageTool(stringResource(R.string.gambit_stremio_done), onClick = onClose, boxed = true, modifier = firstButton)
        StremioImportState.Expired, is StremioImportState.Failed -> {
            StageTool(stringResource(R.string.gambit_stremio_retry), onClick = vm::start, icon = OwnTVIcon.REFRESH, boxed = true, modifier = firstButton)
            StageTool(stringResource(R.string.gambit_stremio_back), onClick = onClose, boxed = true)
        }
    }
}

/** What the dark panel says in every state but the preview. */
@Composable
private fun Panel(state: StremioImportState) {
    when (state) {
        StremioImportState.Idle -> Line(stringResource(R.string.gambit_stremio_intro))
        StremioImportState.Starting -> Line(stringResource(R.string.gambit_stremio_getting_code))
        is StremioImportState.Waiting -> WaitingPanel(state)
        is StremioImportState.Choosing -> Unit
        StremioImportState.Importing -> Line(stringResource(R.string.gambit_stremio_importing))
        is StremioImportState.Done -> DonePanel(state)
        StremioImportState.Expired -> Titled(stringResource(R.string.gambit_stremio_failed_title), stringResource(R.string.gambit_stremio_err_expired))
        is StremioImportState.Failed -> Titled(stringResource(R.string.gambit_stremio_failed_title), stringResource(state.error.messageRes()))
    }
}

@Composable
private fun Line(text: String) {
    Text(text, style = stageText(24, 500), color = GambitColors.Muted)
}

@Composable
private fun Titled(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.mpx)) {
        Text(title, style = stageText(30, 700), color = GambitColors.Text)
        Text(body, style = stageText(20, 500), color = GambitColors.Muted)
    }
}

/** QR code on the left; the link, the code and the countdown on the right. */
@Composable
private fun WaitingPanel(waiting: StremioImportState.Waiting) {
    val qr = remember(waiting.link) { GambitQr.bitmap(waiting.link)?.asImageBitmap() }
    var now by remember(waiting.expiresAtMillis) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(waiting.expiresAtMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val left = ((waiting.expiresAtMillis - now) / 1_000).coerceAtLeast(0)

    Row(horizontalArrangement = Arrangement.spacedBy(40.mpx), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(300.mpx).background(Color.White, RoundedCornerShape(12.mpx)).padding(10.mpx)) {
            if (qr != null) {
                Image(
                    bitmap = qr, contentDescription = stringResource(R.string.gambit_stremio_qr_description),
                    modifier = Modifier.fillMaxSize(), filterQuality = FilterQuality.None,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.mpx)) {
            Text(stringResource(R.string.gambit_stremio_scan), style = stageText(20, 500), color = GambitColors.Muted)
            Text(
                waiting.link.removePrefix("https://"), style = stageText(34, 700), color = GambitColors.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(stringResource(R.string.gambit_stremio_code), style = stageText(20, 500), color = GambitColors.Muted, modifier = Modifier.padding(top = 10.mpx))
            Text(
                waiting.code,
                style = stageText(76, 800).copy(fontFamily = FontFamily.Monospace, letterSpacing = 8.sp),
                color = GambitColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.gambit_stremio_waiting, (left / 60).toInt(), (left % 60).toInt()),
                style = stageText(18, 500), color = GambitColors.Dim, modifier = Modifier.padding(top = 6.mpx),
            )
        }
    }
}

/** One tick row per addon, by name only. Already-added rows are shown but can't be ticked. */
@Composable
private fun ChoosingList(state: StremioImportState.Choosing, first: FocusRequester, onToggle: (Int) -> Unit) {
    Column(Modifier.padding(top = 24.mpx)) {
        if (state.preview.candidates.isEmpty()) {
            Text(stringResource(R.string.gambit_stremio_nothing), style = stageText(20, 500), color = GambitColors.Muted)
        }
        state.preview.candidates.forEachIndexed { i, row ->
            val ticked = i in state.ticked
            StageTool(
                text = rowName(row), boxed = true, valueAccent = ticked,
                value = stringResource(
                    when {
                        !row.selectable -> R.string.gambit_stremio_row_already
                        ticked -> R.string.gambit_stremio_row_add
                        else -> R.string.gambit_stremio_row_skip
                    },
                ),
                onClick = { onToggle(i) },
                modifier = Modifier.padding(bottom = 6.mpx).let { if (i == 0) it.focusRequester(first) else it },
            )
        }
        if (state.preview.unsupported > 0) {
            Text(
                pluralStringResource(R.plurals.gambit_stremio_unsupported, state.preview.unsupported, state.preview.unsupported),
                style = stageText(18, 500), color = GambitColors.Dim, modifier = Modifier.padding(top = 10.mpx),
            )
        }
    }
}

@Composable
private fun rowName(row: ImportCandidate): String {
    val name = row.name ?: stringResource(R.string.gambit_stremio_unnamed)
    return if (row.kind == ImportKind.STREMIO_OWN) stringResource(R.string.gambit_stremio_row_own, name) else name
}

/** One plain sentence per thing that happened; counts that are zero are left out. */
@Composable
private fun DonePanel(done: StremioImportState.Done) {
    val r = done.report
    Column(verticalArrangement = Arrangement.spacedBy(10.mpx)) {
        Text(
            if (r.added > 0) pluralStringResource(R.plurals.gambit_stremio_done_added, r.added, r.added)
            else stringResource(R.string.gambit_stremio_done_none),
            style = stageText(30, 700), color = GambitColors.Text,
        )
        if (r.failed > 0) {
            Sentence(pluralStringResource(R.plurals.gambit_stremio_done_failed, r.failed, r.failed))
            Column(Modifier.padding(start = 20.mpx), verticalArrangement = Arrangement.spacedBy(4.mpx)) {
                r.failures.forEach { Sentence(failureLine(it)) }
            }
        }
        if (r.alreadyAdded > 0) Sentence(pluralStringResource(R.plurals.gambit_stremio_done_already, r.alreadyAdded, r.alreadyAdded))
        if (done.unsupported > 0) Sentence(pluralStringResource(R.plurals.gambit_stremio_unsupported, done.unsupported, done.unsupported))
        if (done.unreadable > 0) Sentence(pluralStringResource(R.plurals.gambit_stremio_done_unreadable, done.unreadable, done.unreadable))
    }
}

/** "Name: couldn't be reached" and so on. The addon's name and a category only, never its address. */
@Composable
private fun failureLine(failure: AddonImportFailure): String {
    val name = failure.name ?: stringResource(R.string.gambit_stremio_unnamed)
    return when (val reason = failure.reason) {
        AddonImportFailReason.Unreachable -> stringResource(R.string.gambit_stremio_fail_unreachable, name)
        is AddonImportFailReason.Refused -> stringResource(R.string.gambit_stremio_fail_refused, name, reason.httpCode)
        AddonImportFailReason.NotAnAddon -> stringResource(R.string.gambit_stremio_fail_not_addon, name)
    }
}

@Composable
private fun Sentence(text: String) {
    Text(text, style = stageText(20, 500), color = GambitColors.Muted)
}
