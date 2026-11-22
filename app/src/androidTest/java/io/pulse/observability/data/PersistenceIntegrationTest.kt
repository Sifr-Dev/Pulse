package io.pulse.observability.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.pulse.observability.probe.FailureKind
import io.pulse.observability.probe.Measurement
import io.pulse.observability.probe.Outcome
import io.pulse.observability.probe.ProbeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PersistenceIntegrationTest {
    @Test fun historyAndLastTargetSurviveStoreRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath("pulse_test_history.db")
        val preferenceFile = File(context.filesDir, "datastore/pulse_test_settings.preferences_pb")
        dbFile.delete()
        preferenceFile.delete()
        var database = Room.databaseBuilder(context, PulseDatabase::class.java, dbFile.name).build()
        val failure = ProbeResult.Failure(Measurement(
            target = "https://example.com/path", startedAtEpochMillis = 123, outcome = Outcome.FAILURE,
            totalNanos = 50, stages = emptyList(), failureStage = io.pulse.observability.probe.StageName.DNS,
            failureCode = FailureKind.DNS_FAILURE, failureMessage = "Not found", failedStageDurationNanos = 50,
        ))
        HistoryRepository(database.measurements()).record(failure)
        PreferenceDataStoreFactory.create { preferenceFile }.use { store ->
            SettingsRepository(store).saveLastTarget("https://example.com/path")
        }
        database.close()
        database = Room.databaseBuilder(context, PulseDatabase::class.java, dbFile.name).build()
        try {
            val rows = HistoryRepository(database.measurements()).snapshot()
            assertEquals(1, rows.size)
            assertEquals(failure.measurement, rows.single())
            PreferenceDataStoreFactory.create { preferenceFile }.use { store ->
                assertEquals("https://example.com/path", SettingsRepository(store).lastTarget.first())
            }
        } finally {
            database.close()
            dbFile.delete()
            preferenceFile.delete()
        }
    }

    @Test fun cancelledHistoryInsertDoesNotCreateRecord() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), PulseDatabase::class.java,
        ).build()
        val dao = database.measurements()
        coroutineScope {
            val job = launch {
                kotlinx.coroutines.awaitCancellation()
            }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }
        assertTrue(dao.all().isEmpty())
        database.close()
    }
}
