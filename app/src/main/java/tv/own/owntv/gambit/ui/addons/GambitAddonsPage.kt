package tv.own.owntv.gambit.ui.addons

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.gambit.addons.data.AddonAddError
import tv.own.owntv.gambit.addons.data.AddonAddResult
import tv.own.owntv.gambit.addons.data.InstalledAddon
import tv.own.owntv.gambit.addons.net.AddonUrl
import tv.own.owntv.gambit.profiles.AdultPinReason
import tv.own.owntv.gambit.profiles.rememberAdultPinGate
import tv.own.owntv.gambit.ui.stremio.StremioImportPage
import tv.own.owntv.gambit.ui.theme.GambitColors
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.TextInputDialog
import tv.own.owntv.ui.stage.StageConfirm
import tv.own.owntv.ui.stage.StageNotice
import tv.own.owntv.ui.stage.StagePopup
import tv.own.owntv.ui.stage.StagePopupOption
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.stage.StageTool
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/**
 * More → Addons: the Stremio addons on this device (shared by its adult profiles), in the order they are
 * asked. Each shows its name and its host only: the address holds the user's keys and is never shown
 * again once saved. Add one by typing its address with the remote. Nothing on a kids profile.
 */
@Composable
internal fun GambitAddonsPage(entry: FocusRequester, vm: AddonsViewModel = koinViewModel()) {
    val kids by vm.kids.collectAsStateWithLifecycle()
    if (kids) return
    val addons by vm.addons.collectAsStateWithLifecycle()
    val unreadable by vm.unreadable.collectAsStateWithLifecycle()
    val typed by vm.typed.collectAsStateWithLifecycle()
    var typing by remember { mutableStateOf(false) }
    // Kids access: an adult PIN every time it is opened; leaving the page (or switching profile) locks it again.
    val kidsEntry by vm.kidsAccessEntry.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val pinGate = rememberAdultPinGate(profiles)
    var kidsOpen by remember { mutableStateOf(false) }
    var noPin by remember { mutableStateOf(false) }
    var backFromKids by remember { mutableStateOf(false) }
    val kidsFocus = remember { FocusRequester() }
    LaunchedEffect(backFromKids) {
        if (!backFromKids) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        runCatching { kidsFocus.requestFocus() }
        backFromKids = false
    }
    if (kidsOpen) {
        BackHandler { kidsOpen = false; backFromKids = true }
        KidsAccessPage(onClose = { kidsOpen = false; backFromKids = true })
        return
    }
    // Import from Stremio: adult profiles only (this page shows nothing on a kids profile, see above).
    var stremioOpen by remember { mutableStateOf(false) }
    var backFromStremio by remember { mutableStateOf(false) }
    val stremioFocus = remember { FocusRequester() }
    LaunchedEffect(backFromStremio) {
        if (!backFromStremio) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        runCatching { stremioFocus.requestFocus() }
        backFromStremio = false
    }
    if (stremioOpen) {
        StremioImportPage(onClose = { stremioOpen = false; backFromStremio = true })
        return
    }
    var actionsFor by remember { mutableStateOf<InstalledAddon?>(null) }
    var removing by remember { mutableStateOf<InstalledAddon?>(null) }
    // Each row keeps its own focus target, so after Move up / Move down focus follows the moved addon,
    // and after Remove it lands on the addon that took its place (or the one above, if it was last).
    val rowFocus = remember { mutableMapOf<AddonUrl, FocusRequester>() }
    var refocus by remember { mutableStateOf<AddonUrl?>(null) }
    LaunchedEffect(refocus, addons) {
        val url = refocus ?: return@LaunchedEffect
        if (addons.none { it.url == url }) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        runCatching { rowFocus[url]?.requestFocus() }
        refocus = null
    }
    // The last addon removed: focus goes to "Type an address" once the list is empty.
    var refocusEntry by remember { mutableStateOf(false) }
    LaunchedEffect(refocusEntry, addons) {
        if (!refocusEntry || addons.isNotEmpty()) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        runCatching { entry.requestFocus() }
        refocusEntry = false
    }

    Column(Modifier.fillMaxSize().focusGroup().widthIn(max = 1100.mpx).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.mpx)) {
            Text(stringResource(R.string.gambit_addons_title), style = stageText(46, 800), color = GambitColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pluralStringResource(R.plurals.gambit_addons_count, addons.size, addons.size), style = stageText(20, 500), color = GambitColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.mpx))
        }
        Text(
            stringResource(R.string.gambit_addons_description), style = stageText(18, 500), color = GambitColors.Muted,
            modifier = Modifier.padding(top = 10.mpx),
        )
        Row(Modifier.padding(top = 24.mpx)) {
            StageTool(
                stringResource(R.string.gambit_addons_add_typed), onClick = { typing = true },
                icon = OwnTVIcon.ADD, boxed = true, modifier = Modifier.focusRequester(entry),
            )
            StageTool(
                stringResource(R.string.gambit_stremio_title), onClick = { stremioOpen = true },
                icon = OwnTVIcon.PHONE, boxed = true, modifier = Modifier.padding(start = 14.mpx).focusRequester(stremioFocus),
            )
            if (kidsEntry) {
                StageTool(
                    stringResource(R.string.gambit_kids_access_title),
                    onClick = {
                        pinGate.guardStrict(AdultPinReason.KIDS_ACCESS, onNoPin = { noPin = true }) { kidsOpen = true }
                    },
                    boxed = true, modifier = Modifier.padding(start = 14.mpx).focusRequester(kidsFocus),
                )
            }
        }
        Column(Modifier.padding(top = 26.mpx), verticalArrangement = Arrangement.spacedBy(6.mpx)) {
            if (unreadable) {
                Text(stringResource(R.string.gambit_addons_unreadable_title), style = stageText(22, 700), color = GambitColors.Text)
                Text(stringResource(R.string.gambit_addons_unreadable_body), style = stageText(18, 500), color = GambitColors.Muted)
            } else if (addons.isEmpty()) {
                Text(stringResource(R.string.gambit_addons_empty), style = stageText(20, 500), color = GambitColors.Dim)
            }
            addons.forEach { a ->
                key(a.url) {
                    val focus = remember { FocusRequester() }
                    DisposableEffect(a.url) {
                        rowFocus[a.url] = focus
                        onDispose { rowFocus.remove(a.url) }
                    }
                    AddonRow(a, onClick = { actionsFor = a }, modifier = Modifier.focusRequester(focus))
                }
            }
        }
    }

    pinGate.Dialog()
    if (noPin) {
        StageNotice(
            title = stringResource(R.string.gambit_kids_access_set_pin_title),
            body = stringResource(R.string.gambit_kids_access_set_pin_body),
            onDismiss = { noPin = false },
        )
    }

    if (typing) {
        TextInputDialog(
            title = stringResource(R.string.gambit_addons_add_typed),
            label = stringResource(R.string.gambit_addons_address),
            hint = stringResource(R.string.gambit_addons_address_hint),
            confirmLabel = stringResource(R.string.gambit_addons_add),
            allowBlank = false,
            onConfirm = { typing = false; vm.addTyped(it) },
            onDismiss = { typing = false },
        )
    }
    typed?.let { r ->
        StageNotice(
            title = when (r) {
                is AddonAddResult.Added -> stringResource(R.string.gambit_addons_added, r.name)
                is AddonAddResult.Failed -> stringResource(R.string.gambit_addons_not_added)
            },
            body = (r as? AddonAddResult.Failed)?.let { stringResource(it.error.message()) },
            onDismiss = vm::clearTyped,
        )
    }

    actionsFor?.let { a ->
        StagePopup(onDismiss = { actionsFor = null }, title = a.manifest.name, body = a.url.host, width = 640.mpx) {
            StagePopupOption(
                title = stringResource(if (a.enabled) R.string.gambit_addons_disable else R.string.gambit_addons_enable),
                onClick = { vm.setEnabled(a.url, !a.enabled); actionsFor = null },
            )
            StagePopupOption(title = stringResource(R.string.gambit_addons_move_up), onClick = { vm.move(a.url, -1); refocus = a.url; actionsFor = null })
            StagePopupOption(title = stringResource(R.string.gambit_addons_move_down), onClick = { vm.move(a.url, 1); refocus = a.url; actionsFor = null })
            StagePopupOption(title = stringResource(R.string.gambit_addons_remove), danger = true, onClick = { removing = a; actionsFor = null })
        }
    }
    removing?.let { a ->
        StageConfirm(
            title = stringResource(R.string.gambit_addons_remove_title, a.manifest.name),
            body = stringResource(R.string.gambit_addons_remove_body),
            confirm = stringResource(R.string.gambit_addons_remove),
            onConfirm = {
                val i = addons.indexOfFirst { it.url == a.url }
                val next = addons.getOrNull(i + 1) ?: addons.getOrNull(i - 1)
                vm.remove(a.url)
                if (next != null) refocus = next.url else refocusEntry = true
                removing = null
            },
            onCancel = { removing = null },
            focusCancel = true,
        )
    }
}

