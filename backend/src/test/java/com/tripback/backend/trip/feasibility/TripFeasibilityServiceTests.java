package com.tripback.backend.trip.feasibility;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static com.tripback.backend.trip.feasibility.TripFeasibilityResult.Feasibility.*;
import static com.tripback.backend.trip.feasibility.TripFeasibilityResult.RouteDataStatus.*;
import static com.tripback.backend.trip.feasibility.TripFeasibilityResult.UnknownReason.*;
import static org.junit.jupiter.api.Assertions.*;

class TripFeasibilityServiceTests {
    private static final String DEPART = "2026-11-07T08:00:00+09:00";
    private final TripFeasibilityService service = new TripFeasibilityService();
    private final TravelLeg.EtaEvidence demo = new TravelLeg.EtaEvidence("unit-test fixture",
            TravelLeg.DataMode.DEMO_SIMULATED, TravelLeg.Verification.UNVERIFIED, null);

    private List<TravelLeg> legs(Long... values) {
        return IntStream.range(0, values.length).mapToObj(index -> new TravelLeg(index, values[index], demo)).toList();
    }

    private TripFeasibilityInput input(String deadline, long safety, List<Long> stays, List<TravelLeg> travel) {
        return TripFeasibilityInput.fromIso8601(DEPART, deadline, safety, stays, travel);
    }

    private TripFeasibilityResult standard(String deadline, long safety) {
        return service.calculate(input(deadline, safety, List.of(60L, 45L), legs(20L, 15L, 40L)));
    }

    @Test
    void completeRoundTripSatisfiesDeadlineAndBufferMathematically() {
        TripFeasibilityResult result = standard("2026-11-07T12:00:00+09:00", 30);
        assertEquals(FEASIBLE, result.feasibility());
        assertEquals(75L, result.totalTravelMinutes());
        assertEquals(105L, result.totalStayMinutes());
        assertEquals(OffsetDateTime.parse("2026-11-07T11:00:00+09:00"), result.predictedReturnAt());
        assertEquals(Duration.ofMinutes(60), result.remainingMargin());
        assertEquals(60L, result.remainingMarginMin());
        assertTrue(result.returnDeadlineMet());
        assertTrue(result.safetyBufferSatisfied());
        assertEquals(UNVERIFIED, result.routeDataStatus());
    }

    @Test
    void exactSafetyBufferEqualityIsSatisfied() {
        TripFeasibilityResult result = standard("2026-11-07T11:30:00+09:00", 30);
        assertEquals(FEASIBLE, result.feasibility());
        assertTrue(result.safetyBufferSatisfied());
        assertEquals(30L, result.remainingMarginMin());
    }

    @Test
    void returningBeforeDeadlineCanStillMissSafetyBuffer() {
        TripFeasibilityResult result = standard("2026-11-07T11:29:59+09:00", 30);
        assertEquals(INFEASIBLE, result.feasibility());
        assertTrue(result.returnDeadlineMet());
        assertFalse(result.safetyBufferSatisfied());
        assertEquals(29L, result.remainingMarginMin());
    }

    @Test
    void returningAfterDeadlineIsInfeasible() {
        TripFeasibilityResult result = standard("2026-11-07T10:59:59+09:00", 0);
        assertEquals(INFEASIBLE, result.feasibility());
        assertFalse(result.returnDeadlineMet());
        assertFalse(result.safetyBufferSatisfied());
        assertEquals(-1L, result.remainingMarginMin());
    }

    private void assertUnknown(TripFeasibilityResult result, List<Integer> missing) {
        assertEquals(UNKNOWN, result.feasibility());
        assertEquals(INCOMPLETE, result.routeDataStatus());
        assertNull(result.totalTravelMinutes());
        assertNull(result.predictedReturnAt());
        assertNull(result.remainingMargin());
        assertNull(result.remainingMarginMin());
        assertNull(result.returnDeadlineMet());
        assertNull(result.safetyBufferSatisfied());
        assertEquals(missing, result.missingEtaLegIndexes());
    }

    @Test
    void omittedFinalReturnLegIsUnknownRatherThanAssumingZero() {
        TripFeasibilityResult result = service.calculate(input("2026-11-07T18:00:00+09:00", 30,
                List.of(60L, 45L), legs(20L, 15L)));
        assertUnknown(result, List.of(2));
        assertTrue(result.unknownReasons().contains(MISSING_REQUIRED_LEG));
        assertTrue(result.unknownReasons().contains(LEG_COUNT_MISMATCH));
        assertEquals(105L, result.totalStayMinutes());
    }

