package io.pulse.observability.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementDao {
    @Insert
    suspend fun insert(measurement: MeasurementEntity): Long

    @Query("SELECT * FROM measurements ORDER BY startedAtEpochMillis DESC, id DESC")
    fun observeAll(): Flow<List<MeasurementEntity>>

    @Query("SELECT * FROM measurements ORDER BY startedAtEpochMillis DESC, id DESC")
    suspend fun all(): List<MeasurementEntity>

    @Query("SELECT * FROM measurements WHERE id = :id")
    suspend fun findById(id: Long): MeasurementEntity?

    @Query("SELECT COUNT(*) FROM measurements")
    suspend fun count(): Int

    @Query("DELETE FROM measurements WHERE id NOT IN (SELECT id FROM measurements ORDER BY startedAtEpochMillis DESC, id DESC LIMIT :limit)")
    suspend fun trimTo(limit: Int)

    @Query("DELETE FROM measurements")
    suspend fun clear()

    @Transaction
    suspend fun insertAndTrim(measurement: MeasurementEntity, limit: Int = MAX_HISTORY): Long {
        val id = insert(measurement)
        trimTo(limit)
        return id
    }

    companion object {
        const val MAX_HISTORY = 2_000
    }
}
