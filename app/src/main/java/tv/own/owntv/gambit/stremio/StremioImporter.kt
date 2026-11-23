package tv.own.owntv.gambit.stremio

import kotlinx.coroutines.delay
import tv.own.owntv.gambit.addons.data.AddonImportReport
import tv.own.owntv.gambit.addons.data.AddonStore
import tv.own.owntv.gambit.addons.data.KidsAddonAccess

/** How waiting for the phone ended. None of these holds the session key. */
sealed interface StremioLinkOutcome {
    /** Approved, and the account's addons were read. */
    data class Ready(val preview: ImportPreview) : StremioLinkOutcome

    /** Not approved in time (or Stremio expired the code). The screen offers a new code. */
    data object Expired : StremioLinkOutcome

    /** Stremio refused or answered something unreadable. */
    data class Failed(val error: StremioError) : StremioLinkOutcome
}

/**
 * Runs the account import: a link code for the TV to show, waiting for the phone, one read of the
 * account's addon list, then adding the addons the user ticked.
 *
 * The session key exists only as a local value inside [awaitAccountAddons], for the one call that reads
 * the addon list; it is never a property, never returned and never stored. There is no logout call.
 *
 * @param pollMs how often the code is checked while waiting.
 * @param timeoutMs how long to wait for the phone before giving up.
 * @param nowMs / [pause] are replaced in tests so no real time passes.
 */
class StremioImporter(
    private val client: StremioClient,
    private val store: AddonStore,
    private val kids: KidsAddonAccess,
    private val pollMs: Long = POLL_MS,
    /** How long [awaitAccountAddons] waits for the phone; the screen's countdown uses the same value. */
    val timeoutMs: Long = TIMEOUT_MS,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun newCode(): StremioResult<StremioLinkCode> = client.createLinkCode()

    /**
     * Checks [code] every [pollMs] until the phone approves it, then reads the account's addons and
     * returns the preview. A dropped connection while waiting is retried on the next check; Stremio
     * refusing is final. Cancelling the caller (leaving the screen) stops it at once.
     */
    suspend fun awaitAccountAddons(code: StremioLinkCode): StremioLinkOutcome {
        val deadline = nowMs() + timeoutMs
        while (true) {
            when (val read = client.readLinkCode(code)) {
                is StremioResult.Ok -> when (val status = read.value) {
                    StremioLinkStatus.Waiting -> Unit
                    is StremioLinkStatus.Linked -> return readAddons(status)
                }
                is StremioResult.Err -> if (!isTransient(read.error)) return StremioLinkOutcome.Failed(read.error)
            }
            if (nowMs() >= deadline) return StremioLinkOutcome.Expired
            pause(pollMs)
        }
    }

    /** Adds the ticked rows. Rows that can't be ticked are ignored. */
    suspend fun import(chosen: List<ImportCandidate>): AddonImportReport =
        store.importAll(
            urls = chosen.filter { it.selectable }.map { it.url },
            kidsApproved = kids.approvals.value.values.flatten().toSet(),
        )

    private suspend fun readAddons(linked: StremioLinkStatus.Linked): StremioLinkOutcome =
        when (val collection = client.addonCollection(linked.key)) {
            is StremioResult.Ok -> StremioLinkOutcome.Ready(StremioImportRules.preview(collection.value, store.registry.value.all))
            is StremioResult.Err -> StremioLinkOutcome.Failed(collection.error)
        }

    private fun isTransient(error: StremioError): Boolean =
        error is StremioError.Timeout || error is StremioError.Network ||
            (error is StremioError.HttpStatus && error.code >= 500)

    companion object {
        const val POLL_MS = 5_000L
        const val TIMEOUT_MS = 5 * 60_000L
    }
}
