package com.tripback.app.trip.replan

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.tripback.app.trip.*
import com.tripback.app.trip.progress.JourneyPhase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.*

/** Pure panel tests. No MainActivity, SDK, HTTP or actual registration. Compile separately from device execution. */
class ReplanDebugPanelTest {
    @get:Rule val compose = createComposeRule()
    private val at = Instant.parse("2026-11-07T01:00:00Z")
    @Test fun httpRegistrationRequiresCostAcknowledgementAndOneExplicitApproval() {
        var registrations = 0
        var current by mutableStateOf(view())
        compose.setContent { MaterialTheme {
            ReplanDebugPanel(current, {}, {
                registrations++
                current = current.copy(canRegister = false, requestState = ReplanRequestState(ReplanRequestPhase.LOADING))
            }, {}, {}, {}, ReplanUiScenario.NORMAL, {}, useHttp = true)
        } }
        compose.runOnIdle { assertEquals(0, registrations) }
        compose.onNodeWithTag("previewOnly").assertTextContains(
            "CLIENT_REPORTED_NOT_VERIFIED",
            substring = true
        )
        compose.onNodeWithTag("httpObservationWarning").assertExists()
        compose.onNodeWithTag("scenarioNORMAL").assertDoesNotExist()
        compose.onNodeWithTag("registerReplan").performScrollTo().performClick()
        compose.onNodeWithTag("submitFakeRegistration").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, registrations) }
        compose.onNodeWithTag("confirmFakeRegistration").performClick()
        compose.onNodeWithTag("submitFakeRegistration").performClick()
        compose.onNodeWithTag("registerReplan").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, registrations) }
    }
    private fun view() = ReplanDebugView("가상 선택 코스", "가상 체류 공간", JourneyPhase.STAYING,
        30, 600, 30, at.plusSeconds(10800).atOffset(ZoneOffset.UTC), 30, null,
        ReplanRequestState(), true, false, listOf("가상 체류 공간" to 30L, "가상 휴식 공간" to 30L), 0)
    private fun projection(minutes: Long, status: ValidationFeasibility = ValidationFeasibility.FEASIBLE) =
        ReplanProjection(status, ValidationFeasibility.FEASIBLE, at.plusSeconds(minutes * 60).atOffset(ZoneOffset.UTC),
            7200, 120, true, true, 20, listOf(ReplanEta(1, 10, "FAKE_UI", ValidationDataMode.DEMO_SIMULATED,
                "UNVERIFIED", at, false)), setOf("FAKE_REPOSITORY_NO_HTTP"))
    private fun preview(status: ValidationFeasibility = ValidationFeasibility.FEASIBLE) = ReplanPreview("PREVIEW_ONLY",
        1, at.plusSeconds(1800), at, "CLIENT_REPORTED_NOT_VERIFIED", 0, 30,
        at.plusSeconds(10800).atOffset(ZoneOffset.UTC), ValidationPoint(0.0, 0.0), emptyList(), emptySet(),
        projection(70), projection(90, status), listOf("FAKE_ONLY"), 4)

    @Test fun explicitFakeRegistrationThenThirtyToFiftyComparisonAndReject() {
        var current by mutableStateOf(view())
        var closed by mutableStateOf(false)
        var registrations = 0
        var previews = 0
        compose.setContent { MaterialTheme {
            if (closed) androidx.compose.material3.Text("닫힘 · 원래 일정 유지")
            else ReplanDebugPanel(current, { current = current.copy(requestedTotalMinutes = it) }, {
                registrations++
                current = current.copy(canPreview = true, requestState = ReplanRequestState(
                    registrationStatus = PlanRegistrationStatus.REGISTERED, expiresAt = at.plusSeconds(1800)))
            }, {
                previews++
                current = current.copy(requestState = current.requestState.copy(
                    phase = ReplanRequestPhase.PREVIEW_READY, preview = preview()))
            }, {}, { closed = true }, ReplanUiScenario.NORMAL, {})
        } }
        compose.onNodeWithTag("requestReplanPreview").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("total50").performScrollTo().performClick()
        compose.onNodeWithTag("requestedTotal").assertTextContains("50분", substring = true)
        compose.onNodeWithTag("registerReplan").performScrollTo().performClick()
        compose.onNodeWithTag("submitFakeRegistration").assertIsNotEnabled()
        compose.onNodeWithTag("confirmFakeRegistration").performClick()
        compose.onNodeWithTag("submitFakeRegistration").performClick()
        compose.onNodeWithTag("requestReplanPreview").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("afterFeasibility").performScrollTo().assertTextEquals("FAKE · FEASIBLE")
        compose.onNodeWithTag("afterReturn").assertTextContains("11:30:00", substring = true)
        compose.onNodeWithTag("previewEta1").performScrollTo().assertTextContains("UNVERIFIED", substring = true)
        compose.onNodeWithTag("rejectReplanPreview").performScrollTo().performClick()
        compose.onNodeWithText("닫힘 · 원래 일정 유지").assertExists()
        compose.runOnIdle { assertEquals(1, registrations); assertEquals(1, previews); assertEquals(30L, current.originalStayMinutes) }
    }
    @Test fun blockedBaselineAndUnknownResultAreTruthful() {
        var current by mutableStateOf(view().copy(blockedReason = "로컬 일정 변경 · Preview 차단", canRegister = false,
            requestState = ReplanRequestState(phase = ReplanRequestPhase.ERROR, error = ReplanError.PLAN_REVISION_MISMATCH)))
        compose.setContent { MaterialTheme {
            ReplanDebugPanel(current, {}, {}, {}, {}, {}, ReplanUiScenario.NORMAL, {})
        } }
        compose.onNodeWithTag("registerReplan").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("requestReplanPreview").assertIsNotEnabled()
        compose.onNodeWithTag("replanError").assertTextContains("PLAN_REVISION_MISMATCH", substring = true)
        compose.runOnIdle { current = view().copy(requestState = ReplanRequestState(
            phase = ReplanRequestPhase.PREVIEW_READY, preview = preview(ValidationFeasibility.UNKNOWN))) }
        compose.onNodeWithTag("afterFeasibility").performScrollTo().assertTextEquals("FAKE · UNKNOWN")
        compose.onNodeWithTag("notServerApproved").performScrollTo().assertTextContains("서버 일정 확정 아님", substring = true)
    }
    @Test fun registrationValidationFailureShowsOnlyFixedDiagnosticAndUncertainty() {
        val current = view().copy(canPreview = false, requestState = ReplanRequestState(
            phase = ReplanRequestPhase.ERROR, error = ReplanError.INVALID_RESPONSE,
            diagnostic = ReplanDiagnostic.COORDINATES_CHANGED, registrationUnconfirmed = true))
        compose.setContent { MaterialTheme {
            ReplanDebugPanel(current, {}, {}, {}, {}, {}, ReplanUiScenario.NORMAL, {})
        } }
        compose.onNodeWithTag("registrationStatus").performScrollTo()
            .assertTextContains("\uc11c\ubc84 \ub4f1\ub85d \uc5ec\ubd80 \ubbf8\ud655\uc778")
        compose.onNodeWithTag("replanDiagnostic").performScrollTo().assertTextContains("COORDINATES_CHANGED")
        compose.onNodeWithTag("requestReplanPreview").performScrollTo().assertIsNotEnabled()
    }

}
