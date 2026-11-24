package tv.own.owntv.gambit.ui.addons

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.gambit.ui.theme.GambitColors
import tv.own.owntv.ui.stage.StageTool
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/**
 * Kids access: the parent picks, per kids profile, the addon catalogues it may see. Reached only through
 * the adult PIN prompt in More → Addons, and only while the release lock allows it; it is shown for this
 * visit only (leaving it, switching profile or restarting locks it again). Catalogue rows show the name,
 * type and addon name, never an address.
 */
@Composable
internal fun KidsAccessPage(onClose: () -> Unit, vm: KidsAccessViewModel = koinViewModel()) {
    val profiles by vm.kidsProfiles.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val approvals by vm.approvals.collectAsStateWithLifecycle()
    var selectedId by remember { mutableStateOf<Long?>(null) }
    // A profile switched on here but with nothing ticked yet: it has no stored entry, so it is still Closed.
    var switchedOn by remember { mutableStateOf(setOf<Long>()) }
    val first = remember { FocusRequester() }

    val selected = profiles.firstOrNull { it.id == selectedId }
    LaunchedEffect(selected == null, selectedId) {
        if (selectedId != null && selected == null) selectedId = null
        withFrameNanos { }
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    BackHandler { if (selectedId != null) selectedId = null else onClose() }

    Column(Modifier.fillMaxSize().focusGroup().widthIn(max = 1100.mpx).verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.gambit_kids_access_title), style = stageText(46, 800), color = GambitColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            stringResource(R.string.gambit_kids_access_description), style = stageText(18, 500), color = GambitColors.Muted,
            modifier = Modifier.padding(top = 10.mpx),
        )
        // Approvals follow one exact addon setup; settings changed on the addon's own server can't be seen.
        Text(
            stringResource(R.string.gambit_kids_access_settings_note), style = stageText(16, 500), color = GambitColors.Dim,
            modifier = Modifier.padding(top = 6.mpx),
        )
        Column(Modifier.padding(top = 24.mpx)) {
            if (selected == null) {
                if (profiles.isEmpty()) {
                    Text(stringResource(R.string.gambit_kids_access_no_kids), style = stageText(20, 500), color = GambitColors.Dim)
                }
                profiles.forEachIndexed { i, p ->
                    val count = approvals[p.id].orEmpty().size
                    StageTool(
                        text = p.name, onClick = { selectedId = p.id }, boxed = true,
                        value = if (count > 0) pluralStringResource(R.plurals.gambit_kids_access_count, count, count) else stringResource(R.string.gambit_kids_access_off),
                        modifier = Modifier.padding(top = if (i == 0) 0.mpx else 8.mpx).let { if (i == 0) it.focusRequester(first) else it },
                    )
                }
                StageTool(
                    stringResource(R.string.gambit_kids_access_back), onClick = onClose, boxed = true,
                    modifier = Modifier.padding(top = 18.mpx).let { if (profiles.isEmpty()) it.focusRequester(first) else it },
                )
            } else {
                val keys = approvals[selected.id].orEmpty()
                val on = keys.isNotEmpty() || selected.id in switchedOn
                Text(selected.name, style = stageText(28, 700), color = GambitColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StageTool(
                    text = stringResource(R.string.gambit_kids_access_allow), boxed = true, valueAccent = on,
                    value = stringResource(if (on) R.string.gambit_kids_access_on else R.string.gambit_kids_access_off),
                    onClick = {
                        if (on) { vm.turnOff(selected.id); switchedOn = switchedOn - selected.id } else switchedOn = switchedOn + selected.id
                    },
                    modifier = Modifier.padding(top = 14.mpx).focusRequester(first),
                )
                if (on) {
                    Text(stringResource(R.string.gambit_kids_access_catalogues), style = stageText(22, 700), color = GambitColors.Text, modifier = Modifier.padding(top = 22.mpx))
                    if (groups.isEmpty()) {
                        Text(stringResource(R.string.gambit_kids_access_no_catalogues), style = stageText(18, 500), color = GambitColors.Muted, modifier = Modifier.padding(top = 8.mpx))
                    }
                    groups.forEach { g ->
                        Text(g.addonName, style = stageText(18, 600), color = GambitColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.mpx, bottom = 6.mpx))
                        g.catalogs.forEach { c ->
                            val ticked = c.key in keys
                            StageTool(
                                text = stringResource(R.string.gambit_kids_access_catalogue, c.name, c.type), boxed = true, valueAccent = ticked,
                                value = stringResource(if (ticked) R.string.gambit_kids_access_on else R.string.gambit_kids_access_off),
                                onClick = { vm.setApproved(selected.id, c.key, !ticked) },
                                modifier = Modifier.padding(bottom = 6.mpx),
                            )
                        }
                    }
                    if (keys.isEmpty()) {
                        Text(stringResource(R.string.gambit_kids_access_none_ticked), style = stageText(18, 500), color = GambitColors.Muted, modifier = Modifier.padding(top = 12.mpx))
                    }
                }
                StageTool(
                    stringResource(R.string.gambit_kids_access_back), onClick = { selectedId = null }, boxed = true,
                    modifier = Modifier.padding(top = 18.mpx),
                )
            }
        }
    }
}
