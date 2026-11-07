package com.tripback.backend.trip.replan;

import com.tripback.backend.trip.feasibility.*;
import com.tripback.backend.trip.integration.*;
import com.tripback.backend.trip.provider.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.tripback.backend.trip.feasibility.TripFeasibilityResult.Feasibility.*;
import static com.tripback.backend.trip.replan.StayReplanResult.*;
import static com.tripback.backend.trip.replan.StayReplanResult.Reason.*;

class StayReplanServiceTests {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-07T10:00:00+09:00");
    private static final Wgs84Point HOME = new Wgs84Point(37, 127);
    private static final List<ProviderPlaceId> IDS = List.of(id("one"), id("two"), id("three"));
    private static ProviderPlaceId id(String value) { return new ProviderPlaceId("KAKAO_LOCAL", "fake-" + value); }
    private final StayReplanService service = new StayReplanService();
    private TravelLeg leg(int index, Long minutes, Instant at) {
        return new TravelLeg(index, minutes, minutes == null ? null : new TravelLeg.EtaEvidence(
                "FAKE_VALIDATED_FIXTURE", TravelLeg.DataMode.VERIFIED_CACHED, TravelLeg.Verification.VERIFIED, at));
    }
    private ValidatedReplanPlan plan(OffsetDateTime deadline, long buffer) {
        return new ValidatedReplanPlan(HOME, deadline, buffer, IDS, Set.of(IDS.get(2)), List.of(30L, 20L, 10L),
                List.of(leg(0, 99L, NOW.toInstant()), leg(1, 5L, NOW.toInstant()),
                        leg(2, 7L, NOW.toInstant()), leg(3, 8L, NOW.toInstant())));
    }
    private ValidatedReplanPlan plan() { return plan(NOW.plusHours(3), 30); }
    private StayReplanResult preview(long total, long elapsed) {
        return service.preview(new StayReplanInput(plan(), 0, NOW, elapsed, total));
    }
    @Test void extensionUsesTotalStayNotAdditionalTime() {
        var r = preview(50, 600);
        assertEquals(20L, r.before().currentRemainingStayMinutes());
        assertEquals(40L, r.after().currentRemainingStayMinutes());
        assertEquals(NOW.plusMinutes(70), r.before().predictedReturnAt());
        assertEquals(NOW.plusMinutes(90), r.after().predictedReturnAt());
        assertEquals(FEASIBLE, r.after().feasibility());
    }
    @Test void shorteningSubtractsElapsedExactlyOnce() {
        var r = preview(20, 600);
        assertEquals(NOW.plusMinutes(60), r.after().predictedReturnAt());
        assertEquals(10L, r.after().currentRemainingStayMinutes());
    }
    @Test void totalBelowElapsedIsExplicitAndCannotBeValidated() {
        var r = preview(9, 600);
        assertEquals(ChangeStatus.TOTAL_STAY_BELOW_ELAPSED, r.changeStatus());
        assertEquals(UNKNOWN, r.after().feasibility());
        assertTrue(r.after().reasons().contains(TOTAL_STAY_BELOW_ELAPSED));
    }
    @Test void existingOverrunClampsRemainingAndDoesNotAddPastStay() {
        var r = preview(40, 1801);
        assertEquals(0L, r.before().currentRemainingStayMinutes());
        assertEquals(10L, r.after().currentRemainingStayMinutes());
        assertEquals(NOW.plusMinutes(50), r.before().predictedReturnAt());
    }
    @Test void secondBoundariesRoundUp() {
        assertEquals(1L, preview(30, 1799).after().currentRemainingStayMinutes());
        assertEquals(0L, preview(30, 1800).after().currentRemainingStayMinutes());
        assertEquals(30L, preview(30, 1).after().currentRemainingStayMinutes());
        assertEquals(29L, preview(30, 60).after().currentRemainingStayMinutes());
    }
    @Test void exactElapsedIsAllowed() { assertEquals(ChangeStatus.PREVIEW_ONLY, preview(10, 600).changeStatus()); }
    private Projection deadline(long minutes, long buffer) {
        return service.preview(new StayReplanInput(plan(NOW.plusMinutes(minutes), buffer), 0, NOW, 600, 30)).after();
    }
    @Test void exactDeadlineWithZeroBufferIsFeasible() {
        var r = deadline(70, 0); assertEquals(FEASIBLE, r.feasibility()); assertTrue(r.returnDeadlineMet());
        assertEquals(Duration.ZERO, r.remainingMargin());
    }
    @Test void exactSafetyBufferPasses() { assertEquals(FEASIBLE, deadline(100, 30).feasibility()); }
    @Test void deadlineMetButBufferShortfallIsInfeasible() {
        var r = deadline(99, 30); assertEquals(INFEASIBLE, r.feasibility()); assertTrue(r.returnDeadlineMet());
        assertFalse(r.safetyBufferSatisfied()); assertTrue(r.reasons().contains(SAFETY_BUFFER_SHORTFALL));
    }
    @Test void deadlineExceededIsInfeasible() { assertEquals(INFEASIBLE, deadline(69, 0).feasibility()); }
    @Test void alreadyPastDeadlineIsAResultNotAnInputError() {
        var r = deadline(-1, 30); assertEquals(INFEASIBLE, r.feasibility());
        assertEquals(-71L, r.remainingMarginMinutes()); assertTrue(r.reasons().contains(DEADLINE_PASSED));
    }
    private ValidatedReplanPlan withLegs(List<TravelLeg> legs) {
        var p = plan(); return new ValidatedReplanPlan(p.home, p.deadline, p.bufferMinutes, p.visits, p.required, p.stays, legs);
    }
    @Test void missingReturnEtaIsUnknown() {
        var p = plan(); var legs = new ArrayList<>(p.legs); legs.set(3, leg(3, null, NOW.toInstant()));
        var r = service.preview(new StayReplanInput(withLegs(legs), 0, NOW, 600, 30)).after();
        assertEquals(UNKNOWN, r.feasibility()); assertNull(r.predictedReturnAt()); assertTrue(r.reasons().contains(MISSING_ETA));
    }
    @Test void omittedIntermediateLegIsUnknownNotZero() {
        var legs = new ArrayList<>(plan().legs); legs.remove(2);
        assertEquals(UNKNOWN, service.preview(new StayReplanInput(withLegs(legs), 0, NOW, 600, 30)).after().feasibility());
    }
    @Test void staleEtaRetainsOriginalSourceTimeAndArithmeticOnly() {
        var at = NOW.minusSeconds(901).toInstant(); var legs = new ArrayList<>(plan().legs); legs.set(3, leg(3, 8L, at));
        var r = service.preview(new StayReplanInput(withLegs(legs), 0, NOW, 600, 30)).after();
        assertEquals(UNKNOWN, r.feasibility()); assertEquals(FEASIBLE, r.arithmeticFeasibility());
        assertTrue(r.reasons().contains(STALE_ETA)); assertEquals(at, r.remainingLegs().getLast().evidence().recordedAt());
        assertEquals("FAKE_VALIDATED_FIXTURE", r.remainingLegs().getLast().evidence().source());
    }
    @Test void futureEtaTimestampIsUnknown() {
        var legs = new ArrayList<>(plan().legs); legs.set(3, leg(3, 8L, NOW.plusSeconds(1).toInstant()));
        assertTrue(service.preview(new StayReplanInput(withLegs(legs), 0, NOW, 600, 30)).after().reasons().contains(FUTURE_ETA_TIMESTAMP));
    }
    @Test void ageBoundaryAndLiveEvidenceAreReusedAsCached() {
        var legs = new ArrayList<>(plan().legs);
        legs.set(3, new TravelLeg(3, 8L, new TravelLeg.EtaEvidence("FAKE_LIVE_LABEL", TravelLeg.DataMode.VERIFIED_LIVE,
                TravelLeg.Verification.VERIFIED, NOW.minusSeconds(900).toInstant())));
        var r = service.preview(new StayReplanInput(withLegs(legs), 0, NOW, 600, 30)).after();
        assertEquals(FEASIBLE, r.feasibility());
        assertEquals(TravelLeg.DataMode.VERIFIED_CACHED, r.remainingLegs().getLast().evidence().dataMode());
    }
    @Test void unverifiedDemoEtaNeverBecomesVerified() {
        var legs = new ArrayList<>(plan().legs);
        legs.set(3, new TravelLeg(3, 8L, new TravelLeg.EtaEvidence("FAKE_DEMO", TravelLeg.DataMode.DEMO_SIMULATED,
                TravelLeg.Verification.UNVERIFIED, NOW.toInstant())));
        var r = service.preview(new StayReplanInput(withLegs(legs), 0, NOW, 600, 30)).after();
        assertEquals(UNKNOWN, r.feasibility()); assertTrue(r.reasons().contains(UNTRUSTED_ETA));
        assertEquals(TravelLeg.DataMode.DEMO_SIMULATED, r.remainingLegs().getLast().evidence().dataMode());
    }
    @Test void currentIndexInMiddlePreservesFutureStayAndFinalHomeLegOnce() {
        var r = service.preview(new StayReplanInput(plan(), 1, NOW, 600, 20)).after();
        assertEquals(NOW.plusMinutes(35), r.predictedReturnAt());
        assertEquals(List.of(2, 3), r.remainingLegs().stream().map(TravelLeg::index).toList());
    }
    @Test void lastVisitStillIncludesReturnHome() {
        var r = service.preview(new StayReplanInput(plan(), 2, NOW, 600, 10)).after();
        assertEquals(NOW.plusMinutes(8), r.predictedReturnAt()); assertEquals(1, r.remainingLegs().size());
    }
    @Test void previewLeavesOriginalPlanAndRequiredStopsUnchanged() {
        var p = plan(); var stays = p.stayMinutes(); var legs = p.originalLegs();
        service.preview(new StayReplanInput(p, 0, NOW, 600, 50));
        assertEquals(List.of(30L, 20L, 10L), stays); assertSame(stays, p.stayMinutes()); assertSame(legs, p.originalLegs());
        assertEquals(HOME, p.home()); assertEquals(IDS, p.visits()); assertEquals(Set.of(IDS.get(2)), p.requiredVisits());
        assertThrows(UnsupportedOperationException.class, () -> stays.set(0, 99L));
    }
    @Test void invalidAndOverflowInputsAreExplicit() {
        assertThrows(IllegalArgumentException.class, () -> new StayReplanInput(plan(), -1, NOW, 0, 30));
        assertThrows(IllegalArgumentException.class, () -> new StayReplanInput(plan(), 0, NOW, -1, 30));
        assertThrows(IllegalArgumentException.class, () -> new StayReplanInput(plan(), 0, NOW, 0, -1));
        assertEquals(ChangeStatus.CALCULATION_OUT_OF_RANGE, preview(Long.MAX_VALUE, 0).changeStatus());
    }
    @Test void serverFactoryUsesFakeValidationWithoutNetworkAndPreviewMakesNoFurtherCalls() {
        AtomicInteger searchCalls = new AtomicInteger(); AtomicInteger routeCalls = new AtomicInteger();
        var data = new ProviderData("KAKAO_LOCAL", ProviderData.DataMode.VERIFIED_CACHED,
                ProviderData.Verification.VALIDATED_PROVIDER_DATA, NOW.toInstant());
        var candidate = new PlaceCandidate(IDS.getFirst(), "Fake fixture", "", new Wgs84Point(37.1, 127.1), data);
        var validator = new TripValidationService(q -> {
            searchCalls.incrementAndGet(); return new PlaceSearchResult("KAKAO_LOCAL", PlaceSearchResult.Status.FOUND,
                    List.of(candidate), null, NOW.toInstant());
        }, q -> {
            routeCalls.incrementAndGet(); return new RoadRouteResult("KAKAO_MOBILITY", q, RoadRouteResult.Status.FOUND, 600L,
                    new ProviderData("KAKAO_MOBILITY", ProviderData.DataMode.VERIFIED_CACHED,
                            ProviderData.Verification.VALIDATED_PROVIDER_DATA, NOW.toInstant()), null, NOW.toInstant());
        });
        var validation = validator.validate(new TripValidationInput(HOME, NOW.minusHours(1), NOW.plusHours(3), 30,
                List.of(new TripValidationInput.Visit("fake fixture", IDS.getFirst(), 30))));
        var demoCandidate = new PlaceCandidate(candidate.id(), candidate.name(), candidate.address(), candidate.coordinates(),
                new ProviderData("KAKAO_LOCAL", ProviderData.DataMode.DEMO_SIMULATED,
                        ProviderData.Verification.UNVERIFIED, NOW.toInstant()));
        var originalPlace = validation.places().getFirst();
        var demoResult = new TripValidationResult(validation.input(), List.of(new TripValidationResult.PlaceCheck(
                0, originalPlace.requested(), demoCandidate, originalPlace.searchStatus(), NOW.toInstant(), null, null)),
                validation.routes(), validation.calculation(), null);
        assertThrows(IllegalArgumentException.class, () -> ValidatedReplanPlan.fromServerValidation(demoResult, Set.of()));
        var p = ValidatedReplanPlan.fromServerValidation(validation, Set.of(IDS.getFirst()));
        int searches = searchCalls.get(); int routes = routeCalls.get();
        assertEquals(FEASIBLE, service.preview(new StayReplanInput(p, 0, NOW, 600, 50)).after().feasibility());
        assertEquals(searches, searchCalls.get()); assertEquals(routes, routeCalls.get());
        assertThrows(IllegalArgumentException.class, () -> ValidatedReplanPlan.fromServerValidation(validation, Set.of(IDS.get(2))));
    }
    @Test void aggregateOverflowIsUnknownWithoutMutatingPlan() {
        var p = plan();
        var huge = new ValidatedReplanPlan(p.home, p.deadline, p.bufferMinutes, p.visits, p.required,
                List.of(30L, Long.MAX_VALUE / 60, Long.MAX_VALUE / 60), p.legs);
        var r = service.preview(new StayReplanInput(huge, 0, NOW, 600, 50));
        assertEquals(ChangeStatus.CALCULATION_OUT_OF_RANGE, r.changeStatus());
        assertEquals(UNKNOWN, r.after().feasibility());
        assertTrue(r.before().reasons().contains(CALCULATION_OUT_OF_RANGE));
        assertEquals(Long.MAX_VALUE / 60, huge.stayMinutes().get(1));
    }
    @Test void bufferComparisonUsesExactInstantRatherThanDisplayedMinutes() {
        var p = plan(NOW.plusMinutes(100).minusNanos(1), 30);
        var r = service.preview(new StayReplanInput(p, 0, NOW, 600, 30)).after();
        assertEquals(INFEASIBLE, r.feasibility()); assertEquals(29L, r.remainingMarginMinutes());
    }}
