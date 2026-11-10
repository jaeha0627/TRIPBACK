package com.tripback.app.trip.progress

import com.tripback.app.trip.*
import com.tripback.app.trip.recommendation.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TripJourneyTests {
    class Clock(var now:Long=Instant.parse("2026-11-07T00:30:00Z").toEpochMilli(),var mono:Long=100000):JourneyClock {
        override fun wall()=now;override fun elapsed()=mono
        fun advance(seconds:Long) {now+=seconds*1000;mono+=seconds*1000}
    }
    private val request=RecommendationForm(parked=true,date="2026-11-07").request()
    private val plan=RecommendationJson.response(RecommendationFixtures.response(request,FakeScenario.ONE),request).alternatives.first()
    private fun journey(c:Clock=Clock(),r:RecommendationRequest=request)=TripJourney(plan,r,c)
    private fun arrive(j:TripJourney) {
        j.depart(true);j.manualAdvance(JourneyPhase.DEPARTURE,j.state.value.revision)
        j.manualAdvance(JourneyPhase.TRAVELING,j.state.value.revision)
    }
    private fun staying(j:TripJourney) {arrive(j);j.startStay()}
    @Test fun normalManualRoundTripHasOnlyDemoRecords() {
        val j=journey();arrive(j);assertEquals(JourneyPhase.ARRIVED,j.state.value.phase)
        j.startStay();j.finishStay(true)
        j.manualAdvance(JourneyPhase.DEPARTURE,j.state.value.revision);j.manualAdvance(JourneyPhase.TRAVELING,j.state.value.revision)
        j.startStay();j.finishStay(true);assertEquals(JourneyPhase.RETURN_PREPARATION,j.state.value.phase)
        j.manualAdvance(JourneyPhase.RETURN_PREPARATION,j.state.value.revision);j.manualAdvance(JourneyPhase.RETURNING,j.state.value.revision)
        assertEquals(JourneyPhase.COMPLETED,j.state.value.phase)
        assertTrue(j.state.value.events.all {it.source=="DEMO_SIMULATED"})
    }
    @Test fun departureNeedsApprovalAndCannotAutoTravel() {
        val j=journey();j.depart(false);assertEquals(JourneyPhase.READY,j.state.value.phase)
        j.depart(true);assertEquals(JourneyPhase.DEPARTURE,j.state.value.phase)
    }
    @Test fun duplicateAndLateEventsAreIgnored() {
        val j=journey();j.depart(true);val revision=j.state.value.revision
        j.manualAdvance(JourneyPhase.DEPARTURE,revision);val count=j.state.value.events.size
        j.manualAdvance(JourneyPhase.DEPARTURE,revision);assertEquals(count,j.state.value.events.size)
        j.manualAdvance(JourneyPhase.TRAVELING,revision);assertEquals(JourneyPhase.TRAVELING,j.state.value.phase)
    }
    @Test fun stayOverrunDoesNotDepartOrReplan() {
        val c=Clock();val j=journey(c);staying(j);c.advance(1801)
        assertEquals(-1L,j.remainingStay());assertEquals(JourneyPhase.STAYING,j.state.value.phase)
        assertTrue(j.state.value.changes.isEmpty())
    }
    @Test fun suspensionAndRecreationKeepOneAnchor() {
        val c=Clock();val j=journey(c);staying(j);c.advance(120)
        val restored=TripJourney(plan,request,c,j.snapshot());restored.startStay();c.advance(60)
        assertEquals(1620L,restored.remainingStay())
        assertEquals(j.state.value.stayStartWall,restored.state.value.stayStartWall)
    }
    @Test fun wallClockChangeOrRebootMakesTimingUnknown() {
        val c=Clock();val j=journey(c);staying(j);c.now+=60000
        assertNull(j.remainingStay());assertEquals(ValidationFeasibility.UNKNOWN,j.projection().arithmetic)
        j.finishStay(true);assertEquals(JourneyPhase.STAYING,j.state.value.phase)
    }
    @Test fun returnShortageUsesExactBufferAndNeverClaimsLiveFeasible() {
        val j=journey(r=request.copy(returnDeadline=request.departAt.plusMinutes(45)))
        assertEquals(ValidationFeasibility.INFEASIBLE,j.projection().arithmetic)
        assertEquals(ValidationFeasibility.UNKNOWN,j.projection().feasibility)
        assertEquals(ValidationFeasibility.FEASIBLE,journey().projection().arithmetic)
    }
    @Test fun missingEtaIsUnknownWithoutInventedReturnTime() {
        val p=plan.copy(validation=plan.validation.copy(legs=plan.validation.legs.map {it.copy(durationSeconds=null)}))
        val j=TripJourney(p,request,Clock());assertEquals(ValidationFeasibility.UNKNOWN,j.projection().arithmetic);assertNull(j.projection().returnAt)
    }
    @Test fun staleProviderEtaIsNotFreshTraffic() {
        val legs=plan.validation.legs.map {it.copy(data=ValidationSource("KAKAO_MOBILITY",ValidationDataMode.VERIFIED_CACHED,"VALIDATED_PROVIDER_DATA",Instant.EPOCH))}
        val j=TripJourney(plan.copy(validation=plan.validation.copy(legs=legs)),request,Clock())
        assertEquals(ValidationFeasibility.UNKNOWN,j.projection().feasibility)
        assertTrue(j.projection().reasons.any {it.contains("15분")})
    }
    @Test fun proposalDoesNotMutateAndRejectPreservesPlan() {
        val j=journey();staying(j);val before=j.state.value.stays
        val p=j.shortenProposal()!!;assertEquals(before,j.state.value.stays)
        j.decide(p,false);assertEquals(before,j.state.value.stays);assertFalse(j.state.value.changes.last().approved)
    }
    @Test fun approvalReevaluatesAndStaleApprovalCannotApplyTwice() {
        val j=journey();staying(j);val p=j.shortenProposal()!!
        j.decide(p,true);assertEquals(1200L,j.state.value.stays[0]);j.decide(p,true);assertEquals(1,j.state.value.changes.size)
    }
    @Test fun requiredStopCannotBeOmittedEvenInForgedProposal() {
        val j=journey(r=request.copy(mustVisitPlaceIds=listOf(plan.orderedVisits[1].id)));staying(j)
        assertNull(j.omitProposal());val p=j.shortenProposal()!!.copy(omitted=setOf(1))
        assertThrows(IllegalArgumentException::class.java){j.decide(p,true)};assertTrue(j.state.value.omitted.isEmpty())
    }
    @Test fun optionalOmissionRequiresApprovalAndServerRevalidation() {
        val j=journey();staying(j);val p=j.omitProposal()!!
        assertEquals(ValidationFeasibility.UNKNOWN,p.after.arithmetic);assertTrue(j.state.value.omitted.isEmpty())
        j.decide(p,true);assertEquals(setOf(1),j.state.value.omitted);j.finishStay(true)
        assertEquals(JourneyPhase.RETURN_PREPARATION,j.state.value.phase)
    }
    @Test fun uxUnknownAndDrivingRestrictionsBlockAllComplexActions() {
        assertFalse(DrivingSafety().canInteract)
        assertTrue(DrivingSafety(true,false,0).canInteract)
        assertFalse(DrivingSafety(true,true,0).canInteract)
        assertFalse(DrivingSafety(true,false,1).canInteract)
    }
    @Test fun nonFeasiblePlanAndCorruptRestorationRejected() {
        assertThrows(IllegalArgumentException::class.java){TripJourney(plan.copy(feasibility=ValidationFeasibility.UNKNOWN),request,Clock())}
        assertThrows(Exception::class.java){TripJourney(plan,request,Clock(),"broken")}
    }
    @Test fun demoAdapterCannotCallActualSdk() {
        val a=DemoNavigationAdapter();assertTrue(a.capability.value.message.contains("DEMO_SIMULATED"))
        assertNull(a.advance(JourneyPhase.STAYING));assertFalse(UnsupportedNavigationAdapter.capability.value.supported)
    }
}
