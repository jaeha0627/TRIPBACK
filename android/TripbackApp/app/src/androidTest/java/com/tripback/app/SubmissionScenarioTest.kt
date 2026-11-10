package com.tripback.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.tripback.app.trip.progress.*
import com.tripback.app.trip.recommendation.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** A single unbroken, zero-provider-cost scenario. Fast-forward is test clock only. */
class SubmissionScenarioTest {
    @get:Rule val compose=createComposeRule()
    private fun click(tag:String,scroll:Boolean=true) {
        val node=compose.onNodeWithTag(tag)
        if(scroll) node.performScrollTo()
        node.performClick();compose.waitForIdle()
    }
    private fun capture(name:String,dialog:Boolean=false) {
        val node=if(dialog) compose.onNode(isDialog()) else compose.onNodeWithTag("recommendationFlow")
        val file=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"submission-$name.png")
        file.outputStream().use { node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun completeRecommendationOverrunReplanAndHistoryWithRestoration() {
        val form=RecommendationForm(parked=true)
        class Clock:JourneyClock {
            var wall=form.request().departAt.toInstant().toEpochMilli()
            var elapsed=100000L
            override fun wall()=wall
            override fun elapsed()=elapsed
            fun advance(seconds:Long) {wall+=seconds*1000;elapsed+=seconds*1000}
        }
        val clock=Clock()
        val repo=FakeRecommendationRepository(0)
        val restoration=StateRestorationTester(compose)
        restoration.setContent {Box(Modifier.requiredSize(1080.dp,720.dp)) {RecommendationFlow({},repo,clock)}}
        compose.onNodeWithText("목표 지역").performScrollTo().performTextReplacement("천안 독립기념관")
        click("parked");capture("01-input");click("generate",false)
        compose.waitUntil(5000) {compose.onAllNodesWithTag("results").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("detailai-proposal-1").assertExists()
        compose.onNodeWithTag("detailai-proposal-2").assertExists();capture("02-two-plans")
        click("detailai-proposal-1");capture("03-detail");click("selectPlan",false)
        compose.onNodeWithTag("navigationDisabled").assertIsNotEnabled();capture("04-ready")
        click("beginJourneyDemo");click("demoDepart");click("demoTravel");capture("05-travel-demo")
        click("demoArrive");click("startStay");capture("06-stay")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("journeyPhase").assertTextEquals("머무는 시간")
        compose.runOnIdle {clock.advance(8*3600)}
        compose.mainClock.advanceTimeBy(1100)
        compose.waitUntil(5000) {compose.onAllNodes(hasTestTag("stayTimer") and hasText("체류시간 초과",substring=true)).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("returnWarning").assertExists()
        compose.onNodeWithTag("returnStatus").assertTextContains("UNKNOWN",substring=true);capture("07-overrun-shortage")
        click("shortenProposal");capture("08-proposal",true)
        compose.onNodeWithTag("rejectChange").performClick()
        compose.onNode(hasText("1. 시연 문화 공간",substring=true) and hasText("현재 30분",substring=true)).assertExists();capture("09-rejected")
        click("shortenProposal");compose.onNodeWithTag("approveChange").performClick()
        compose.onNodeWithText("현재 20분",substring=true).assertExists();capture("10-approved")
        click("finishStay");click("demoTravel");click("demoArrive");click("startStay");click("finishStay")
        compose.onNodeWithTag("journeyPhase").assertTextEquals("귀가 준비");capture("11-return-preparation")
        click("demoReturn");click("demoComplete");capture("12-summary")
        click("saveDemoHistory");compose.onNodeWithTag("historySaved").assertTextEquals("DEMO_SIMULATED 기록 저장됨");compose.onNodeWithTag("historySaved").performScrollTo();capture("13-history")
        val record=DemoTripHistory(InstrumentationRegistry.getInstrumentation().targetContext).records().first()
        assertEquals("DEMO_SIMULATED",record.getValue("source").jsonPrimitive.content)
        assertFalse(record.getValue("actualDrivingVerified").jsonPrimitive.boolean)
        val progress=record.getValue("demoProgress").jsonObject
        assertEquals(1200L,progress.getValue("stays").jsonArray.first().jsonPrimitive.long)
        assertEquals(listOf(false,true),progress.getValue("changes").jsonArray.map {it.jsonObject.getValue("approved").jsonPrimitive.boolean})
        assertEquals(2,progress.getValue("events").jsonArray.count {it.jsonObject.getValue("kind").jsonPrimitive.content=="STAY_FINISHED"})
    }
}
