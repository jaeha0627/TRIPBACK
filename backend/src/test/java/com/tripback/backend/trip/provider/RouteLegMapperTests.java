package com.tripback.backend.trip.provider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.tripback.backend.trip.feasibility.TravelLeg;
import com.tripback.backend.trip.feasibility.TripFeasibilityInput;
import com.tripback.backend.trip.feasibility.TripFeasibilityResult;
import com.tripback.backend.trip.feasibility.TripFeasibilityService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RouteLegMapperTests {
    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
    private static final String PROVIDER = "TEST_ONLY";
    private final Wgs84Point origin = new Wgs84Point(37, 127);
    private final Wgs84Point stop = new Wgs84Point(38, 128);
    private final RoadRouteQuery outbound = new RoadRouteQuery(origin, stop);
    private final RoadRouteQuery inbound = new RoadRouteQuery(stop, origin);
    private final ProviderData demo = new ProviderData(PROVIDER, ProviderData.DataMode.DEMO_SIMULATED,
            ProviderData.Verification.UNVERIFIED, NOW);

    private RoadRouteResult found(RoadRouteQuery query, long seconds, ProviderData data) {
        return new RoadRouteResult(PROVIDER, query, RoadRouteResult.Status.FOUND, seconds, data, null, NOW);
    }

    private TripFeasibilityResult calculate(List<TravelLeg> legs) {
        return new TripFeasibilityService().calculate(TripFeasibilityInput.fromIso8601(
                "2026-11-07T08:00:00+09:00", "2026-11-07T18:00:00+09:00", 30, List.of(60L), legs));
    }

    @Test
    void missingReturnEtaKeepsRequiredReturnLegAndEngineUnknown() {
        RoadRouteResult missing = new RoadRouteResult(PROVIDER, inbound, RoadRouteResult.Status.ETA_MISSING,
                null, null, ProviderFailure.of(ProviderFailure.Code.MISSING_ETA), NOW);
        TravelLeg out = RouteLegMapper.toTravelLeg(0, outbound, found(outbound, 600, demo));
        TravelLeg back = RouteLegMapper.toTravelLeg(1, inbound, missing);
        TripFeasibilityResult result = calculate(List.of(out, back));
        assertEquals(TripFeasibilityResult.Feasibility.UNKNOWN, result.feasibility());
        assertEquals(List.of(1), result.missingEtaLegIndexes());
        assertEquals(2, result.evaluatedLegs().size());
        assertNull(back.minutes());
        assertNull(back.evidence());
        assertNull(result.totalTravelMinutes());
        assertNull(result.predictedReturnAt());
        assertNull(result.safetyBufferSatisfied());
    }

    @Test
    void unavailableFailedNoRouteAndAbsentResultsAllPreserveUnknownRatherThanZero() {
        List<RoadRouteResult> outcomes = new java.util.ArrayList<>();
        outcomes.add(UnavailableProviders.roadRoute(Clock.fixed(NOW, ZoneOffset.UTC)).route(inbound));
        outcomes.add(new RoadRouteResult(PROVIDER, inbound, RoadRouteResult.Status.FAILED, null, null,
                ProviderFailure.fromHttpStatus(500), NOW));
        outcomes.add(new RoadRouteResult(PROVIDER, inbound, RoadRouteResult.Status.NO_ROUTE, null, null, null, NOW));
        outcomes.add(null);
        for (RoadRouteResult outcome : outcomes) {
            TravelLeg missing = RouteLegMapper.toTravelLeg(1, inbound, outcome);
            TripFeasibilityResult result = calculate(List.of(RouteLegMapper.toTravelLeg(0, outbound, found(outbound, 600, demo)), missing));
            assertEquals(TripFeasibilityResult.Feasibility.UNKNOWN, result.feasibility());
            assertNull(missing.minutes());
            assertNull(result.remainingMarginMin());
        }
    }

    @Test
    void missingIntermediateLegDoesNotRemoveAnyVisitOrShiftIndexes() {
        Wgs84Point secondStop = new Wgs84Point(39, 129);
        RoadRouteQuery middle = new RoadRouteQuery(stop, secondStop);
        RoadRouteQuery home = new RoadRouteQuery(secondStop, origin);
        List<TravelLeg> legs = List.of(
                RouteLegMapper.toTravelLeg(0, outbound, found(outbound, 60, demo)),
                RouteLegMapper.toTravelLeg(1, middle, null),
                RouteLegMapper.toTravelLeg(2, home, found(home, 60, demo)));
        TripFeasibilityResult result = new TripFeasibilityService().calculate(TripFeasibilityInput.fromIso8601(
                "2026-11-07T08:00:00+09:00", "2026-11-07T18:00:00+09:00", 30, List.of(60L, 45L), legs));
        assertEquals(TripFeasibilityResult.Feasibility.UNKNOWN, result.feasibility());
        assertEquals(List.of(1), result.missingEtaLegIndexes());
        assertEquals(105, result.totalStayMinutes());
        assertEquals(3, result.evaluatedLegs().size());
    }

    @Test
    void sourceModeVerificationAndOriginalRetrievalTimeArePreserved() {
        for (ProviderData.DataMode mode : ProviderData.DataMode.values()) {
            ProviderData.Verification verification = mode == ProviderData.DataMode.DEMO_SIMULATED
                    ? ProviderData.Verification.UNVERIFIED : ProviderData.Verification.VALIDATED_PROVIDER_DATA;
            // These are declared metadata fixtures, not real provider lookups.
            ProviderData data = new ProviderData(PROVIDER, mode, verification, NOW.minusSeconds(3600));
            TravelLeg leg = RouteLegMapper.toTravelLeg(0, outbound, found(outbound, 60, data));
            assertEquals(mode.name(), leg.evidence().dataMode().name());
            assertEquals(data.retrievedAt(), leg.evidence().recordedAt());
            assertEquals(PROVIDER, leg.evidence().source());
            assertEquals(mode == ProviderData.DataMode.DEMO_SIMULATED ? TravelLeg.Verification.UNVERIFIED
                    : TravelLeg.Verification.VERIFIED, leg.evidence().verification());
        }
    }

    @Test
    void completeDemoEtasAllowOnlyMathematicalCalculationNotVerifiedLivePromotion() {
        TripFeasibilityResult result = calculate(List.of(
                RouteLegMapper.toTravelLeg(0, outbound, found(outbound, 600, demo)),
                RouteLegMapper.toTravelLeg(1, inbound, found(inbound, 600, demo))));
        assertEquals(TripFeasibilityResult.Feasibility.FEASIBLE, result.feasibility());
        assertEquals(TripFeasibilityResult.RouteDataStatus.UNVERIFIED, result.routeDataStatus());
        assertTrue(result.evaluatedLegs().stream().allMatch(leg -> leg.evidence().dataMode() == TravelLeg.DataMode.DEMO_SIMULATED));
    }

    @Test
    void cachedEtaIsNeverRelabeledLiveAndMixedDemoRemainsUnverified() {
        ProviderData cached = new ProviderData(PROVIDER, ProviderData.DataMode.VERIFIED_CACHED,
                ProviderData.Verification.VALIDATED_PROVIDER_DATA, NOW.minusSeconds(3600));
        TravelLeg out = RouteLegMapper.toTravelLeg(0, outbound, found(outbound, 600, cached));
        TripFeasibilityResult result = calculate(List.of(out,
                RouteLegMapper.toTravelLeg(1, inbound, found(inbound, 600, demo))));
        assertEquals(TravelLeg.DataMode.VERIFIED_CACHED, out.evidence().dataMode());
        assertEquals(TripFeasibilityResult.RouteDataStatus.UNVERIFIED, result.routeDataStatus());
    }

    @Test
    void secondsAreConservativelyRoundedUpPerLegIncludingExplicitZero() {
        long[][] cases = {{0, 0}, {1, 1}, {59, 1}, {60, 1}, {61, 2}, {119, 2}, {120, 2}};
        for (long[] pair : cases) {
            TravelLeg leg = RouteLegMapper.toTravelLeg(0, outbound, found(outbound, pair[0], demo));
            assertEquals(pair[1], leg.minutes());
        }
    }

    @Test
    void routeForDifferentEndpointsCannotSupplyEtaForARequiredLeg() {
        assertThrows(IllegalArgumentException.class, () -> RouteLegMapper.toTravelLeg(1, inbound, found(outbound, 60, demo)));
        assertThrows(IllegalArgumentException.class, () -> RouteLegMapper.toTravelLeg(0, null, null));
        assertThrows(IllegalArgumentException.class, () -> RouteLegMapper.toTravelLeg(-1, inbound, null));
    }

    @Test
    void excessiveDurationFailsWithoutOverflowOrFabricatedEta() {
        assertThrows(IllegalArgumentException.class, () -> RouteLegMapper.toTravelLeg(0, outbound, found(outbound, Long.MAX_VALUE, demo)));
    }
}
