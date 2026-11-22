package io.pulse.observability.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.pulse.observability.PulseApplication
import io.pulse.observability.export.ExportFormat
import io.pulse.observability.probe.Measurement
import io.pulse.observability.probe.NetworkProbe
import io.pulse.observability.probe.TargetParser
import io.pulse.observability.probe.TargetValidationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant

data class PulseUiState(
    val targetInput: String = "",
    val running: Boolean = false,
    val latest: Measurement? = null,
    val history: List<Measurement> = emptyList(),
    val selectedTab: Int = 0,
    val validationError: String? = null,
    val message: String? = null,
)

class MeasurementViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as PulseApplication
    private val _state = MutableStateFlow(PulseUiState())
    val state: StateFlow<PulseUiState> = _state.asStateFlow()
    private var probeJob: Job? = null

    init {
        viewModelScope.launch {
            app.history.measurements.collect { rows ->
                _state.update { it.copy(history = rows) }
            }
        }
        viewModelScope.launch {
            app.settings.lastTarget.collect { saved ->
                _state.update { if (it.targetInput.isEmpty()) it.copy(targetInput = saved) else it }
            }
        }
    }

    fun setTarget(value: String) {
        if (value.length <= 2_048) _state.update { it.copy(targetInput = value, validationError = null, message = null) }
    }

    fun selectTab(tab: Int) = _state.update { it.copy(selectedTab = tab.coerceIn(0, 1)) }

    fun run() {
        if (probeJob?.isActive == true) return
        val target = try {
            TargetParser.parse(state.value.targetInput)
        } catch (error: TargetValidationException) {
            _state.update { it.copy(validationError = error.message, latest = null) }
            return
        }
        _state.update { it.copy(running = true, validationError = null, message = null, latest = null, selectedTab = 0) }
        probeJob = viewModelScope.launch {
            try {
                val result = app.probe.measure(target)
                // Only completed attempts enter history. Cancellation is deliberately not a measurement.
                val recorded = app.history.record(result)
                app.settings.saveLastTarget(target.normalized)
                _state.update { it.copy(latest = recorded) }
            } catch (error: CancellationException) {
                throw error
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    fun cancel() {
        probeJob?.cancel()
        probeJob = null
        _state.update { it.copy(running = false, message = "Measurement cancelled; no history record was added.") }
    }

    fun clearHistory() {
        if (probeJob?.isActive == true) return
        viewModelScope.launch {
            app.history.clear()
            _state.update { it.copy(latest = null, message = "Local history cleared.") }
        }
    }

    fun export(uri: Uri, format: ExportFormat) {
        if (probeJob?.isActive == true) return
        viewModelScope.launch {
            _state.update { it.copy(message = "Preparing export…") }
            try {
                val count = withContext(Dispatchers.IO) {
                    val stream = app.contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("The selected document could not be opened")
                    app.exporter.export(app.history.snapshot(), format, stream)
                }
                _state.update { it.copy(message = "Exported $count measurements as ${format.extension.uppercase()}.") }
            } catch (_: Exception) {
                _state.update { it.copy(message = "Export failed. The selected document was not readable or writable.") }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        probeJob?.cancel()
        super.onCleared()
    }
}
