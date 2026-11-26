package io.pulse.observability.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.pulse.observability.probe.RedirectKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Schema 1 exists on real devices: any measurement saved before this change lives in a
 * version-1 database. These tests open a genuine version-1 file, run the shipped
 * [PulseDatabase.MIGRATION_1_2], and let Room itself validate the resulting schema.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PulseDatabase::class.java)

    @Test fun migrationFrom1To2PreservesRowsAndAddsNullColumns() {
        val databaseName = "pulse_migration_1_2.db"
        helper.createDatabase(databaseName, 1).use { database ->
            database.execSQL(
                """
                INSERT INTO measurements
                    (target, startedAtEpochMillis, outcome, totalNanos, dnsNanos, tcpNanos, tlsNanos,
                     headersNanos, bodyNanos, resolvedAddress, responseCode, httpVersion, tlsProtocol,
                     tlsCipherSuite, bodyBytes, firstResponseByteNanos, failureStage, failureCode,
                     failureMessage, failedStageDurationNanos)
                VALUES
                    ('https://example.com/before', 1700000000000, 'SUCCESS', 1234, 10, 20, 30, 40, 50,
                     '192.0.2.1', 200, '1.1', 'TLSv1.3', 'TLS_AES_128_GCM_SHA256', 7, 60, NULL, NULL,
                     NULL, NULL)
                """.trimIndent(),
            )
        }

        // runMigrationsAndValidate throws if the migrated schema differs from the version-2
        // entity/schema export, so this is the real check rather than a column-name guess.
        helper.runMigrationsAndValidate(databaseName, 2, true, PulseDatabase.MIGRATION_1_2).use { database ->
            database.query("SELECT target, responseCode, interimResponses, redirectTarget, redirectKind FROM measurements").use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals("https://example.com/before", cursor.getString(0))
                assertEquals(200, cursor.getInt(1))
                // Pre-existing rows genuinely never observed these fields; null is the honest
                // value, not a backfilled zero.
                assertEquals(true, cursor.isNull(2))
                assertEquals(true, cursor.isNull(3))
                assertEquals(true, cursor.isNull(4))
            }
        }
        InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(databaseName).delete()
    }

    @Test fun migratedDatabaseRoundTripsRedirectColumnsThroughRoom() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "pulse_migration_roundtrip.db"
        context.getDatabasePath(databaseName).delete()
        helper.createDatabase(databaseName, 1).use { database ->
            assertEquals(1, database.version)
        }

        val database = Room.databaseBuilder(context, PulseDatabase::class.java, databaseName)
            .addMigrations(PulseDatabase.MIGRATION_1_2)
            .build()
        try {
            val dao = database.measurements()
            dao.insert(
                MeasurementEntity(
                    target = "http://127.0.0.1:8080/start", startedAtEpochMillis = 5,
                    outcome = io.pulse.observability.probe.Outcome.SUCCESS, totalNanos = 9,
                    dnsNanos = 1, tcpNanos = 1, tlsNanos = null, headersNanos = 2, bodyNanos = 1,
                    resolvedAddress = "127.0.0.1", responseCode = 301, httpVersion = "1.1",
                    tlsProtocol = null, tlsCipherSuite = null, bodyBytes = 0,
                    firstResponseByteNanos = 3, interimResponses = 1,
                    redirectTarget = "https://secure.example", redirectKind = RedirectKind.UPGRADE,
                    failureStage = null, failureCode = null, failureMessage = null,
                ),
            )
            val row = dao.all().single()
            assertEquals(1, row.interimResponses)
            assertEquals("https://secure.example", row.redirectTarget)
            assertEquals(RedirectKind.UPGRADE, row.redirectKind)
            assertNull(row.failureCode)
            assertEquals(row, row.toMeasurement().toEntity())
        } finally {
            database.close()
            context.getDatabasePath(databaseName).delete()
        }
    }

    @Test fun freshDatabaseIsVersion2() {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, PulseDatabase::class.java,
        ).build()
        try {
            assertEquals(2, database.openHelper.readableDatabase.version)
        } finally {
            database.close()
        }
    }
}