    @Test
    void finalReturnLegWithoutEtaIsUnknown() {
        TripFeasibilityResult result = service.calculate(input("2026-11-07T18:00:00+09:00", 30,
                List.of(60L, 45L), legs(20L, 15L, null)));
        assertUnknown(result, List.of(2));
        assertTrue(result.unknownReasons().contains(MISSING_ETA));
        assertFalse(result.unknownReasons().contains(LEG_COUNT_MISMATCH));
    }

    @Test
    void missingIntermediateEtaIsUnknownEvenWhenOtherTimesFit() {
        TripFeasibilityResult result = service.calculate(input("2026-11-07T18:00:00+09:00", 30,
                List.of(60L, 45L), legs(20L, null, 40L)));
        assertUnknown(result, List.of(1));
        assertTrue(result.unknownReasons().contains(MISSING_ETA));
    }

    @Test
    void explicitIndexesKeepReturnLegFromShiftingIntoMissingIntermediateSlot() {
        TripFeasibilityResult result = service.calculate(input("2026-11-07T18:00:00+09:00", 30,
                List.of(60L, 45L), List.of(new TravelLeg(2, 40L, demo), new TravelLeg(0, 20L, demo))));
        assertUnknown(result, List.of(1));
    }

    @Test
    void tooFewLegsReportsCountMismatchAndAllMissingIndexes() {
        TripFeasibilityResult result = service.calculate(input("2026-11-07T18:00:00+09:00", 30,
                List.of(60L, 45L), List.of()));
        assertUnknown(result, List.of(0, 1, 2));
        assertTrue(result.unknownReasons().contains(LEG_COUNT_MISMATCH));
    }

    @Test
    void extraOrDuplicateOrNegativeLegIndexesAreInvalid() {
        assertThrows(IllegalArgumentException.class, () -> service.calculate(input("2026-11-07T18:00:00+09:00", 30,
                List.of(60L), legs(10L, 20L, 30L))));
        assertThrows(IllegalArgumentException.class, () -> service.calculate(input("2026-11-07T18:00:00+09:00", 30,
                List.of(60L), List.of(new TravelLeg(0, 10L, demo), new TravelLeg(0, 20L, demo)))));
        assertThrows(IllegalArgumentException.class, () -> new TravelLeg(-1, 0L, demo));
    }

    @Test
    void negativeStayTravelAndSafetyTimesAreInvalid() {
        assertThrows(IllegalArgumentException.class, () -> input("2026-11-07T18:00:00+09:00", 30, List.of(-1L), legs(10L, 20L)));
        assertThrows(IllegalArgumentException.class, () -> new TravelLeg(0, -1L, demo));
        assertThrows(IllegalArgumentException.class, () -> input("2026-11-07T18:00:00+09:00", -1, List.of(60L), legs(10L, 20L)));
    }

    @Test
    void crossingMidnightUsesActualDate() {
        TripFeasibilityInput input = TripFeasibilityInput.fromIso8601("2026-11-07T23:30:00+09:00",
                "2026-11-08T01:00:00+09:00", 30, List.of(30L), legs(20L, 10L));
        TripFeasibilityResult result = service.calculate(input);
        assertEquals(FEASIBLE, result.feasibility());
        assertEquals(OffsetDateTime.parse("2026-11-08T00:30:00+09:00"), result.predictedReturnAt());
    }

    @Test
    void differentOffsetsAndCalendarDatesAreComparedAsInstants() {
        TripFeasibilityInput input = TripFeasibilityInput.fromIso8601("2026-11-08T00:00:00+09:00",
                "2026-11-07T19:00:00+01:00", 30, List.of(30L), legs(20L, 10L));
        TripFeasibilityResult result = service.calculate(input);
        assertEquals(FEASIBLE, result.feasibility());
        assertEquals(120L, result.remainingMarginMin());
        assertEquals(OffsetDateTime.parse("2026-11-08T01:00:00+09:00"), result.predictedReturnAt());
    }

    @Test
    void equivalentDeadlineInstantsProduceEquivalentResults() {
        TripFeasibilityResult local = standard("2026-11-07T11:30:00+09:00", 30);
        TripFeasibilityResult utc = standard("2026-11-07T02:30:00Z", 30);
        assertEquals(local, utc);
    }

