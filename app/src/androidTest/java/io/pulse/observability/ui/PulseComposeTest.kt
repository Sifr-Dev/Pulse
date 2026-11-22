package io.pulse.observability.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.pulse.observability.probe.FailureKind
import io.pulse.observability.probe.Measurement
import io.pulse.observability.probe.Outcome
import io.pulse.observability.probe.StageName
import io.pulse.observability.probe.StageTiming
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PulseComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun initialScreenHasNoSampleResults() {
        compose.setContent { MeasureScreen(PulseUiState(), {}, {}, {}) }
        compose.onNodeWithText("No measurement yet").assertExists()
        compose.onNodeWithText("There are no sample readings.", substring = true).assertExists()
        compose.onNodeWithTag("run_probe").assertIsEnabled()
    }

    @Test fun enteringTargetAndRunUsesCallback() {
        var target = ""
        var runs = 0
        compose.setContent { MeasureScreen(PulseUiState(), { target = it }, { runs++ }, {}) }
        compose.onNodeWithTag("target_input").performTextInput("https://example.com/")
        compose.onNodeWithTag("run_probe").performClick()
        assertEquals("https://example.com/", target)
        assertEquals(1, runs)
    }

    @Test fun runningStateOnlyCancelsAndDisablesTarget() {
        compose.setContent { MeasureScreen(PulseUiState(targetInput = "https://example.com", running = true), {}, {}, {}) }
        compose.onNodeWithTag("cancel_probe").assertIsEnabled()
        compose.onNodeWithTag("target_input").assertIsNotEnabled()
        compose.onNodeWithTag("run_probe").assertDoesNotExist()
    }

    @Test fun realMeasurementStateRendersEachCompletedStage() {
        val success = Measurement(
            id = 1, target = "https://example.com/", startedAtEpochMillis = 0,
            outcome = Outcome.SUCCESS, totalNanos = 50_000_000,
            stages = listOf(
                StageTiming(StageName.DNS, 1_000_000), StageTiming(StageName.TCP, 2_000_000),
                StageTiming(StageName.TLS, 3_000_000), StageTiming(StageName.HTTP_HEADERS, 4_000_000),
                StageTiming(StageName.HTTP_BODY, 5_000_000),
            ),
            resolvedAddress = "192.0.2.1", responseCode = 200, bodyBytes = 123,
        )
        compose.setContent { MeasureScreen(PulseUiState(latest = success), {}, {}, {}) }
        compose.onNodeWithTag("result_status").assertExists()
        compose.onNodeWithText("HTTP 200").assertExists()
        compose.onNodeWithText("https://example.com/").assertExists()
        compose.onNodeWithText("http_headers").assertExists()
        compose.onNodeWithText("123").assertExists()
    }

    @Test fun typedFailureIsRenderedWithItsStage() {
        // setContent may be called once per ComposeTestRule invocation, so the
        // failure case lives in its own test rather than a second setContent.
        val failure = Measurement(
            id = 2, target = "https://example.com/", startedAtEpochMillis = 0,
            outcome = Outcome.FAILURE, totalNanos = 50_000_000, stages = emptyList(),
            responseCode = null, failureStage = StageName.DNS,
            failureCode = FailureKind.DNS_FAILURE, failureMessage = "The system resolver could not resolve this hostname.",
        )
        compose.setContent { MeasureScreen(PulseUiState(latest = failure), {}, {}, {}) }
        compose.onNodeWithText("Measurement failed").assertExists()
        compose.onNodeWithText("dns_failure", substring = true).assertExists()
    }

    @Test fun invalidTargetShowsValidationNotHistory() {
        compose.setContent { MeasureScreen(PulseUiState(validationError = "Only http:// and https:// URLs are supported."), {}, {}, {}) }
        compose.onNodeWithText("Only http:// and https:// URLs are supported.").assertExists()
        compose.onNodeWithTag("result_status").assertDoesNotExist()
    }
}
