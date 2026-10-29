package tv.own.owntv.gambit.ui.addons

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.companion.CompanionLink
import tv.own.owntv.gambit.addons.AddonAddError
import tv.own.owntv.gambit.addons.AddonAddResult
import tv.own.owntv.gambit.addons.GambitAddonServer
import tv.own.owntv.gambit.addons.InstalledAddon
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
 * asked. Each shows its name and its host only — the address holds the user's keys and is never shown
 * again once saved. Add from a phone (QR + PIN) or, as a fallback, type the address with the remote.
 * Nothing on a kids profile.
 */
@Composable
internal fun GambitAddonsPage(entry: FocusRequester, vm: AddonsViewModel = koinViewModel()) {
    val kids by vm.kids.collectAsStateWithLifecycle()
    if (kids) return
    val addons by vm.addons.collectAsStateWithLifecycle()
    val phone by vm.phone.collectAsStateWithLifecycle()
    val typed by vm.typed.collectAsStateWithLifecycle()
    var phoneOpen by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<InstalledAddon?>(null) }
    var removing by remember { mutableStateOf<InstalledAddon?>(null) }

    Column(Modifier.fillMaxSize().focusGroup().widthIn(max = 1100.mpx).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.mpx)) {
            Text(stringResource(R.string.gambit_addons_title), style = stageText(46, 800), color = GambitColors.Text, maxLines = 1)
            Text(pluralStringResource(R.plurals.gambit_addons_count, addons.size, addons.size), style = stageText(20, 500), color = GambitColors.Dim, maxLines = 1, modifier = Modifier.padding(bottom = 8.mpx))
        }
        Text(
            stringResource(R.string.gambit_addons_description), style = stageText(18, 500), color = GambitColors.Muted,
            modifier = Modifier.padding(top = 10.mpx),
        )
        Row(Modifier.padding(top = 24.mpx), horizontalArrangement = Arrangement.spacedBy(14.mpx)) {
            StageTool(
                stringResource(R.string.gambit_addons_add_phone), onClick = { vm.openPhone(); phoneOpen = true },
                icon = OwnTVIcon.PHONE, boxed = true, modifier = Modifier.focusRequester(entry),
            )
            StageTool(stringResource(R.string.gambit_addons_add_typed), onClick = { typing = true }, icon = OwnTVIcon.ADD, boxed = true)
        }
        Column(Modifier.padding(top = 26.mpx), verticalArrangement = Arrangement.spacedBy(6.mpx)) {
            if (addons.isEmpty()) {
                Text(stringResource(R.string.gambit_addons_empty), style = stageText(20, 500), color = GambitColors.Dim)
            }
            addons.forEach { a -> AddonRow(a, onClick = { actionsFor = a }) }
        }
    }

    if (phoneOpen) PhonePopup(phone, onClose = { vm.closePhone(); phoneOpen = false })

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
        StagePopup(onDismiss = { actionsFor = null }, title = a.name, body = a.host, width = 640.mpx) {
            StagePopupOption(
                title = stringResource(if (a.enabled) R.string.gambit_addons_disable else R.string.gambit_addons_enable),
                onClick = { vm.setEnabled(a.id, !a.enabled); actionsFor = null },
            )
            StagePopupOption(title = stringResource(R.string.gambit_addons_move_up), onClick = { vm.move(a.id, -1); actionsFor = null })
            StagePopupOption(title = stringResource(R.string.gambit_addons_move_down), onClick = { vm.move(a.id, 1); actionsFor = null })
            StagePopupOption(title = stringResource(R.string.gambit_addons_remove), danger = true, onClick = { removing = a; actionsFor = null })
        }
    }
    removing?.let { a ->
        StageConfirm(
            title = stringResource(R.string.gambit_addons_remove_title, a.name),
            body = stringResource(R.string.gambit_addons_remove_body),
            confirm = stringResource(R.string.gambit_addons_remove),
            onConfirm = { vm.remove(a.id); removing = null },
            onCancel = { removing = null },
            focusCancel = true,
        )
    }
}

