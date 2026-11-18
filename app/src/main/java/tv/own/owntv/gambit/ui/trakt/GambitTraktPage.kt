package tv.own.owntv.gambit.ui.trakt

import android.text.format.DateUtils
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.BuildConfig
import tv.own.owntv.R
import tv.own.owntv.gambit.trakt.TraktRenewalCheck
import tv.own.owntv.gambit.ui.theme.GambitColors
import tv.own.owntv.gambit.ui.theme.gambitAccentLight
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageTool
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * More → Trakt: sign in from a phone. The TV shows a QR code for Trakt's activation page, the address
 * as text and the 8-character code to type there, with a countdown. One button sits under the panel
 * in every state (Sign in, Cancel, Try again, Sign out). It is the same element throughout, so D-pad
 * focus stays on it as the state changes. Back cancels a sign-in in progress; leaving the page does too.
 *
 * Not shown at all when the build has no Trakt Client ID (the More item is hidden then).
 */
@Composable
internal fun GambitTraktPage(entry: FocusRequester, vm: TraktSignInViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val renewal by vm.renewal.collectAsStateWithLifecycle()
    if (state == TraktSignInState.NotConfigured) return
    val busy = state is TraktSignInState.Starting || state is TraktSignInState.Waiting
    // Registered after More's own Back, so it wins: Back stops the sign-in first, then leaves the page.
    BackHandler(enabled = busy) { vm.cancel() }
    DisposableEffect(vm) { onDispose { vm.cancel() } }
    // A renewal refused in the background clears the sign-in: read it again each time the page opens.
    LaunchedEffect(vm) { vm.recheck() }
    // TEMPORARY (Stage 4): the renewal check button. If a check signs out, that button goes away; put
    // focus back on the main button.
    LaunchedEffect(renewal, state) {
        if (renewal is TraktRenewalState.Done && state != TraktSignInState.SignedIn) {
            withFrameNanos { }
            withFrameNanos { }
            runCatching { entry.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize().focusGroup().widthIn(max = 1100.mpx).verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.gambit_trakt_title), style = stageText(46, 800), color = GambitColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            stringResource(R.string.gambit_trakt_description), style = stageText(18, 500), color = GambitColors.Muted,
            modifier = Modifier.padding(top = 10.mpx),
        )
        Box(
            Modifier
                .padding(top = 26.mpx)
                .fillMaxWidth()
                .background(GambitColors.Rail, RoundedCornerShape(16.mpx))
                .padding(28.mpx),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(20.mpx)) {
                Panel(state)
                RenewalLines(renewal) // TEMPORARY (Stage 4)
            }
        }
        val label: Int
        val icon: OwnTVIcon
        val action: () -> Unit
        when (state) {
            TraktSignInState.NotConfigured, TraktSignInState.Checking -> { label = R.string.gambit_trakt_sign_in; icon = OwnTVIcon.PERSON; action = { } }
            TraktSignInState.SignedOut -> { label = R.string.gambit_trakt_sign_in; icon = OwnTVIcon.PERSON; action = { vm.start() } }
            TraktSignInState.Starting, is TraktSignInState.Waiting -> { label = R.string.gambit_trakt_cancel; icon = OwnTVIcon.CLOSE; action = { vm.cancel() } }
            TraktSignInState.SignedIn -> { label = R.string.gambit_trakt_sign_out; icon = OwnTVIcon.CLOSE; action = { vm.signOut() } }
            is TraktSignInState.Failed -> { label = R.string.gambit_trakt_retry; icon = OwnTVIcon.REFRESH; action = { vm.start() } }
        }
        Row(Modifier.padding(top = 24.mpx), horizontalArrangement = Arrangement.spacedBy(16.mpx)) {
            StageTool(
                stringResource(label), onClick = action,
                icon = icon, boxed = true, modifier = Modifier.focusRequester(entry),
            )
            // TEMPORARY (Stage 4): debug builds only; removed in its own commit after one Shield check.
            if (BuildConfig.DEBUG && vm.canCheckRenewal && state == TraktSignInState.SignedIn) {
                val running = renewal == TraktRenewalState.Running
                StageTool(
                    stringResource(if (running) R.string.gambit_trakt_checking_renewal else R.string.gambit_trakt_check_renewal),
                    onClick = { vm.checkRenewal() }, icon = OwnTVIcon.REFRESH, boxed = true,
                )
            }
        }
    }
}

