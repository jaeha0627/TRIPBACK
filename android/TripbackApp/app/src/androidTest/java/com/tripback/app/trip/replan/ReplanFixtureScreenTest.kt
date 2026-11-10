package com.tripback.app.trip.replan

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

/** In-process fixture UI only. No MainActivity, HTTP, Provider or navigation initialization. */
class ReplanFixtureScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun fixtureRegistrationAndComparisonAreExplicitThenClose() {
        var open by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if (open) ReplanFixtureScreen { open = false } else Text("Fixture 닫힘 · 일반 DEMO 유지")
        } }
        compose.onNodeWithTag("fixtureOnlyWarning").performScrollTo().assertTextContains("실제 서버 검증 계획 아님", substring = true)
        compose.onNodeWithTag("total50").performScrollTo().performClick()
        compose.onNodeWithTag("registerReplan").performScrollTo().performClick()
        compose.onNodeWithTag("submitFakeRegistration").assertIsNotEnabled()
        compose.onNodeWithTag("confirmFakeRegistration").performClick()
        compose.onNodeWithTag("submitFakeRegistration").performClick()
        compose.waitUntil(5000) {
            runCatching { compose.onNodeWithTag("requestReplanPreview").assertIsEnabled(); true }.getOrDefault(false)
        }
        compose.onNodeWithTag("requestReplanPreview").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("afterFeasibility").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("afterFeasibility").performScrollTo().assertTextEquals("FAKE · FEASIBLE")
        compose.onNodeWithTag("previewEta1").performScrollTo().assertTextContains("UNVERIFIED", substring = true)
        compose.onNodeWithTag("rejectReplanPreview").performScrollTo().performClick()
        compose.onNodeWithText("Fixture 닫힘 · 일반 DEMO 유지").assertExists()
    }
}