    @Test
    void oneNanosecondBelowBufferFailsAndExactBoundaryPasses() {
        TripFeasibilityInput below = TripFeasibilityInput.fromIso8601("2026-11-07T08:00:30.25+09:00",
                "2026-11-07T10:00:30.249999999+09:00", 30, List.of(60L), legs(15L, 15L));
        TripFeasibilityResult result = service.calculate(below);
        assertEquals(INFEASIBLE, result.feasibility());
        assertEquals(Duration.ofMinutes(30).minusNanos(1), result.remainingMargin());
        assertEquals(29L, result.remainingMarginMin());
        assertEquals(FEASIBLE, service.calculate(new TripFeasibilityInput(below.departAt(),
                below.returnDeadline().plusNanos(1), 30, below.stayMinutes(), below.travelLegs())).feasibility());
    }

    @Test
    void minuteDisplayFloorsPositiveAndNegativeSecondFractions() {
        OffsetDateTime returned = OffsetDateTime.parse(DEPART).plusMinutes(60);
        List<Duration> margins = List.of(Duration.ZERO, Duration.ofSeconds(59).plusNanos(999999999),
                Duration.ofMinutes(1), Duration.ofNanos(-1), Duration.ofSeconds(-59),
                Duration.ofMinutes(-1), Duration.ofMinutes(-1).minusNanos(1));
        List<Long> expectedMinutes = List.of(0L, 0L, 1L, -1L, -1L, -1L, -2L);
        for (int index = 0; index < margins.size(); index++) {
            TripFeasibilityResult result = service.calculate(new TripFeasibilityInput(OffsetDateTime.parse(DEPART),
                    returned.plus(margins.get(index)), 0, List.of(30L), legs(20L, 10L)));
            assertEquals(expectedMinutes.get(index), result.remainingMarginMin());
            assertEquals(margins.get(index), result.remainingMargin());
            assertEquals(!margins.get(index).isNegative(), result.returnDeadlineMet());
            assertEquals(!margins.get(index).isNegative(), result.safetyBufferSatisfied());
        }
    }

    @Test
    void handlesTotalsAboveIntegerRangeWithoutIntOverflow() {
        TripFeasibilityResult result = service.calculate(input("2026-11-07T18:00:00+09:00", 0,
                List.of((long) Integer.MAX_VALUE), legs(10L, 20L)));
        assertEquals((long) Integer.MAX_VALUE, result.totalStayMinutes());
        assertEquals(30L, result.totalTravelMinutes());
        assertEquals(INFEASIBLE, result.feasibility());
    }

    @Test
    void rejectsIndividualDurationOverflow() {
        assertThrows(IllegalArgumentException.class, () -> new TravelLeg(0, Long.MAX_VALUE, demo));
        assertThrows(IllegalArgumentException.class, () -> input("2026-11-07T18:00:00+09:00", Long.MAX_VALUE, List.of(), legs(0L)));
        assertThrows(IllegalArgumentException.class, () -> input("2026-11-07T18:00:00+09:00", 0, List.of(Long.MAX_VALUE), legs(0L, 0L)));
    }

    @Test
    void rejectsOverflowInStaySumTravelSumAndMinutesToSeconds() {
        long max = Long.MAX_VALUE / 60;
        assertThrows(IllegalArgumentException.class, () -> service.calculate(input("2026-11-07T18:00:00+09:00", 0,
                Collections.nCopies(61, max), List.of())));
        Long[] travel = new Long[61];
        Arrays.fill(travel, max);
        assertThrows(IllegalArgumentException.class, () -> service.calculate(input("2026-11-07T18:00:00+09:00", 0,
                Collections.nCopies(60, 0L), legs(travel))));
        assertThrows(IllegalArgumentException.class, () -> service.calculate(input("2026-11-07T18:00:00+09:00", 0,
                List.of(max), legs(max, 0L))));
    }

    @Test
    void rejectsPredictedTimestampOverflow() {
        TripFeasibilityInput input = new TripFeasibilityInput(OffsetDateTime.MAX.minusMinutes(1), OffsetDateTime.MAX,
                0, List.of(5L), legs(0L, 0L));
        assertThrows(IllegalArgumentException.class, () -> service.calculate(input));
    }

