package tv.own.owntv.gambit.ui.stremio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.own.owntv.gambit.addons.data.AddonImportReport
import tv.own.owntv.gambit.stremio.ImportPreview
import tv.own.owntv.gambit.stremio.StremioError
import tv.own.owntv.gambit.stremio.StremioImporter
import tv.own.owntv.gambit.stremio.StremioLinkOutcome
import tv.own.owntv.gambit.stremio.StremioResult

/**
 * What the Stremio import screen shows. No state holds the Stremio session key: it never leaves
 * [StremioImporter]. Failures carry the typed [StremioError]; the screen maps each type to its own text.
 */
sealed interface StremioImportState {
    /** The intro and the Start button. */
    data object Idle : StremioImportState

    /** Asking Stremio for a code. */
    data object Starting : StremioImportState

    /** Show [link] as a QR code and as text, with [code]; the TV stops waiting at [expiresAtMillis]. */
    data class Waiting(val code: String, val link: String, val expiresAtMillis: Long) : StremioImportState

    /**
     * The account's addons, in Stremio's order. [ticked] holds indexes into [preview]'s candidates;
     * only selectable rows can be in it.
     */
    data class Choosing(val preview: ImportPreview, val ticked: Set<Int>) : StremioImportState

    data object Importing : StremioImportState

    /** What happened, plus what the preview left out ([unsupported], [unreadable]). */
    data class Done(val report: AddonImportReport, val unsupported: Int, val unreadable: Int) : StremioImportState

    /** The phone didn't approve in time. */
    data object Expired : StremioImportState

    data class Failed(val error: StremioError) : StremioImportState
}

/**
 * The import screen's state machine, free of Android so it runs in plain JVM tests. [scope] is the
 * screen's scope (viewModelScope): when it ends, waiting stops. [cancel] is for Back.
 */
class StremioImportModel(
    private val importer: StremioImporter,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow<StremioImportState>(StremioImportState.Idle)
    val state: StateFlow<StremioImportState> = mutableState.asStateFlow()

    private var job: Job? = null

    /** Gets a code and waits for the phone. Starting again replaces a wait in progress. */
    fun start() {
        job?.cancel()
        mutableState.value = StremioImportState.Starting
        job = scope.launch {
            when (val got = importer.newCode()) {
                is StremioResult.Err -> publish(StremioImportState.Failed(got.error))
                is StremioResult.Ok -> {
                    val code = got.value
                    publish(StremioImportState.Waiting(code.code, code.link, nowMillis() + importer.timeoutMs))
                    publish(
                        when (val outcome = importer.awaitAccountAddons(code)) {
                            is StremioLinkOutcome.Ready -> StremioImportState.Choosing(
                                outcome.preview,
                                outcome.preview.candidates.indices.filter { outcome.preview.candidates[it].tickedByDefault }.toSet(),
                            )
                            StremioLinkOutcome.Expired -> StremioImportState.Expired
                            is StremioLinkOutcome.Failed -> StremioImportState.Failed(outcome.error)
                        },
                    )
                }
            }
        }
    }

    /** Ticks or unticks one preview row. Rows that can't be ticked stay as they are. */
    fun toggle(index: Int) {
        val now = mutableState.value as? StremioImportState.Choosing ?: return
        val row = now.preview.candidates.getOrNull(index) ?: return
        if (!row.selectable) return
        mutableState.value = now.copy(ticked = if (index in now.ticked) now.ticked - index else now.ticked + index)
    }

    /** Adds the ticked rows. Does nothing unless the preview is showing. */
    fun import() {
        val now = mutableState.value as? StremioImportState.Choosing ?: return
        val chosen = now.ticked.sorted().map { now.preview.candidates[it] }
        mutableState.value = StremioImportState.Importing
        job = scope.launch {
            val report = importer.import(chosen)
            publish(StremioImportState.Done(report, now.preview.unsupported, now.preview.unreadable))
        }
    }

    /** Back or Cancel: stops waiting and goes back to the start. An import already running finishes. */
    fun cancel() {
        val now = mutableState.value
        if (now == StremioImportState.Importing) return
        job?.cancel()
        job = null
        mutableState.value = StremioImportState.Idle
    }

    private suspend fun publish(next: StremioImportState) {
        currentCoroutineContext().ensureActive()
        mutableState.value = next
    }
}
