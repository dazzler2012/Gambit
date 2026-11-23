package tv.own.owntv.gambit.profiles

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.categorylock.PinAttempt
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.profile.ProfileProtection
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/** What the adult PIN is being asked for; picks the dialog's body text. */
enum class AdultPinReason { ADD_PROFILE, CHANGE_PROFILE, KIDS_ACCESS }

/** One adult profile's answer to a PIN, without Core's type so the rule can be tested here. */
internal sealed interface AdultPinOutcome {
    data object Accepted : AdultPinOutcome
    data object Wrong : AdultPinOutcome
    data class Wait(val seconds: Int) : AdultPinOutcome
}

/**
 * The rule that keeps a kids profile in kids mode. Adding a profile, or changing one from a kids profile,
 * needs the PIN of an adult profile (one that is not Kids and has a profile PIN or a Settings PIN).
 * With no such adult profile there is nothing to ask for: a kid could already pick the unlocked adult
 * profile, so no prompt is shown.
 */
internal object AdultPinRule {

    /** Whether to ask. [always] is the profile picker's Add tile, where nobody knows who is holding the remote. */
    fun needed(always: Boolean, activeIsKids: Boolean, adultPinHolders: Int): Boolean =
        adultPinHolders > 0 && (always || activeIsKids)

    /** Whether the profile [activeProfileId] in [profiles] is a kids profile. */
    fun activeIsKids(profiles: List<ProfileEntity>, activeProfileId: Long): Boolean =
        profiles.firstOrNull { it.id == activeProfileId }?.isKids == true

    /**
     * Try [pin] on each adult in turn and stop at the first that accepts it. Otherwise it is Wrong if any
     * adult said Wrong, or the shortest wait if every adult is waiting out earlier wrong entries.
     */
    suspend fun verifyAny(
        holders: List<ProfileEntity>,
        pin: String,
        verify: suspend (ProfileEntity, String) -> AdultPinOutcome,
    ): AdultPinOutcome {
        val outcomes = ArrayList<AdultPinOutcome>(holders.size)
        for (holder in holders) {
            val outcome = verify(holder, pin)
            if (outcome == AdultPinOutcome.Accepted) return outcome
            outcomes += outcome
        }
        if (outcomes.isEmpty() || outcomes.any { it == AdultPinOutcome.Wrong }) return AdultPinOutcome.Wrong
        return outcomes.filterIsInstance<AdultPinOutcome.Wait>().minBy { it.seconds }
    }
}

/**
 * One PIN prompt. [check] tries a PIN on the adults in [holders]; only an accepted PIN unlocks, and then
 * [run] does [action] once (a second call, or a call before unlocking, does nothing).
 */
internal class PinSession(
    private val holders: List<ProfileEntity>,
    private val verify: suspend (ProfileEntity, String) -> AdultPinOutcome,
    private val action: () -> Unit,
) {
    private var unlocked = false
    private var ran = false

    suspend fun check(pin: String): AdultPinOutcome {
        val outcome = AdultPinRule.verifyAny(holders, pin, verify)
        if (outcome == AdultPinOutcome.Accepted) unlocked = true
        return outcome
    }

    fun run() {
        if (!unlocked || ran) return
        ran = true
        action()
    }
}

/**
 * The strict rule: with no adult PIN holder there is nothing to check a PIN against, so [onNoPin] is called
 * and there is no session (the action can never run). Otherwise a [PinSession] to prompt with.
 */
internal fun strictPinSession(
    holders: List<ProfileEntity>,
    verify: suspend (ProfileEntity, String) -> AdultPinOutcome,
    action: () -> Unit,
    onNoPin: () -> Unit,
): PinSession? {
    if (holders.isEmpty()) {
        onNoPin()
        return null
    }
    return PinSession(holders, verify, action)
}

private fun PinAttempt.toOutcome(): AdultPinOutcome = when (this) {
    PinAttempt.Accepted -> AdultPinOutcome.Accepted
    PinAttempt.Wrong -> AdultPinOutcome.Wrong
    is PinAttempt.Wait -> AdultPinOutcome.Wait(seconds)
}

/**
 * Asks an adult profile's PIN before an action, when [AdultPinRule] says so. Call [guardAlways] or
 * [guardKids] with the action, and place [Dialog] once in the same screen.
 */
