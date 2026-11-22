package io.pulse.observability.export

import io.pulse.observability.probe.Measurement
import io.pulse.observability.probe.StageName
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant

const val EXPORT_VERSION = 1

enum class ExportFormat(val extension: String, val mimeType: String) {
    JSONL("jsonl", "application/x-ndjson"),
    CSV("csv", "text/csv"),
}

/** Deterministic, versioned records; output is streamed and response payloads are never present. */
class MeasurementExporter(private val appVersion: String) {
    fun export(
        measurements: List<Measurement>,
        format: ExportFormat,
        output: OutputStream,
        exportedAtEpochMillis: Long = System.currentTimeMillis(),
    ): Int {
        BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8)).use { writer ->
            when (format) {
                ExportFormat.JSONL -> writeJsonl(measurements, writer, exportedAtEpochMillis)
                ExportFormat.CSV -> writeCsv(measurements, writer)
            }
        }
        return measurements.size
    }

    private fun writeJsonl(measurements: List<Measurement>, writer: BufferedWriter, exportedAt: Long) {
        writer.write("{\"format\":\"pulse.measurements\",\"version\":$EXPORT_VERSION,")
        writer.write("\"app_version\":${json(appVersion)},\"exported_at\":${json(Instant.ofEpochMilli(exportedAt).toString())}}\n")
        measurements.forEach { measurement ->
            writer.write("{")
            val fields = linkedMapOf<String, Any?>(
                "type" to "measurement",
                "schema_version" to EXPORT_VERSION,
                "id" to measurement.id,
                "target" to measurement.target,
                "started_at" to Instant.ofEpochMilli(measurement.startedAtEpochMillis).toString(),
                "outcome" to measurement.outcome.name,
                "total_ns" to measurement.totalNanos,
                "dns_ns" to measurement.duration(StageName.DNS),
                "tcp_ns" to measurement.duration(StageName.TCP),
                "tls_ns" to measurement.duration(StageName.TLS),
                "http_headers_ns" to measurement.duration(StageName.HTTP_HEADERS),
                "http_body_ns" to measurement.duration(StageName.HTTP_BODY),
                "resolved_address" to measurement.resolvedAddress,
                "response_code" to measurement.responseCode,
                "http_version" to measurement.httpVersion,
                "tls_protocol" to measurement.tlsProtocol,
                "tls_cipher_suite" to measurement.tlsCipherSuite,
                "body_bytes" to measurement.bodyBytes,
                "first_response_byte_ns" to measurement.firstResponseByteNanos,
                "failure_stage" to measurement.failureStage?.wireName,
                "failure_code" to measurement.failureCode?.wireName,
                "failure_message" to measurement.failureMessage,
                "failed_stage_duration_ns" to measurement.failedStageDurationNanos,
            )
            fields.entries.joinTo(writer, ",") { (name, value) ->
                "${json(name)}:${value.jsonValue()}"
            }
            writer.write("}\n")
        }
    }

    private fun writeCsv(measurements: List<Measurement>, writer: BufferedWriter) {
        val columns = listOf(
            "schema_version", "record_id", "target", "started_at_epoch_ms", "outcome", "total_ns",
            "dns_ns", "tcp_ns", "tls_ns", "http_headers_ns", "http_body_ns", "resolved_address",
            "response_code", "http_version", "tls_protocol", "tls_cipher_suite", "body_bytes",
            "first_response_byte_ns", "failure_stage", "failure_code", "failure_message", "failed_stage_duration_ns",
        )
        writer.write(columns.joinToString(","))
        writer.write("\r\n")
        measurements.forEach { measurement ->
            val fields = listOf(
                EXPORT_VERSION.toString(), measurement.id.toString(), measurement.target,
                measurement.startedAtEpochMillis.toString(), measurement.outcome.name, measurement.totalNanos.toString(),
                measurement.duration(StageName.DNS)?.toString().orEmpty(),
                measurement.duration(StageName.TCP)?.toString().orEmpty(),
                measurement.duration(StageName.TLS)?.toString().orEmpty(),
                measurement.duration(StageName.HTTP_HEADERS)?.toString().orEmpty(),
                measurement.duration(StageName.HTTP_BODY)?.toString().orEmpty(),
                measurement.resolvedAddress.orEmpty(), measurement.responseCode?.toString().orEmpty(),
                measurement.httpVersion.orEmpty(), measurement.tlsProtocol.orEmpty(),
                measurement.tlsCipherSuite.orEmpty(), measurement.bodyBytes?.toString().orEmpty(),
                measurement.firstResponseByteNanos?.toString().orEmpty(),
                measurement.failureStage?.wireName.orEmpty(), measurement.failureCode?.wireName.orEmpty(),
                measurement.failureMessage.orEmpty(), measurement.failedStageDurationNanos?.toString().orEmpty(),
            )
            writer.write(fields.joinToString(",") { csv(it) })
            writer.write("\r\n")
        }
    }

    private fun Any?.jsonValue(): String = when (this) {
        null -> "null"
        is Number -> toString()
        else -> json(toString())
    }

    private fun json(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

    private fun csv(value: String): String = if (value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
        "\"${value.replace("\"", "\"\"")}\""
    } else value
}