/** Name, then "host · v1.4.2 · movie, series · 3 catalogs"; dimmed with "Off" when disabled. Host only, never the path or query. */
@Composable
private fun AddonRow(a: InstalledAddon, onClick: () -> Unit, modifier: Modifier = Modifier) {
    StageSurface(onClick = onClick, radius = 10.mpx, modifier = modifier.fillMaxWidth().heightIn(min = 84.mpx)) { focused ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.mpx, vertical = 12.mpx), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.manifest.name, style = stageText(24, 600), color = if (a.enabled || focused) Color.White else GambitColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val catalogs = a.manifest.catalogs.size
                val bits = listOfNotNull(
                    a.url.host,
                    a.manifest.version.takeIf { it.isNotBlank() }?.let { stringResource(R.string.gambit_addons_version, it) },
                    a.manifest.types.takeIf { it.isNotEmpty() }?.joinToString(stringResource(R.string.gambit_addons_list_separator)),
                    catalogs.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.gambit_addons_catalogs, it, it) },
                )
                Text(
                    bits.joinToString(stringResource(R.string.gambit_addons_separator)), style = stageText(17, 400),
                    color = if (focused) Color.White.copy(alpha = 0.8f) else GambitColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.mpx),
                )
            }
            if (!a.enabled) Text(stringResource(R.string.common_off), style = stageText(18, 600), color = GambitColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.mpx))
        }
    }
}

private fun AddonAddError.message(): Int = when (this) {
    AddonAddError.NOT_AN_ADDRESS -> R.string.gambit_addons_err_address
    AddonAddError.UNREACHABLE -> R.string.gambit_addons_err_unreachable
    AddonAddError.NOT_AN_ADDON -> R.string.gambit_addons_err_manifest
}
