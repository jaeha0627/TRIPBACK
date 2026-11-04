package com.tripback.app

import androidx.compose.ui.test.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.tripback.app.trip.recommendation.*
import org.junit.Rule
import org.junit.Test

class RecommendationFlowTest {
    @get:Rule val compose=createComposeRule()
    private fun capture(name:String) {
        val file=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"recommendation-$name.png")
        file.outputStream().use {(if(name=="scenario-menu") compose.onNode(isPopup()) else compose.onNodeWithTag("recommendationFlow")).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun load(scenario:FakeScenario) {
        compose.onNodeWithTag("scenarioPicker").performClick()
        capture("scenario-menu")
        compose.onNodeWithTag("scenario"+scenario.name).performClick()
        compose.onNodeWithTag("parked").performScrollTo().performClick()
        capture("input-bottom")
        compose.onNodeWithTag("generate").performClick()
        compose.waitUntil(5000){compose.onAllNodesWithTag("results").fetchSemanticsNodes().isNotEmpty()}
    }
    @Test fun fiveScreensAndSelectionRestoreWithoutNavigation() {
        val restoration=StateRestorationTester(compose)
        restoration.setContent {AuditViewport {RecommendationFlow(onExit={})}}
        compose.onNodeWithText("오늘은 어디로\n떠나볼까요?").assertIsDisplayed()
        capture("input")
        load(FakeScenario.TWO)
        capture("results")
        val cards=compose.onAllNodes(hasTestTag("detailai-proposal-1") or hasTestTag("detailai-proposal-2")).fetchSemanticsNodes()
        org.junit.Assert.assertEquals(2,cards.size)
        org.junit.Assert.assertEquals(cards[0].boundsInRoot.top,cards[1].boundsInRoot.top,1f)
        compose.onNodeWithTag("detailai-proposal-1").performScrollTo().assertIsDisplayed()
        capture("results-bottom")
        compose.onNodeWithTag("detailai-proposal-1").performClick()
        capture("detail")
        compose.onNodeWithTag("selectPlan").performClick()
        capture("ready")
        compose.onNodeWithTag("ready").assertExists()
        compose.onNodeWithTag("navigationDisabled").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("readyReview").performScrollTo().assertIsDisplayed()
        capture("ready-bottom")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("ready").assertExists()
        compose.onNodeWithTag("navigationDisabled").assertIsNotEnabled()
    }
    @Test fun oneAndZeroResultsAreNotFilled() {
        compose.setContent {AuditViewport {RecommendationFlow(onExit={},repository=FakeRecommendationRepository(0))}}
        load(FakeScenario.ONE)
        capture("one")
        compose.onNodeWithTag("detailai-proposal-1").assertExists()
        compose.onNodeWithTag("detailai-proposal-2").assertDoesNotExist()
        compose.onNodeWithTag("editConditions").performScrollTo().performClick()
        compose.onNodeWithTag("scenarioPicker").performClick()
        compose.onNodeWithTag("scenarioZERO").performClick()
        compose.onNodeWithTag("generate").performClick()
        compose.waitUntil(5000){compose.onAllNodesWithTag("results").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("추천 가능한 코스가 없어요").assertExists()
        compose.onNodeWithTag("detailai-proposal-1").assertDoesNotExist()
        capture("zero")
    }
    @Test fun unknownCoursesShowFailureAndUnconfirmedMargin() {
        compose.setContent {AuditViewport {RecommendationFlow(onExit={},repository=FakeRecommendationRepository(0))}}
        load(FakeScenario.UNKNOWN)
        compose.onNodeWithTag("detailai-proposal-1").assertDoesNotExist()
        compose.onAllNodesWithText("선택할 수 없는 제안 · ETA 또는 장소 확인이 부족해요.").assertCountEquals(2)
        compose.onAllNodesWithText("집 도착 예상  미확인").assertCountEquals(2)
        capture("unknown")
    }
    @Test fun malformedJsonShowsErrorAndKeepsInput() {
        compose.setContent {AuditViewport {RecommendationFlow(onExit={},repository=FakeRecommendationRepository(0))}}
        compose.onNodeWithTag("scenarioPicker").performClick()
        compose.onNodeWithTag("scenarioBAD_JSON").performScrollTo().performClick()
        compose.onNodeWithTag("parked").performScrollTo().performClick()
        compose.onNodeWithTag("generate").performClick()
        compose.waitUntil(5000){compose.onAllNodesWithTag("recommendationError").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("recommendationError").performScrollTo().assertIsDisplayed()
        capture("error")
        compose.onNodeWithTag("results").assertDoesNotExist()
        compose.onNodeWithTag("generate").assertExists()
    }
    @Test fun loadingCanBeCancelled() {
        compose.setContent {AuditViewport {RecommendationFlow(onExit={},repository=FakeRecommendationRepository(60000))}}
        compose.onNodeWithTag("parked").performScrollTo().performClick()
        compose.onNodeWithTag("generate").performClick()
        compose.onNodeWithTag("loading").assertExists()
        capture("loading")
        org.junit.Assert.assertTrue(compose.onNodeWithTag("cancel").fetchSemanticsNode().boundsInRoot.height>=48f)
        compose.onNodeWithTag("cancel").performClick()
        compose.onNodeWithTag("recommendationError").assertExists()
        compose.onNodeWithTag("generate").assertExists()
    }
}

@Composable private fun AuditViewport(content:@Composable ()->Unit) {
    // Reference PNG: 1280x720 minus 200px vehicle cluster = 1080x720 app area.
    Box(Modifier.requiredSize(1080.dp,720.dp)) {content()}
}
