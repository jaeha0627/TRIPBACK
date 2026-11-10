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
    @Test fun smallWindowKeepsFooterAndScrolledFieldsAccessible() {
        compose.setContent {
            Box(Modifier.requiredSize(600.dp,480.dp)) {
                RecommendationFlow(onExit={},repository=FakeRecommendationRepository(0))
            }
        }
        compose.onNodeWithTag("generate").assertIsDisplayed()
        capture("small-window")
        compose.onNodeWithText("필수 방문 카카오 ID · 쉼표 구분, 최대 5개").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("parked").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("generate").assertIsDisplayed().performClick()
        compose.onNodeWithTag("recommendationError").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("generate").assertIsDisplayed()
        capture("small-window-validation")
    }
    @Test fun smallWindowResultsStackAndKeepBothDetailsAndInputs() {
        compose.setContent {
            Box(Modifier.requiredSize(600.dp,480.dp)) {
                RecommendationFlow(onExit={},repository=FakeRecommendationRepository(0))
            }
        }
        compose.onNodeWithTag("parked").performScrollTo().performClick()
        compose.onNodeWithTag("generate").performClick()
        compose.waitUntil(5000){compose.onAllNodesWithTag("results").fetchSemanticsNodes().isNotEmpty()}
        capture("small-results")
        compose.onNodeWithText("시연 코스 A").performScrollTo().assertIsDisplayed()
        val first = compose.onNodeWithText("시연 코스 A").fetchSemanticsNode().boundsInRoot.left
        compose.onNodeWithTag("detailai-proposal-1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("시연 코스 B").performScrollTo().assertIsDisplayed()
        val second = compose.onNodeWithText("시연 코스 B").fetchSemanticsNode().boundsInRoot.left
        org.junit.Assert.assertEquals(first,second,1f)
        compose.onNodeWithTag("detailai-proposal-2").performScrollTo().assertIsDisplayed()
        capture("small-results-bottom")
        compose.onNodeWithTag("detailai-proposal-2").performClick()
        compose.onNodeWithTag("selectPlan").assertExists()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("editConditions").performScrollTo().performClick()
        compose.onNodeWithText("목표 지역").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("천안 독립기념관").assertExists()
        compose.onNodeWithTag("parked").performScrollTo().assertIsOn()
    }
    @Test fun infeasibleResultsRemainRejectedAndHaveNoDetailAction() {
        compose.setContent {AuditViewport {RecommendationFlow(onExit={},repository=FakeRecommendationRepository(0))}}
        load(FakeScenario.INFEASIBLE)
        compose.onNodeWithText("추천 가능한 코스가 없어요").assertExists()
        compose.onAllNodesWithText("선택할 수 없는 제안 · 귀가 마감 또는 안전 여유를 지키기 어려워요.").assertCountEquals(2)
        compose.onNodeWithTag("detailai-proposal-1").assertDoesNotExist()
        compose.onNodeWithTag("detailai-proposal-2").assertDoesNotExist()
        capture("infeasible")
    }
    @Test fun fiveScreensAndSelectionRestoreWithoutNavigation() {
        val restoration=StateRestorationTester(compose)
        restoration.setContent {AuditViewport {RecommendationFlow(onExit={})}}
        compose.onNodeWithText("오늘은 어디로\n떠나볼까요?").assertIsDisplayed()
        capture("input")
        load(FakeScenario.TWO)
        compose.onNodeWithText("천안 독립기념관").assertExists()
        compose.onNodeWithText("18:00까지 귀가").assertExists()
        compose.onNodeWithText("안전 여유 30분").assertExists()
        compose.onAllNodesWithText("FEASIBLE · 현재 ETA 기준 가능").assertCountEquals(2)
        compose.onAllNodesWithText("집 도착 예상  11:00").assertCountEquals(2)
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
    @Test fun detailShowsSelectedCourseAndValidatedScheduleThenReturnsToResults() {
        compose.setContent {AuditViewport {RecommendationFlow(onExit={},repository=FakeRecommendationRepository(0))}}
        load(FakeScenario.TWO)
        compose.onNodeWithTag("detailai-proposal-2").performScrollTo().performClick()
        compose.onNodeWithText("시연 코스 B 일정").assertIsDisplayed()
        compose.onNodeWithText("집 도착 예상  11:00").assertExists()
        compose.onNodeWithText("FEASIBLE · 안전 여유 30분").assertExists()
        compose.onNodeWithTag("detailSchedule").assertIsDisplayed()
        compose.onNodeWithText("09:30").assertExists()
        compose.onNodeWithText("귀가 마감 18:00").assertExists()
        compose.onNodeWithText("방문별 도착 시각은 제공되지 않았습니다.").assertExists()
        compose.onNodeWithText("AI 추천 이유 · 미검증").assertExists()
        compose.onNodeWithText("배터리 68%",substring=true).assertDoesNotExist()
        compose.onNodeWithText("휴게소 넣기").assertDoesNotExist()
        val back=compose.onNodeWithTag("detailBack").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val select=compose.onNodeWithTag("selectPlan").assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(back.height>=48f && select.height>=48f)
        capture("detail-wide")
        compose.onNodeWithTag("detailBack").performClick()
        compose.onNodeWithTag("results").assertExists()
        compose.onNodeWithTag("detailai-proposal-2").performScrollTo().performClick()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("results").assertExists()
    }
    @Test fun smallDetailScrollsTimelineAndKeepsSelectionAboveContent() {
        compose.setContent {
            Box(Modifier.requiredSize(600.dp,480.dp)) {
                RecommendationFlow(onExit={},repository=FakeRecommendationRepository(0))
            }
        }
        compose.onNodeWithTag("parked").performScrollTo().performClick()
        compose.onNodeWithTag("generate").performClick()
        compose.waitUntil(5000){compose.onAllNodesWithTag("results").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("detailai-proposal-1").performScrollTo().performClick()
        compose.onNodeWithTag("selectPlan").assertIsDisplayed()
        capture("detail-small")
        compose.onNodeWithText("귀가 마감 18:00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("selectPlan").assertIsDisplayed()
        capture("detail-small-timeline")
        compose.onNodeWithTag("selectPlan").performClick()
        compose.onNodeWithTag("ready").assertExists()
        compose.onNodeWithTag("navigationDisabled").performScrollTo().assertIsNotEnabled()
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
