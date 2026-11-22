package io.pulse.observability.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [MeasurementEntity::class], version = 1, exportSchema = true)
abstract class PulseDatabase : RoomDatabase() {
    abstract fun measurements(): MeasurementDao
}
