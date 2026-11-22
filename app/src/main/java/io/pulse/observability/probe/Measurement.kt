package io.pulse.observability.probe

import java.util.Locale

data class ProbeConfig(
    val dnsTimeoutMillis: Long = 8_000,
    val connectTimeoutMillis: Long = 8_000,
    val tlsTimeoutMillis: Long = 10_000,
    val headersTimeoutMillis: Long = 15_000,
    val bodyTimeoutMillis: Long = 10_000,
    val totalTimeoutMillis: Long = 45_000,
    val maxBodyBytes: Int = 65_536,
    val maxHeaderBytes: Int = 32_768,
) {
    init {
        require(dnsTimeoutMillis > 0 && connectTimeoutMillis > 0 && tlsTimeoutMillis > 0)
        require(headersTimeoutMillis > 0 && bodyTimeoutMillis > 0 && totalTimeoutMillis > 0)
        require(maxBodyBytes in 1..1_048_576) { "Body limit must be between 1 byte and 1 MiB" }
        require(maxHeaderBytes in 1_024..65_536) { "Header limit must be between 1 and 64 KiB" }
    }
}

enum class StageName(val wireName: String) {
    DNS("dns"),
    TCP("tcp"),
    TLS("tls"),
    HTTP_HEADERS("http_headers"),
    HTTP_BODY("http_body"),
}

data class StageTiming(val stage: StageName, val durationNanos: Long)

enum class FailureKind(val wireName: String) {
    DNS_FAILURE("dns_failure"),
    DNS_TIMEOUT("dns_timeout"),
    TCP_FAILURE("tcp_failure"),
    TCP_TIMEOUT("tcp_timeout"),
    TLS_FAILURE("tls_failure"),
    TLS_CERTIFICATE("tls_certificate"),
    TLS_TIMEOUT("tls_timeout"),
    HTTP_FAILURE("http_failure"),
    HTTP_PROTOCOL("http_protocol"),
    HTTP_TIMEOUT("http_timeout"),
    BODY_TOO_LARGE("body_too_large"),
    BODY_TIMEOUT("body_timeout"),
    BODY_FAILURE("body_failure"),
    TOTAL_TIMEOUT("total_timeout"),
}

enum class Outcome { SUCCESS, FAILURE }

/** Persistable metadata only: no response body, cookies, authorization, or socket streams. */
data class Measurement(
    val id: Long = 0,
    val target: String,
    val startedAtEpochMillis: Long,
    val outcome: Outcome,
    val totalNanos: Long,
    val stages: List<StageTiming>,
    val resolvedAddress: String? = null,
    val responseCode: Int? = null,
    val httpVersion: String? = null,
    val tlsProtocol: String? = null,
    val tlsCipherSuite: String? = null,
    val bodyBytes: Long? = null,
    val firstResponseByteNanos: Long? = null,
    val failureStage: StageName? = null,
    val failureCode: FailureKind? = null,
    val failureMessage: String? = null,
    val failedStageDurationNanos: Long? = null,
) {
    fun duration(stage: StageName): Long? = stages.firstOrNull { it.stage == stage }?.durationNanos
}

sealed interface ProbeResult {
    val measurement: Measurement

    data class Success(override val measurement: Measurement) : ProbeResult
    data class Failure(override val measurement: Measurement) : ProbeResult {
        init {
            require(measurement.failureCode != null && measurement.failureStage != null)
        }
    }
}

internal class StageFailure(
    val kind: FailureKind,
    val stage: StageName,
    val durationNanos: Long,
    cause: Throwable? = null,
) : Exception(kind.wireName, cause)

internal class HttpProtocolException(message: String) : Exception(message)
internal class BodyLimitException(val contentLength: Long?) : Exception("Response body exceeds configured limit")

fun FailureKind.userMessage(): String = when (this) {
    FailureKind.DNS_FAILURE -> "The system resolver could not resolve this hostname."
    FailureKind.DNS_TIMEOUT -> "DNS resolution exceeded its time limit."
    FailureKind.TCP_FAILURE -> "A TCP connection could not be established."
    FailureKind.TCP_TIMEOUT -> "The TCP connection exceeded its time limit."
    FailureKind.TLS_FAILURE -> "The secure connection could not be established."
    FailureKind.TLS_CERTIFICATE -> "The server certificate is untrusted or does not match the hostname."
    FailureKind.TLS_TIMEOUT -> "The TLS handshake exceeded its time limit."
    FailureKind.HTTP_FAILURE -> "The server closed the connection or an HTTP I/O error occurred."
    FailureKind.HTTP_PROTOCOL -> "The server returned an invalid or unsupported HTTP response."
    FailureKind.HTTP_TIMEOUT -> "Waiting for HTTP response headers exceeded its time limit."
    FailureKind.BODY_TOO_LARGE -> "The response body exceeds the configured measurement limit."
    FailureKind.BODY_TIMEOUT -> "Reading the bounded response body exceeded its time limit."
    FailureKind.BODY_FAILURE -> "The response body ended unexpectedly or could not be read."
    FailureKind.TOTAL_TIMEOUT -> "The measurement exceeded its overall time limit."
}

fun Long.formatMilliseconds(): String = String.format(Locale.ROOT, "%.1f ms", this / 1_000_000.0)