    @Test
    void invalidDatesOffsetsMissingValuesAndReversedDeadlineAreRejected() {
        for (String date : List.of("2026-02-30T08:00:00+09:00", "2026-11-07T08:00:00", "invalid")) {
            assertThrows(IllegalArgumentException.class, () -> TripFeasibilityInput.fromIso8601(date,
                    "2026-11-07T18:00:00+09:00", 0, List.of(), legs(0L)));
        }
        assertThrows(IllegalArgumentException.class, () -> input("2026-11-07T07:00:00+09:00", 0, List.of(), legs(0L)));
        assertThrows(IllegalArgumentException.class, () -> service.calculate(null));
        assertThrows(IllegalArgumentException.class, () -> input("2026-11-07T18:00:00+09:00", 0, null, legs(0L)));
        assertThrows(IllegalArgumentException.class, () -> input("2026-11-07T18:00:00+09:00", 0, Arrays.asList((Long) null), legs(0L, 0L)));
        assertThrows(IllegalArgumentException.class, () -> input("2026-11-07T18:00:00+09:00", 0, List.of(), Arrays.asList((TravelLeg) null)));
    }

    @Test
    void demoEvidenceIsPreservedAndNeverPromotedByMathematicalSuccess() {
        TripFeasibilityResult result = standard("2026-11-07T12:00:00+09:00", 30);
        assertEquals(FEASIBLE, result.feasibility());
        assertEquals(UNVERIFIED, result.routeDataStatus());
        assertTrue(result.evaluatedLegs().stream().allMatch(leg -> leg.evidence().equals(demo)));
        assertThrows(IllegalArgumentException.class, () -> new TravelLeg.EtaEvidence("test",
                TravelLeg.DataMode.DEMO_SIMULATED, TravelLeg.Verification.VERIFIED, Instant.EPOCH));
    }

    @Test
    void verifiedMetadataIsOnlyACallerDeclarationAndMixedDemoRemainsUnverified() {
        TravelLeg.EtaEvidence cached = new TravelLeg.EtaEvidence("provider-reference",
                TravelLeg.DataMode.VERIFIED_CACHED, TravelLeg.Verification.VERIFIED, Instant.EPOCH);
        TripFeasibilityResult declared = service.calculate(input("2026-11-07T12:00:00+09:00", 30,
                List.of(60L), List.of(new TravelLeg(0, 10L, cached), new TravelLeg(1, 20L, cached))));
        assertEquals(ALL_ETAS_DECLARED_VERIFIED, declared.routeDataStatus());
        assertEquals(cached, declared.evaluatedLegs().getFirst().evidence());
        TripFeasibilityResult mixed = service.calculate(input("2026-11-07T12:00:00+09:00", 30,
                List.of(60L), List.of(new TravelLeg(0, 10L, cached), new TravelLeg(1, 20L, demo))));
        assertEquals(UNVERIFIED, mixed.routeDataStatus());
        assertThrows(IllegalArgumentException.class, () -> new TravelLeg(1, null, cached));
        assertThrows(IllegalArgumentException.class, () -> new TravelLeg(0, 10L, null));
    }

    @Test
    void inputListsAreSnapshotsAndNoVisitsAreDropped() {
        List<Long> stays = new ArrayList<>(List.of(60L, 45L));
        List<TravelLeg> travel = new ArrayList<>(legs(20L, 15L, 40L));
        TripFeasibilityInput input = input("2026-11-07T12:00:00+09:00", 30, stays, travel);
        stays.clear();
        travel.clear();
        TripFeasibilityResult result = service.calculate(input);
        assertEquals(2, input.stayMinutes().size());
        assertEquals(3, result.evaluatedLegs().size());
        assertEquals(105L, result.totalStayMinutes());
        assertThrows(UnsupportedOperationException.class, () -> result.evaluatedLegs().clear());
    }

    @Test
    void explicitZeroTimesAreValidButNoReturnLegIsStillUnknownWithNoVisits() {
        TripFeasibilityResult zero = service.calculate(input(DEPART, 0, List.of(), legs(0L)));
        assertEquals(FEASIBLE, zero.feasibility());
        assertEquals(0L, zero.totalTravelMinutes());
        assertEquals(0L, zero.remainingMarginMin());
        assertUnknown(service.calculate(input(DEPART, 0, List.of(), List.of())), List.of(0));
    }
}
