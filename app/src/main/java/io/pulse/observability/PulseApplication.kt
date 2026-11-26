package io.pulse.observability

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import io.pulse.observability.data.HistoryRepository
import io.pulse.observability.data.PulseDatabase
import io.pulse.observability.data.SettingsRepository
import io.pulse.observability.export.MeasurementExporter
import io.pulse.observability.probe.networkProbe

private val Context.settingsStore by preferencesDataStore(name = "pulse_settings")

class PulseApplication : Application() {
    val database: PulseDatabase by lazy {
        Room.databaseBuilder(this, PulseDatabase::class.java, "pulse.db")
            .addMigrations(PulseDatabase.MIGRATION_1_2)
            .build()
    }
    val history: HistoryRepository by lazy { HistoryRepository(database.measurements()) }
    val settings: SettingsRepository by lazy { SettingsRepository(settingsStore) }
    val exporter: MeasurementExporter by lazy { MeasurementExporter(BuildConfig.VERSION_NAME) }
    val probe by lazy { networkProbe() }
}
