package com.tripback.app

import ai.pleos.playground.navi.data.DestinationInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

/** Pure panel test. Never creates MainActivity, NaviHelper or a service connection. */
class DestinationDiagnosticPanelTest {
    @get:Rule val compose = createComposeRule()
    private fun response(id: String, sub: String?) = DestinationInfo(
        latitude = 0.0, longitude = 0.0, name = "synthetic-private-name",
        address = "synthetic-private-address", remainDistance = 0, remainTime = 0,
        tollFare = 0, poiId = id, poiSubId = sub
    )
    @Test fun readOnlyResponseRecomposesAllFieldDiagnostics() {
        val state = DestinationDiagnosticUiState()
        compose.setContent { DestinationDiagnosticPanel(state) }
        compose.onNodeWithText(DESTINATION_DIAGNOSTIC_UI_VERSION).assertExists()
        compose.onNodeWithText("목적지 진단 응답 대기", substring = true).assertExists()
        compose.runOnIdle { state.accept(response("synthetic-private-id", null)) }
        compose.onNodeWithTag("destinationSummary").assertTextContains("INSUFFICIENT_DATA")
        compose.onNodeWithText("POI ID: DIFFERENT").assertExists()
        compose.onNodeWithText("POI Sub ID: NULL").assertExists()
        compose.onNodeWithText("latitude: OUTSIDE_TOLERANCE").assertExists()
        compose.onNodeWithText("longitude: OUTSIDE_TOLERANCE").assertExists()
        compose.onNodeWithText("displayLatitude: ABSENT").assertExists()
        compose.onNodeWithText("displayLongitude: ABSENT").assertExists()
        compose.onNodeWithText("namePresent: true / nameExact: false").assertExists()
        for (raw in listOf("synthetic-private-id", "synthetic-private-name", "synthetic-private-address")) {
            compose.onNodeWithText(raw, substring = true).assertDoesNotExist()
        }
        compose.runOnIdle { state.accept(response("", "")) }
        compose.onNodeWithText("POI ID: EMPTY").assertExists()
        compose.onNodeWithText("POI Sub ID: EMPTY").assertExists()
    }
}
