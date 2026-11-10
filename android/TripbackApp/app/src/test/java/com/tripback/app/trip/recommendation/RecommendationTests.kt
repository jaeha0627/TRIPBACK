package com.tripback.app.trip.recommendation

import com.tripback.app.trip.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RecommendationTests {
    private fun request()=RecommendationForm(parked=true,date="2026-11-07").request()
    private fun response(s:FakeScenario)=RecommendationJson.response(RecommendationFixtures.response(request(),s),request())
    @Test fun requestRoundTripsFlatContractAndAllPreferences() {
        val r=request().copy(companionType="FAMILY",childAgeGroup="TEEN",budgetPerPersonKrw=50000,mealPreferences=listOf("KOREAN"),
            walkingPreference="LOW",parkingPreferences=listOf("FREE"),amenities=listOf("ACCESSIBLE"),preferIndoorWhenRaining=true,
            mustVisitPlaceIds=listOf(ValidationPlaceId("KAKAO_LOCAL","001")))
        val json=RecommendationJson.request(r);val obj=Json.parseToJsonElement(json).jsonObject
        assertTrue(obj.containsKey("origin")&&obj.containsKey("targetCenter"))
        assertFalse(obj.containsKey("companions"));assertFalse(obj.containsKey("dataMode"))
        assertEquals(r,RecommendationJson.readRequest(json))
    }
    @Test fun invalidFormParkingCoordinatesAndTimesAreRejected() {
        for(f in listOf(RecommendationForm(),RecommendationForm(parked=true,originLat="NaN"),
            RecommendationForm(parked=true,deadline="07:00"),RecommendationForm(parked=true,depart="25:10"),
            RecommendationForm(parked=true,oneWay="181"),RecommendationForm(parked=true,budget="-2"))) assertThrows(Exception::class.java){f.request()}
    }
    @Test fun recommendationDoesNotInheritDemoTwoHourRule() {
        val r=request();assertNotNull(r.copy(returnDeadline=r.departAt.plusMinutes(90)))
    }
    @Test fun familyDuplicatesAndUnsupportedPreferencesAreRejected() {
        assertThrows(IllegalArgumentException::class.java){request().copy(companionType="FAMILY")}
        assertThrows(IllegalArgumentException::class.java){request().copy(interests=listOf("FOOD","FOOD"))}
        assertThrows(IllegalArgumentException::class.java){request().copy(walkingPreference="FLY")}
        assertThrows(IllegalArgumentException::class.java){request().copy(mustVisitPlaceIds=List(6){ValidationPlaceId("KAKAO_LOCAL",it.toString())})}
    }
    @Test fun twoAlternativesRetainOrderEvidenceAndReturnLeg() {
        val r=response(FakeScenario.TWO);assertEquals("COMPLETED",r.recommendationStatus);assertEquals(2,r.alternatives.size)
        for(a in r.alternatives) {
            assertEquals(3,a.validation.legs.size);assertEquals(ValidationFeasibility.FEASIBLE,a.feasibility)
            assertEquals(setOf(ValidationDataMode.DEMO_SIMULATED),a.dataModes);assertTrue(a.notices.contains("AI_REASON_UNVERIFIED"))
        }
        assertEquals(r.alternatives.first().orderedVisits.map{it.id}.reversed(),r.alternatives.last().orderedVisits.map{it.id})
    }
    @Test fun oneAlternativeIsNotFilledToTwo() {
        val r=response(FakeScenario.ONE);assertEquals("PARTIAL",r.recommendationStatus);assertEquals(1,r.alternatives.size)
    }
    @Test fun zeroAlternativesHaveNoReplacement() {
        val r=response(FakeScenario.ZERO);assertTrue(r.alternatives.isEmpty());assertEquals("NO_FEASIBLE_ALTERNATIVES",r.recommendationStatus)
    }
    @Test fun infeasibleProposalsRemainRejected() {
        val r=response(FakeScenario.INFEASIBLE);assertTrue(r.alternatives.isEmpty())
        assertTrue(r.rejectedAlternatives.all{it.proposal!!.feasibility==ValidationFeasibility.INFEASIBLE})
    }
    @Test fun unknownPreservesNullReturnEtaAndMargin() {
        val r=response(FakeScenario.UNKNOWN);assertTrue(r.alternatives.isEmpty())
        r.rejectedAlternatives.forEach {assertNull(it.proposal!!.validation.predictedReturnAt);assertNull(it.proposal.validation.remainingMarginMin);assertNull(it.proposal.validation.legs.last().durationSeconds)}
        assertEquals("귀가 가능 여부 미확인",feasibilityLabel(ValidationFeasibility.UNKNOWN))
    }
    @Test fun unavailableModelErrorAndTimeoutNeverBecomeSuccess() {
        for(s in listOf(FakeScenario.UNCONFIGURED,FakeScenario.MODEL_ERROR,FakeScenario.TIMEOUT)) {
            val r=response(s);assertTrue(r.alternatives.isEmpty());assertEquals(0,r.calls.modelGenerations)
        }
    }
    @Test fun malformedJsonAndFeasibleWithNullReturnAreRejected() {
        assertThrows(RecommendationFormatException::class.java){response(FakeScenario.BAD_JSON)}
        val raw=RecommendationFixtures.response(request(),FakeScenario.ONE)
        val root=Json.parseToJsonElement(raw).jsonObject.toMutableMap()
        val a=root.getValue("alternatives").jsonArray.first().jsonObject.toMutableMap()
        val v=a.getValue("validation").jsonObject.toMutableMap();v["predictedReturnAt"]=JsonNull
        a["validation"]=JsonObject(v);root["alternatives"]=JsonArray(listOf(JsonObject(a)))
        assertThrows(RecommendationFormatException::class.java){RecommendationJson.response(JsonObject(root).toString(),request())}
    }
    @Test fun allFakePlacesAndEtasStayDemo() {
        FakeScenario.entries.filter{it!=FakeScenario.BAD_JSON}.forEach {s->assertTrue(response(s).candidates.all{it.data.dataMode==ValidationDataMode.DEMO_SIMULATED})}
    }
    @Test fun threeSourceLabelsAreParsedAsMetadataOnly() {
        ValidationDataMode.entries.forEach {mode->
            val source=RecommendationJson.source(buildJsonObject {
                put("provider","KAKAO_LOCAL");put("dataMode",mode.name)
                put("verification",if(mode==ValidationDataMode.DEMO_SIMULATED)"UNVERIFIED" else "VALIDATED_PROVIDER_DATA")
                put("retrievedAt","2026-11-07T00:00:00Z")
            });assertEquals(mode,source.dataMode)
        }
    }
    @Test fun missingRequiredIdIsNotDropped() {
        val r=request().copy(mustVisitPlaceIds=listOf(ValidationPlaceId("KAKAO_LOCAL","missing")))
        val result=RecommendationJson.response(RecommendationFixtures.response(r,FakeScenario.TWO),r)
        assertTrue(result.alternatives.isEmpty());assertEquals("REQUIRED_PLACE_UNAVAILABLE",result.rejectedAlternatives.first().reason)
    }
    @Test fun providerMismatchAndMissingCandidatesAreRejected() {
        val raw=RecommendationFixtures.response(request(),FakeScenario.ONE)
        assertThrows(RecommendationFormatException::class.java){RecommendationJson.response(raw.replace("KAKAO_LOCAL","OTHER"),request())}
        val root=Json.parseToJsonElement(raw).jsonObject.toMutableMap();root["candidates"]=JsonArray(emptyList())
        assertThrows(RecommendationFormatException::class.java){RecommendationJson.response(JsonObject(root).toString(),request())}
    }
    @Test fun firstAndReturnLegTimeLimitStillApplies() {
        val r=request().copy(maximumOneWayMinutes=5)
        val result=RecommendationJson.response(RecommendationFixtures.response(r,FakeScenario.TWO),r)
        assertTrue(result.alternatives.isEmpty());assertTrue(result.rejectedAlternatives.all{it.reason=="MAXIMUM_ONE_WAY_EXCEEDED"})
    }
    @Test fun storedJsonFixturesMatchWireContract() {
        val r=RecommendationJson.readRequest(javaClass.getResource("/recommendation/request.json")!!.readText())
        for((name,count) in listOf("two" to 2,"one" to 1,"zero" to 0,"unknown" to 0,"unconfigured" to 0))
            assertEquals(count,RecommendationJson.response(javaClass.getResource("/recommendation/$name.json")!!.readText(),r).alternatives.size)
    }
    @Test fun duplicateSubmitAndLateCancelledResponseAreIgnored()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var calls=0
        val session=RecommendationSession(this,RecommendationRepository {r,_->calls++;entered.complete(Unit)
            withContext(NonCancellable){release.await()};RecommendationFixtures.response(r,FakeScenario.TWO)})
        session.submit(request(),FakeScenario.TWO);entered.await();session.submit(request(),FakeScenario.ONE)
        assertEquals(1,calls);session.cancel();release.complete(Unit);yield()
        assertEquals(RecommendationPage.INPUT,session.state.value.page);assertNull(session.state.value.response)
    }
    @Test fun readyAndSelectionRestoreWithoutReplayingRequest()=runBlocking {
        var calls=0;val repo=RecommendationRepository {r,s->calls++;RecommendationFixtures.response(r,s)}
        val session=RecommendationSession(this,repo);session.submit(request(),FakeScenario.ONE);yield()
        session.detail("ai-proposal-1");assertEquals(RecommendationPage.DETAIL,session.state.value.page)
        session.select();val restored=RecommendationSession(this,repo);restored.restore(session.snapshot())
        assertEquals(RecommendationPage.READY,restored.state.value.page);assertEquals("ai-proposal-1",restored.state.value.selectedPlanId);assertEquals(1,calls)
    }
    @Test fun loadingRestorationNeverRestartsWork()=runBlocking {
        val session=RecommendationSession(this,RecommendationRepository{_,_->error("must not call")})
        session.restore(listOf("LOADING","","",""));assertEquals(RecommendationPage.INPUT,session.state.value.page);assertNotNull(session.state.value.error)
    }
    @Test fun parseErrorPreservesLastResultWithNoRetry()=runBlocking {
        var calls=0;val session=RecommendationSession(this,RecommendationRepository{r,s->calls++;RecommendationFixtures.response(r,s)})
        session.submit(request(),FakeScenario.ONE);yield();session.input();session.submit(request(),FakeScenario.BAD_JSON);yield()
        assertEquals(2,calls);assertNotNull(session.state.value.error);assertEquals(1,session.state.value.response!!.alternatives.size)
        session.results();assertEquals(RecommendationPage.RESULTS,session.state.value.page)
    }
    @Test fun invalidSavedStateReturnsToInput()=runBlocking {
        val session=RecommendationSession(this,FakeRecommendationRepository(0));session.restore(listOf("READY","broken","broken","x"))
        assertEquals(RecommendationPage.INPUT,session.state.value.page);assertNotNull(session.state.value.error)
    }
}