/** What the dark panel says in each state. */
@Composable
private fun Panel(state: TraktSignInState) {
    when (state) {
        TraktSignInState.NotConfigured -> Unit
        TraktSignInState.Checking -> Line(R.string.gambit_trakt_checking)
        TraktSignInState.SignedOut -> Line(R.string.gambit_trakt_signed_out)
        TraktSignInState.Starting -> Line(R.string.gambit_trakt_getting_code)
        is TraktSignInState.Waiting -> WaitingPanel(state)
        TraktSignInState.SignedIn -> Column(verticalArrangement = Arrangement.spacedBy(8.mpx)) {
            Text(stringResource(R.string.gambit_trakt_signed_in_title), style = stageText(30, 700), color = gambitAccentLight(stageAccent.accent))
            Text(stringResource(R.string.gambit_trakt_signed_in_body), style = stageText(20, 500), color = GambitColors.Muted)
        }
        is TraktSignInState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.mpx)) {
            Text(stringResource(R.string.gambit_trakt_failed_title), style = stageText(30, 700), color = GambitColors.Text)
            Text(stringResource(state.error.messageRes()), style = stageText(20, 500), color = GambitColors.Muted)
        }
    }
}

/** TEMPORARY (Stage 4): the renewal check's three results. Never a token: only yes/no, a time and the error's text. */
@Composable
private fun RenewalLines(renewal: TraktRenewalState) {
    when (renewal) {
        TraktRenewalState.Idle -> Unit
        TraktRenewalState.Running -> Line(R.string.gambit_trakt_checking_renewal)
        is TraktRenewalState.Done -> Column(verticalArrangement = Arrangement.spacedBy(6.mpx)) {
            Text(stringResource(R.string.gambit_trakt_renewal_title), style = stageText(24, 700), color = GambitColors.Text)
            RenewalLine(R.string.gambit_trakt_renewal_forced, renewal.result.forced)
            RenewalLine(R.string.gambit_trakt_renewal_automatic, renewal.result.automatic)
            RenewalLine(R.string.gambit_trakt_renewal_rejected, renewal.result.afterRejected)
        }
    }
}

@Composable
private fun RenewalLine(name: Int, step: TraktRenewalCheck.Step?) {
    val context = LocalContext.current
    val label = stringResource(name)
    val ok = step != null && step.renewed && step.error == null
    val text = when {
        step == null -> stringResource(R.string.gambit_trakt_renewal_not_run, label)
        ok -> stringResource(
            R.string.gambit_trakt_renewal_ok, label,
            DateUtils.formatDateTime(
                context, (step!!.expiresAtEpochSeconds ?: 0L) * 1000,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
            ),
        )
        step!!.renewed -> stringResource(R.string.gambit_trakt_renewal_call_failed, label, stringResource(step.error!!.messageRes()))
        step.error != null -> stringResource(R.string.gambit_trakt_renewal_failed, label, stringResource(step.error.messageRes()))
        else -> stringResource(R.string.gambit_trakt_renewal_not_renewed, label)
    }
    Text(text, style = stageText(20, 500), color = if (ok) gambitAccentLight(stageAccent.accent) else GambitColors.Text)
}

@Composable
private fun Line(res: Int) {
    Text(stringResource(res), style = stageText(24, 500), color = GambitColors.Muted)
}

/** QR code on the left; address, the code in large type and the countdown on the right. */
@Composable
private fun WaitingPanel(waiting: TraktSignInState.Waiting) {
    val qr = remember(waiting.verificationUrl) { GambitQr.bitmap(waiting.verificationUrl)?.asImageBitmap() }
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
                    bitmap = qr, contentDescription = stringResource(R.string.gambit_trakt_qr_description),
                    modifier = Modifier.fillMaxSize(), filterQuality = FilterQuality.None,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.mpx)) {
            Text(stringResource(R.string.gambit_trakt_scan), style = stageText(20, 500), color = GambitColors.Muted)
            Text(
                waiting.verificationUrl.removePrefix("https://"), style = stageText(34, 700), color = GambitColors.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(stringResource(R.string.gambit_trakt_enter_code), style = stageText(20, 500), color = GambitColors.Muted, modifier = Modifier.padding(top = 10.mpx))
            Text(
                waiting.userCode,
                style = stageText(76, 800).copy(fontFamily = FontFamily.Monospace, letterSpacing = 8.sp),
                color = GambitColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.gambit_trakt_expires_in, (left / 60).toInt(), (left % 60).toInt()),
                style = stageText(18, 500), color = GambitColors.Dim, modifier = Modifier.padding(top = 6.mpx),
            )
        }
    }
}