/** Name, then "host · v1.4.2 · movie, series · 3 catalogs"; dimmed with "Off" when disabled. */
@Composable
private fun AddonRow(a: InstalledAddon, onClick: () -> Unit) {
    StageSurface(onClick = onClick, radius = 10.mpx, modifier = Modifier.fillMaxWidth().heightIn(min = 84.mpx)) { focused ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.mpx, vertical = 12.mpx), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.name, style = stageText(24, 600), color = if (a.enabled || focused) Color.White else GambitColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val bits = listOfNotNull(
                    a.host,
                    a.version.takeIf { it.isNotBlank() }?.let { stringResource(R.string.gambit_addons_version, it) },
                    a.types.takeIf { it.isNotEmpty() }?.joinToString(", "),
                    a.catalogCount.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.gambit_addons_catalogs, it, it) },
                )
                Text(
                    bits.joinToString(stringResource(R.string.gambit_addons_separator)), style = stageText(17, 400),
                    color = if (focused) Color.White.copy(alpha = 0.8f) else GambitColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.mpx),
                )
            }
            if (!a.enabled) Text(stringResource(R.string.common_off), style = stageText(18, 600), color = GambitColors.Dim, modifier = Modifier.padding(start = 16.mpx))
        }
    }
}

/** The QR code and PIN while the phone page is open; then what happened. */
@Composable
private fun PhonePopup(state: GambitAddonServer.State, onClose: () -> Unit) {
    StagePopup(
        onDismiss = onClose,
        title = stringResource(R.string.gambit_addons_add_phone),
        body = stringResource(R.string.gambit_addons_phone_steps),
        width = 900.mpx,
        buttons = { tv.own.owntv.ui.stage.StageButton(stringResource(R.string.common_done), onClick = onClose, height = 56.mpx, textSize = 19) },
    ) {
        when (state) {
            is GambitAddonServer.State.Open -> Row(Modifier.padding(top = 10.mpx), horizontalArrangement = Arrangement.spacedBy(30.mpx), verticalAlignment = Alignment.CenterVertically) {
                val qr = remember(state.address) { CompanionLink.renderQr(state.address, 512) }
                Box(Modifier.size(260.mpx).background(Color.White, RoundedCornerShape(16.mpx)).padding(12.mpx)) {
                    if (qr != null) Image(qr.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                }
                Column {
                    Text(state.address.removePrefix("http://"), style = stageText(26, 700), color = GambitColors.Text, maxLines = 1)
                    Text(stringResource(R.string.gambit_addons_pin), style = stageText(18, 500), color = GambitColors.Muted, modifier = Modifier.padding(top = 22.mpx))
                    Text(state.pin.chunked(3).joinToString(" "), style = stageText(54, 800), color = GambitColors.Text, maxLines = 1)
                }
            }
            is GambitAddonServer.State.Added -> Text(stringResource(R.string.gambit_addons_added, state.name), style = stageText(24, 700), color = GambitColors.Text)
            GambitAddonServer.State.Locked -> Text(stringResource(R.string.gambit_addons_locked), style = stageText(20, 500), color = GambitColors.Text)
            GambitAddonServer.State.Failed -> Text(stringResource(R.string.gambit_addons_server_failed), style = stageText(20, 500), color = GambitColors.Text)
            GambitAddonServer.State.Idle -> Text(stringResource(R.string.gambit_addons_closed), style = stageText(20, 500), color = GambitColors.Text)
        }
    }
}

private fun AddonAddError.message(): Int = when (this) {
    AddonAddError.NOT_AN_ADDRESS -> R.string.gambit_addons_err_address
    AddonAddError.UNREACHABLE -> R.string.gambit_addons_err_unreachable
    AddonAddError.NOT_AN_ADDON -> R.string.gambit_addons_err_manifest
}
