package io.pulse.observability.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MeasurementEntity::class], version = 2, exportSchema = true)
abstract class PulseDatabase : RoomDatabase() {
    abstract fun measurements(): MeasurementDao

    companion object {
        /**
         * Adds the interim-1xx count and sanitized redirect boundary recorded from schema 2.
         *
         * The columns are nullable and carry no default, so every existing row keeps meaning:
         * measurements taken before schema 2 genuinely did not observe these fields. No data is
         * rewritten or backfilled, and no column is dropped.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `measurements` ADD COLUMN `interimResponses` INTEGER")
                db.execSQL("ALTER TABLE `measurements` ADD COLUMN `redirectTarget` TEXT")
                db.execSQL("ALTER TABLE `measurements` ADD COLUMN `redirectKind` TEXT")
            }
        }
    }
}
