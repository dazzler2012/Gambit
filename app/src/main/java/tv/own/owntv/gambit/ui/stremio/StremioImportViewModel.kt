package tv.own.owntv.gambit.ui.stremio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import tv.own.owntv.gambit.stremio.StremioImporter

/**
 * More → Addons → Import from Stremio. A thin wrapper over [StremioImportModel], which holds the logic.
 * Waiting runs in [viewModelScope], so it also stops when the screen is gone.
 */
class StremioImportViewModel(importer: StremioImporter) : ViewModel() {
    private val model = StremioImportModel(importer, viewModelScope)

    val state: StateFlow<StremioImportState> = model.state

    fun start() = model.start()

    fun toggle(index: Int) = model.toggle(index)

    fun import() = model.import()

    fun cancel() = model.cancel()
}