@Stable
class AdultPinGate internal constructor(
    private val protection: ProfileProtection,
    private val scope: CoroutineScope,
    private val profiles: () -> List<ProfileEntity>,
) {
    private class Pending(val session: PinSession, val reason: AdultPinReason)

    private var pending by mutableStateOf<Pending?>(null)

    /** Ask whenever an adult PIN exists (the profile picker's Add tile). */
    fun guardAlways(reason: AdultPinReason, action: () -> Unit) = guard(always = true, activeIsKids = false, reason, action)

    /** Ask only when the profile in use, [activeProfileId], is a kids profile. */
    fun guardKids(activeProfileId: Long, reason: AdultPinReason, action: () -> Unit) =
        guard(always = false, activeIsKids = AdultPinRule.activeIsKids(profiles(), activeProfileId), reason, action)

    /**
     * Like [guardAlways], but for what must never run without a PIN (Kids access): when no adult profile has
     * a PIN, [onNoPin] is called and [action] never runs. Otherwise [action] runs once after a right PIN.
     */
    fun guardStrict(reason: AdultPinReason, onNoPin: () -> Unit, action: () -> Unit) {
        val adults = profiles().filter { !it.isKids }
        scope.launch {
            val holders = adults.filter { protection.isProtected(it) }
            strictPinSession(holders, ::verifyOne, action, onNoPin)?.let { pending = Pending(it, reason) }
        }
    }

    private suspend fun verifyOne(profile: ProfileEntity, pin: String): AdultPinOutcome = protection.verify(profile, pin).toOutcome()

    private fun guard(always: Boolean, activeIsKids: Boolean, reason: AdultPinReason, action: () -> Unit) {
        if (!always && !activeIsKids) { action(); return }
        val adults = profiles().filter { !it.isKids }
        scope.launch {
            val holders = adults.filter { protection.isProtected(it) }
            if (AdultPinRule.needed(always, activeIsKids, holders.size)) pending = Pending(PinSession(holders, ::verifyOne, action), reason) else action()
        }
    }

    @Composable
    fun Dialog() {
        val ask = pending ?: return
        AdultPinDialog(
            body = stringResource(
                when (ask.reason) {
                    AdultPinReason.ADD_PROFILE -> R.string.gambit_adult_pin_add
                    AdultPinReason.CHANGE_PROFILE -> R.string.gambit_adult_pin_change
                    AdultPinReason.KIDS_ACCESS -> R.string.gambit_adult_pin_kids_access
                },
            ),
            verify = { pin -> ask.session.check(pin) },
            onUnlocked = { pending = null; ask.session.run() },
            onDismiss = { pending = null },
        )
    }
}

/** An [AdultPinGate] for a screen showing [profiles]. */
@Composable
fun rememberAdultPinGate(profiles: List<ProfileEntity>): AdultPinGate {
    val protection = koinInject<ProfileProtection>()
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(profiles)
    return remember(protection, scope) { AdultPinGate(protection, scope) { current } }
}

/** The adult PIN prompt, laid out like the protected-profile prompt. */
@Composable
private fun AdultPinDialog(
    body: String,
    verify: suspend (String) -> AdultPinOutcome,
    onUnlocked: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var waitSeconds by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { fieldFocus.requestFocus() } }
    LaunchedEffect(waitSeconds > 0) {
        while (waitSeconds > 0) {
            delay(1_000)
            waitSeconds--
        }
    }
    val submit = {
        val entered = pin
        if (entered.isNotBlank() && waitSeconds == 0) scope.launch {
            when (val result = verify(entered)) {
                AdultPinOutcome.Accepted -> onUnlocked()
                AdultPinOutcome.Wrong -> { wrong = true; pin = "" }
                is AdultPinOutcome.Wait -> { wrong = false; waitSeconds = result.seconds; pin = "" }
            }
        }
    }
    tv.own.owntv.ui.stage.StagePopup(
        onDismiss = onDismiss,
        title = stringResource(R.string.gambit_adult_pin_title),
        body = body,
        width = 760.mpx,
        buttons = {
            tv.own.owntv.ui.stage.StageButton(stringResource(R.string.common_cancel), onClick = onDismiss, height = 56.mpx, textSize = 19)
            tv.own.owntv.ui.stage.StageButton(
                stringResource(R.string.settings_backup_unlock),
                onClick = { submit() },
                height = 56.mpx, textSize = 19, tinted = true,
            )
        },
    ) {
        tv.own.owntv.features.settings.StageFieldRow(
            icon = tv.own.owntv.ui.components.OwnTVIcon.PENCIL,
            label = stringResource(R.string.profiles_pin),
            value = pin,
            onValueChange = { pin = it; wrong = false },
            modifier = Modifier.focusRequester(fieldFocus),
            password = true,
        )
        val note = when {
            waitSeconds > 0 -> pluralStringResource(R.plurals.content_category_pin_wait, waitSeconds, waitSeconds)
            wrong -> stringResource(R.string.settings_backup_pin_incorrect)
            else -> null
        }
        if (note != null) Text(note, style = stageText(17, 600), color = StageColors.Danger, modifier = Modifier.padding(top = 10.mpx))
    }
}
