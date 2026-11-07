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

}
