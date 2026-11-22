package io.pulse.observability.data

import io.pulse.observability.probe.Measurement
import io.pulse.observability.probe.ProbeResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class HistoryRepository(private val dao: MeasurementDao) {
    val measurements: Flow<List<Measurement>> = dao.observeAll().map { rows -> rows.map { it.toMeasurement() } }

    suspend fun record(result: ProbeResult): Measurement {
        val id = dao.insertAndTrim(result.measurement.toEntity())
        return result.measurement.copy(id = id)
    }

    suspend fun snapshot(): List<Measurement> = dao.all().map { it.toMeasurement() }

    suspend fun clear() = dao.clear()
}
