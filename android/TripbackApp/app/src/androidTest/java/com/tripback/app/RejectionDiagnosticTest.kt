package com.tripback.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.foundation.layout.Column
import com.tripback.app.trip.recommendation.RejectionDiagnostic
import org.junit.Rule
import org.junit.Test

class RejectionDiagnosticTest {
    @get:Rule val compose = createComposeRule()
    @Test fun debugHttpShowsOnlySafeIndexAndAllowlistedReason() {
        compose.setContent {
            Column {
                RejectionDiagnostic(0, "INVALID_TEXT", true)
                RejectionDiagnostic(1, "synthetic-private-payload", true)
            }
        }
        compose.onNodeWithText("거부안 index: 0 · reason: INVALID_TEXT").assertExists()
        compose.onNodeWithText("거부안 index: 1 · reason: UNKNOWN_REJECTION").assertExists()
        compose.onNodeWithText("synthetic-private-payload", substring=true).assertDoesNotExist()
        compose.onAllNodesWithTag("rejectionDiagnostic").assertCountEquals(2)
    }
    @Test fun fakeScreenDoesNotShowHttpDiagnostics() {
        compose.setContent { RejectionDiagnostic(0, "INVALID_TEXT", false) }
        compose.onAllNodesWithTag("rejectionDiagnostic").assertCountEquals(0)
    }
}