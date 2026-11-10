package com.tripback.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import com.tripback.app.trip.progress.*
import com.tripback.app.trip.recommendation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TripJourneyFlowTest {
    @get:Rule val compose=createComposeRule()
    private val request=RecommendationForm(parked=true,date="2026-11-07").request()
    private fun journey()=TripJourney(RecommendationJson.response(RecommendationFixtures.response(request,FakeScenario.ONE),request).alternatives.first(),request,AndroidJourneyClock)
    private fun click(tag:String) {compose.onNodeWithTag(tag).performScrollTo().performClick();compose.waitForIdle()}
    private fun capture(name:String,tag:String="journeyScreen") {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val out=File(context.cacheDir,"journey-$name.png")
        val node=if(name=="change-proposal") compose.onNode(isDialog()) else compose.onNodeWithTag(tag)
        out.outputStream().use {node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun manualJourneyApprovalTimerAndSummaryScreens() {
        val j=journey()
        compose.setContent {Box(Modifier.requiredSize(1080.dp,720.dp)){JourneyScreen(j,{})}}
        capture("ready");click("demoDepart");capture("departure");click("demoTravel");capture("traveling")
        click("demoArrive");capture("arrived");click("startStay");capture("staying")
        click("shortenProposal");capture("change-proposal");compose.onNodeWithTag("rejectChange").performClick();assertEquals(1800L,j.state.value.stays[0])
        click("shortenProposal");compose.onNodeWithTag("approveChange").performClick();assertEquals(1200L,j.state.value.stays[0])
        click("finishStay");click("demoTravel");click("demoArrive");click("startStay");click("finishStay")
        capture("return-preparation");click("demoReturn");capture("returning");click("demoComplete");capture("completed")
        click("saveDemoHistory");compose.onNodeWithTag("historySaved").assertExists()
        assertTrue(DemoTripHistory(InstrumentationRegistry.getInstrumentation().targetContext).records().isNotEmpty())
    }
    @Test fun drivingRestrictionChangeHidesEditorsAndRestoresState() {
        var safety by mutableStateOf(DrivingSafety(true,false,0))
        compose.setContent {DrivingSafetyGate(safety) {
            var edits by androidx.compose.runtime.saveable.rememberSaveable {mutableIntStateOf(0)}
            androidx.compose.material3.Button(onClick={edits++},modifier=Modifier.testTag("editor")) {Text("편집 $edits")}
        }}
        compose.onNodeWithTag("editor").performClick()
        compose.onNodeWithTag("editor").assertExists()
        compose.runOnIdle {safety=DrivingSafety(true,true,511)}
        compose.onNodeWithTag("drivingRestricted").assertExists();compose.onNodeWithTag("editor").assertDoesNotExist();capture("ux-restricted","drivingRestricted")
        compose.runOnIdle {safety=DrivingSafety()};compose.onNodeWithTag("editor").assertDoesNotExist()
        compose.runOnIdle {safety=DrivingSafety(true,false,0)};compose.onNodeWithTag("editor").assertExists();compose.onNodeWithText("편집 1").assertExists()
    }
    @Test fun realCarUxInitialQueryAndListenerRegistration() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val monitor=CarDrivingSafetyMonitor(instrumentation.targetContext)
        try {
            instrumentation.runOnMainSync {monitor.start()}
            compose.waitUntil(10000) {monitor.state.value.known}
            android.util.Log.i("TripbackCarUx","initial: ${monitor.state.value}")
            assertTrue(monitor.state.value.known)
        } finally {instrumentation.runOnMainSync {monitor.close()}}
        assertFalse(monitor.state.value.canInteract)
    }
    @Test fun selectedRecommendationJourneyRestoresWhileStaying() {
        val restoration=StateRestorationTester(compose)
        restoration.setContent {Box(Modifier.requiredSize(1080.dp,720.dp)){RecommendationFlow(onExit={})}}
        click("parked");compose.onNodeWithTag("generate").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithTag("results").fetchSemanticsNodes().isNotEmpty()}
        click("detailai-proposal-1");compose.onNodeWithTag("selectPlan").performClick();click("beginJourneyDemo")
        click("demoDepart");click("demoTravel");click("demoArrive");click("startStay")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("journeyPhase").assertTextEquals("머무는 시간")
        compose.onNodeWithTag("stayTimer").assertExists();capture("stay-restored")
    }
    @Test fun overrunShortageAndUnknownAreVisible() {
        class TestClock:JourneyClock {
            var now=request.departAt.toInstant().toEpochMilli()
            var mono=100000L
            override fun wall()=now
            override fun elapsed()=mono
        }
        val clock=TestClock()
        val plan=RecommendationJson.response(RecommendationFixtures.response(request,FakeScenario.ONE),request).alternatives.first()
        val j=TripJourney(plan,request.copy(returnDeadline=request.departAt.plusMinutes(45)),clock)
        j.depart(true);j.manualAdvance(JourneyPhase.DEPARTURE,j.state.value.revision)
        j.manualAdvance(JourneyPhase.TRAVELING,j.state.value.revision);j.startStay()
        clock.now+=1801000;clock.mono+=1801000
        compose.setContent {Box(Modifier.requiredSize(1080.dp,720.dp)){JourneyScreen(j,{})}}
        compose.onNodeWithTag("returnWarning").assertExists()
        compose.onNodeWithTag("stayTimer").assertTextContains("체류시간 초과",substring=true)
        capture("stay-overrun-return-shortage")
        compose.onNodeWithTag("returnStatus").assertTextContains("UNKNOWN",substring=true)
    }

    private class StayClock(var now:Long,var mono:Long=100000L):JourneyClock {
        override fun wall()=now
        override fun elapsed()=mono
        fun advance(seconds:Long) {now+=seconds*1000;mono+=seconds*1000}
    }
    private fun staying(clock:JourneyClock):TripJourney {
        val plan=RecommendationJson.response(RecommendationFixtures.response(request,FakeScenario.ONE),request).alternatives.first()
        return TripJourney(plan,request,clock).also { j ->
            j.depart(true);j.manualAdvance(JourneyPhase.DEPARTURE,j.state.value.revision)
            j.manualAdvance(JourneyPhase.TRAVELING,j.state.value.revision);j.startStay()
        }
    }
    @Test fun stayingLayoutTimerApprovalAndDebugEntryRemainSafe() {
        val clock=StayClock(request.departAt.toInstant().toEpochMilli())
        val j=staying(clock)
        val raw=RecommendationFixtures.response(request,FakeScenario.ONE)
        clock.advance(90)
        compose.setContent {Box(Modifier.requiredSize(1080.dp,720.dp)) {
            JourneyScreen(j,{}) {
                com.tripback.app.trip.replan.JourneyReplanDebug(j,request,raw,j.plan.planId,useHttp=false)
            }
        }}
        compose.onNodeWithTag("stayCountdown").assertTextEquals("00:28:30")
        compose.onNodeWithTag("stayElapsed").assertTextEquals("수동 시연 경과 00:01:30")
        compose.onNodeWithText("현재 방문지 · 머무는 중").assertIsDisplayed()
        compose.onNodeWithTag("returnStatus").assertTextContains("UNKNOWN",substring=true)
        capture("staying-wide")
        compose.runOnIdle {clock.advance(60)}
        compose.waitUntil(5000) {compose.onAllNodesWithText("00:27:30").fetchSemanticsNodes().isNotEmpty()}
        click("shortenProposal");assertEquals(1800L,j.state.value.stays[0])
        compose.onNodeWithTag("rejectChange").performClick();assertEquals(1800L,j.state.value.stays[0])
        click("shortenProposal");compose.onNodeWithTag("approveChange").performClick()
        assertEquals(1200L,j.state.value.stays[0]);assertEquals(30L,j.plan.orderedVisits[0].stayMinutes)
        click("omitProposal");assertTrue(j.state.value.omitted.isEmpty())
        compose.onNodeWithTag("rejectChange").performClick();assertTrue(j.state.value.omitted.isEmpty())
        click("openReplanPreview");compose.onNodeWithTag("replanDebugPanel").assertExists()
        compose.onNodeWithTag("previewOnly").assertTextContains("FAKE",substring=true)
        compose.onNodeWithTag("closeReplanPreview").performClick()
        compose.onNodeWithTag("finishStay").performScrollTo().assertIsDisplayed()
        capture("staying-controls")
        click("finishStay");assertEquals(JourneyPhase.DEPARTURE,j.state.value.phase)
    }
    @Test fun smallStayingScrollsScheduleAndClockJumpDisablesFinish() {
        val clock=StayClock(request.departAt.toInstant().toEpochMilli())
        val j=staying(clock)
        compose.setContent {Box(Modifier.requiredSize(600.dp,480.dp)){JourneyScreen(j,{})}}
        compose.onNodeWithTag("stayCountdown").assertExists()
        capture("staying-small")
        compose.onNodeWithTag("finishStay").performScrollTo().assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("finishStay").fetchSemanticsNode().boundsInRoot.height>=48f)
        compose.onNodeWithTag("stayVisit1").performScrollTo().assertIsDisplayed()
        capture("staying-small-schedule")
        compose.runOnIdle {clock.now+=60000}
        compose.waitUntil(5000) {compose.onAllNodesWithText("체류 시계 변경 감지 · 확인 필요").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("finishStay").performScrollTo().assertIsNotEnabled()
        assertEquals(JourneyPhase.STAYING,j.state.value.phase)
        compose.onNodeWithTag("stayCountdown").performScrollTo().assertTextEquals("UNKNOWN")
        capture("staying-clock-unknown")
    }

}
