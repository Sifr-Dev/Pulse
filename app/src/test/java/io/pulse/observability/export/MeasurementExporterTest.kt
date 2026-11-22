package io.pulse.observability.export

import io.pulse.observability.probe.FailureKind
import io.pulse.observability.probe.Measurement
import io.pulse.observability.probe.Outcome
import io.pulse.observability.probe.StageName
import io.pulse.observability.probe.StageTiming
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream

class MeasurementExporterTest {
    private val measurement = Measurement(
        id = 42,
        target = "https://example.com/a?b=\"quoted\",x=1",
        startedAtEpochMillis = 0,
        outcome = Outcome.SUCCESS,
        totalNanos = 50,
        stages = listOf(
            StageTiming(StageName.DNS, 10), StageTiming(StageName.TCP, 10),
            StageTiming(StageName.TLS, 10), StageTiming(StageName.HTTP_HEADERS, 10),
            StageTiming(StageName.HTTP_BODY, 10),
        ),
        responseCode = 200,
        httpVersion = "1.1",
        bodyBytes = 3,
    )

    @Test fun `jsonl is versioned and includes envelope and measurement`() {
        val output = ByteArrayOutputStream()
        assertEquals(1, MeasurementExporter("0.1.0").export(listOf(measurement), ExportFormat.JSONL, output, 0))
        val lines = output.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
        val envelope = JSONObject(lines[0])
        assertEquals("pulse.measurements", envelope.getString("format"))
        assertEquals(1, envelope.getInt("version"))
        assertEquals("1970-01-01T00:00:00Z", envelope.getString("exported_at"))
        val record = JSONObject(lines[1])
        assertEquals(1, record.getInt("schema_version"))
        assertEquals(42, record.getLong("id"))
        assertEquals(measurement.target, record.getString("target"))
        assertEquals(10, record.getLong("dns_ns"))
        assertFalse(record.has("response_body"))
        assertFalse(record.has("headers"))
    }

    @Test fun `csv quotes commas quotes newlines and includes failures`() {
        val failed = measurement.copy(
            outcome = Outcome.FAILURE,
            responseCode = null,
            failureStage = StageName.DNS,
            failureCode = FailureKind.DNS_FAILURE,
            failureMessage = "line one\nline \"two\", failed",
        )
        val output = ByteArrayOutputStream()
        MeasurementExporter("0.1.0").export(listOf(failed), ExportFormat.CSV, output, 0)
        val csv = output.toString(Charsets.UTF_8)
        assertTrueCsv(csv)
        assertTrueCsv(csv)
        org.junit.Assert.assertTrue(csv.contains("\"https://example.com/a?b=\"\"quoted\"\",x=1\""))
        org.junit.Assert.assertTrue(csv.contains("\"line one\nline \"\"two\"\", failed\""))
        assertFalse(csv.contains(EXPORT_VERSION.toString() + "," + EXPORT_VERSION.toString()))
    }

    @Test fun `empty exports retain schema`() {
        val json = ByteArrayOutputStream()
        val csv = ByteArrayOutputStream()
        MeasurementExporter("0.1.0").export(emptyList(), ExportFormat.JSONL, json)
        MeasurementExporter("0.1.0").export(emptyList(), ExportFormat.CSV, csv)
        assertEquals(1, json.toString(Charsets.UTF_8).lines().count { it.isNotEmpty() })
        org.junit.Assert.assertTrue(csv.toString(Charsets.UTF_8).startsWith("schema_version,record_id,target"))
    }

    private fun assertTrueCsv(value: String) = org.junit.Assert.assertTrue(value.contains("\r\n"))
}
