package io.pulse.observability.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.pulse.observability.probe.FailureKind
import io.pulse.observability.probe.Measurement
import io.pulse.observability.probe.Outcome
import io.pulse.observability.probe.RedirectKind
import io.pulse.observability.probe.StageName
import io.pulse.observability.probe.StageTiming

@Entity(tableName = "measurements")
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val target: String,
    val startedAtEpochMillis: Long,
    val outcome: Outcome,
    val totalNanos: Long,
    val dnsNanos: Long?,
    val tcpNanos: Long?,
    val tlsNanos: Long?,
    val headersNanos: Long?,
    val bodyNanos: Long?,
    val resolvedAddress: String?,
    val responseCode: Int?,
    val httpVersion: String?,
    val tlsProtocol: String?,
    val tlsCipherSuite: String?,
    val bodyBytes: Long?,
    val firstResponseByteNanos: Long?,
    val interimResponses: Int? = null,
    val redirectTarget: String? = null,
    val redirectKind: RedirectKind? = null,
    val failureStage: StageName?,
    val failureCode: FailureKind?,
    val failureMessage: String?,
    val failedStageDurationNanos: Long? = null,
)

fun Measurement.toEntity(): MeasurementEntity = MeasurementEntity(
    id = id,
    target = target,
    startedAtEpochMillis = startedAtEpochMillis,
    outcome = outcome,
    totalNanos = totalNanos,
    dnsNanos = duration(StageName.DNS),
    tcpNanos = duration(StageName.TCP),
    tlsNanos = duration(StageName.TLS),
    headersNanos = duration(StageName.HTTP_HEADERS),
    bodyNanos = duration(StageName.HTTP_BODY),
    resolvedAddress = resolvedAddress,
    responseCode = responseCode,
    httpVersion = httpVersion,
    tlsProtocol = tlsProtocol,
    tlsCipherSuite = tlsCipherSuite,
    bodyBytes = bodyBytes,
    firstResponseByteNanos = firstResponseByteNanos,
    interimResponses = interimResponses,
    redirectTarget = redirectTarget,
    redirectKind = redirectKind,
    failureStage = failureStage,
    failureCode = failureCode,
    failureMessage = failureMessage,
    failedStageDurationNanos = failedStageDurationNanos,
)

fun MeasurementEntity.toMeasurement(): Measurement = Measurement(
    id = id,
    target = target,
    startedAtEpochMillis = startedAtEpochMillis,
    outcome = outcome,
    totalNanos = totalNanos,
    stages = listOfNotNull(
        dnsNanos?.let { StageTiming(StageName.DNS, it) },
        tcpNanos?.let { StageTiming(StageName.TCP, it) },
        tlsNanos?.let { StageTiming(StageName.TLS, it) },
        headersNanos?.let { StageTiming(StageName.HTTP_HEADERS, it) },
        bodyNanos?.let { StageTiming(StageName.HTTP_BODY, it) },
    ),
    resolvedAddress = resolvedAddress,
    responseCode = responseCode,
    httpVersion = httpVersion,
    tlsProtocol = tlsProtocol,
    tlsCipherSuite = tlsCipherSuite,
    bodyBytes = bodyBytes,
    firstResponseByteNanos = firstResponseByteNanos,
    interimResponses = interimResponses,
    redirectTarget = redirectTarget,
    redirectKind = redirectKind,
    failureStage = failureStage,
    failureCode = failureCode,
    failureMessage = failureMessage,
    failedStageDurationNanos = failedStageDurationNanos,
)
