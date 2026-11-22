package io.pulse.observability.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.pulse.observability.probe.FailureKind
import io.pulse.observability.probe.Outcome
import io.pulse.observability.probe.StageName
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomHistoryTest {
    private lateinit var database: PulseDatabase
    private lateinit var dao: MeasurementDao

    @Before fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), PulseDatabase::class.java,
        ).build()
        dao = database.measurements()
    }

    @After fun closeDatabase() = database.close()

    @Test fun successfulAndFailedMeasurementsRoundTrip() = runBlocking {
        dao.insert(MeasurementEntity(
            target = "https://example.com/", startedAtEpochMillis = 2, outcome = Outcome.SUCCESS,
            totalNanos = 50, dnsNanos = 1, tcpNanos = 2, tlsNanos = 3, headersNanos = 4, bodyNanos = 5,
            resolvedAddress = "192.0.2.1", responseCode = 200, httpVersion = "1.1",
            tlsProtocol = "TLSv1.3", tlsCipherSuite = "TLS_AES_128_GCM_SHA256", bodyBytes = 12,
            firstResponseByteNanos = 30, failureStage = null, failureCode = null, failureMessage = null,
        ))
        dao.insert(MeasurementEntity(
            target = "http://example.com/", startedAtEpochMillis = 1, outcome = Outcome.FAILURE,
            totalNanos = 10, dnsNanos = 3, tcpNanos = null, tlsNanos = null, headersNanos = null, bodyNanos = null,
            resolvedAddress = null, responseCode = null, httpVersion = null, tlsProtocol = null,
            tlsCipherSuite = null, bodyBytes = null, firstResponseByteNanos = null,
            failureStage = StageName.DNS, failureCode = FailureKind.DNS_FAILURE, failureMessage = "Not found",
        ))
        val rows = dao.all()
        assertEquals(2, rows.size)
        assertEquals("https://example.com/", rows[0].target)
        assertEquals(Outcome.SUCCESS, rows[0].outcome)
        assertEquals(12L, rows[0].bodyBytes)
        assertEquals(FailureKind.DNS_FAILURE, rows[1].failureCode)
        assertEquals(rows.map { it.toMeasurement() }, dao.observeAll().first().map { it.toMeasurement() })
    }

    @Test fun atomicRetentionRemovesOldestRows() = runBlocking {
        repeat(MeasurementDao.MAX_HISTORY + 3) { index ->
            dao.insertAndTrim(MeasurementEntity(
                target = "http://example.com/$index", startedAtEpochMillis = index.toLong(),
                outcome = Outcome.SUCCESS, totalNanos = 1, dnsNanos = null, tcpNanos = null,
                tlsNanos = null, headersNanos = null, bodyNanos = null, resolvedAddress = null,
                responseCode = null, httpVersion = null, tlsProtocol = null, tlsCipherSuite = null,
                bodyBytes = null, firstResponseByteNanos = null, failureStage = null, failureCode = null,
                failureMessage = null,
            ))
        }
        assertEquals(MeasurementDao.MAX_HISTORY, dao.count())
        val rows = dao.all()
        assertTrue(rows.all { it.startedAtEpochMillis >= 3 })
        assertEquals((MeasurementDao.MAX_HISTORY + 2).toLong(), rows.first().startedAtEpochMillis)
    }

    @Test fun clearDeletesOnlyLocalHistory() = runBlocking {
        dao.insert(MeasurementEntity(
            target = "https://example.com/", startedAtEpochMillis = 2, outcome = Outcome.FAILURE,
            totalNanos = 0, dnsNanos = null, tcpNanos = null, tlsNanos = null, headersNanos = null,
            bodyNanos = null, resolvedAddress = null, responseCode = null, httpVersion = null,
            tlsProtocol = null, tlsCipherSuite = null, bodyBytes = null, firstResponseByteNanos = null,
            failureStage = StageName.DNS, failureCode = FailureKind.DNS_TIMEOUT, failureMessage = "Timeout",
        ))
        dao.clear()
        assertTrue(dao.all().isEmpty())
    }
}
