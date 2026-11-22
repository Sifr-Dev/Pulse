package io.pulse.observability.data

import io.pulse.observability.probe.FailureKind
import io.pulse.observability.probe.Measurement
import io.pulse.observability.probe.Outcome
import io.pulse.observability.probe.StageName
import io.pulse.observability.probe.StageTiming
import org.junit.Assert.assertEquals
import org.junit.Test

class MeasurementMappingTest {
    @Test fun `failed measurement survives persistence mapping`() {
        val original = Measurement(
            id = 3, target = "http://example.com/", startedAtEpochMillis = 123,
            outcome = Outcome.FAILURE, totalNanos = 4_321,
            stages = listOf(StageTiming(StageName.DNS, 21), StageTiming(StageName.TCP, 4_300)),
            resolvedAddress = "127.0.0.1", failureStage = StageName.TCP,
            failureCode = FailureKind.TCP_FAILURE, failureMessage = "Connection refused",
        )
        assertEquals(original, original.toEntity().toMeasurement())
    }
}
