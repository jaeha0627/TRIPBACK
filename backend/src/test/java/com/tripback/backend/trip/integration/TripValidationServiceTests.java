package com.tripback.backend.trip.integration;

import com.tripback.backend.trip.feasibility.*;
import com.tripback.backend.trip.provider.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import java.time.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import static com.tripback.backend.trip.integration.TripValidationResult.*;
import static com.tripback.backend.trip.integration.TripValidationResult.Reason.*;
import static com.tripback.backend.trip.feasibility.TripFeasibilityResult.Feasibility.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class TripValidationServiceTests {
    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
    private static final OffsetDateTime DEPART = OffsetDateTime.parse("2026-11-07T08:00:00+09:00");
    private static final Wgs84Point HOME = new Wgs84Point(37.1, 127.1);
    private static ProviderPlaceId id(int i) { return new ProviderPlaceId("KAKAO_LOCAL", "fixture-" + i); }
    private static ProviderData demo(String provider) {
        return new ProviderData(provider, ProviderData.DataMode.DEMO_SIMULATED, ProviderData.Verification.UNVERIFIED, NOW);
    }
    private static PlaceCandidate candidate(int i) {
        return new PlaceCandidate(id(i), "Synthetic place " + i, "", new Wgs84Point(36 + i * .01, 126 + i * .01), demo("KAKAO_LOCAL"));
    }
    private static TripValidationInput.Visit visit(int i, long stay) {
        return new TripValidationInput.Visit("keyword-" + i, id(i), stay);
    }
    private static TripValidationInput input(int count) {
        return new TripValidationInput(HOME, DEPART, DEPART.plusHours(10), 30,
                IntStream.range(0, count).mapToObj(i -> visit(i, 60)).toList());
    }
    private static RoadRouteResult found(RoadRouteQuery query, long seconds) {
        return new RoadRouteResult("KAKAO_MOBILITY", query, RoadRouteResult.Status.FOUND,
                seconds, demo("KAKAO_MOBILITY"), null, NOW);
    }
    private static PlaceSearchResult search(PlaceSearchResult.Status status, ProviderFailure failure, PlaceCandidate... candidates) {
        return new PlaceSearchResult("KAKAO_LOCAL", status, List.of(candidates), failure, NOW);
    }
    private static RoadRouteResult routeFailure(RoadRouteQuery query, RoadRouteResult.Status status, ProviderFailure failure) {
        return new RoadRouteResult("KAKAO_MOBILITY", query, status, null, null, failure, NOW);
    }

    private static final class FakePlaces implements PlaceSearchProvider {
        final List<PlaceSearchQuery> calls = new ArrayList<>();
        BiFunction<Integer, PlaceSearchQuery, PlaceSearchResult> handler = (i, q) ->
                // Deliberately put a different candidate first.
                TripValidationServiceTests.search(PlaceSearchResult.Status.FOUND, null, candidate(99),
                        candidate(Integer.parseInt(q.keyword().substring("keyword-".length()))));
        public PlaceSearchResult search(PlaceSearchQuery query) {
            int index = calls.size();
            calls.add(query);
            return handler.apply(index, query);
        }
    }
    private static final class FakeRoutes implements RoadRouteProvider {
        final List<RoadRouteQuery> calls = new ArrayList<>();
        BiFunction<Integer, RoadRouteQuery, RoadRouteResult> handler = (i, q) -> found(q, 61 + 60L * i);
        public RoadRouteResult route(RoadRouteQuery query) {
            int index = calls.size();
            calls.add(query);
            return handler.apply(index, query);
        }
    }
    private final FakePlaces places = new FakePlaces();
    private final FakeRoutes routes = new FakeRoutes();
    private final TripValidationService service = new TripValidationService(places, routes);

    private void unknown(TripValidationResult result, List<Integer> missing) {
        assertEquals(UNKNOWN, result.feasibility());
        assertEquals(missing, result.calculation().missingEtaLegIndexes());
        assertNull(result.calculation().totalTravelMinutes());
        assertNull(result.calculation().predictedReturnAt());
        assertNull(result.calculation().remainingMarginMin());
        assertNull(result.calculation().returnDeadlineMet());
        assertNull(result.calculation().safetyBufferSatisfied());
        assertEquals(DataCoverage.INCOMPLETE, result.dataCoverage());
    }

    private void orderedRoundTrip(int count) {
        TripValidationInput input = input(count);
        TripValidationResult result = service.validate(input);
        assertEquals(FEASIBLE, result.feasibility());
        assertEquals(count, places.calls.size());
        assertEquals(count + 1, routes.calls.size());
        assertEquals(count, result.places().size());
        for (int i = 0; i < count; i++) {
            assertEquals(i, result.places().get(i).index());
            assertEquals(input.visits().get(i), result.places().get(i).requested());
            assertEquals(id(i), result.places().get(i).candidate().id());
            assertEquals(PlaceCandidate.VisitStatus.NOT_VERIFIED, result.places().get(i).candidate().visitStatus());
            assertEquals("keyword-" + i, places.calls.get(i).keyword());
            assertEquals(1, places.calls.get(i).page());
            assertEquals(15, places.calls.get(i).size());
        }
        for (int i = 0; i <= count; i++) {
            RoadRouteQuery expected = new RoadRouteQuery(i == 0 ? HOME : candidate(i - 1).coordinates(),
                    i == count ? HOME : candidate(i).coordinates());
            assertEquals(expected, routes.calls.get(i));
            assertEquals(i, result.routes().get(i).index());
            assertEquals(expected, result.routes().get(i).query());
            assertEquals(i, result.calculation().evaluatedLegs().get(i).index());
            assertEquals(NOW, result.routes().get(i).response().data().retrievedAt());
            assertEquals(NOW, result.calculation().evaluatedLegs().get(i).evidence().recordedAt());
        }
        assertEquals(DataCoverage.CONTAINS_DEMO, result.dataCoverage());
        assertEquals(Set.of(ProviderData.DataMode.DEMO_SIMULATED), result.dataModes());
        TripFeasibilityResult expected = new TripFeasibilityService().calculate(new TripFeasibilityInput(
                input.departAt(), input.returnDeadline(), input.safetyBufferMin(),
                input.visits().stream().map(TripValidationInput.Visit::stayMinutes).toList(),
                result.calculation().evaluatedLegs()));
        assertEquals(expected, result.calculation());
        assertEquals(count * 60L, result.calculation().totalStayMinutes());
        assertTrue(result.notice().contains("RETURN_NOT_GUARANTEED"));
        assertTrue(result.notice().contains("NOT_FUTURE_TRAFFIC"));
    }

    @Test void oneVisitRoundTrip() { orderedRoundTrip(1); }
    @Test void threeVisitRoundTrip() { orderedRoundTrip(3); }
    @Test void fiveVisitRoundTripIncludesReturn() { orderedRoundTrip(5); }

    @Test void sufficientMarginAndRoundedSecondsMatchEngineExactly() {
        TripValidationResult result = service.validate(input(1));
        assertEquals(5L, result.calculation().totalTravelMinutes()); // 61 -> 2, 121 -> 3.
        assertEquals(DEPART.plusMinutes(65), result.calculation().predictedReturnAt());
        assertEquals(535L, result.calculation().remainingMarginMin());
        assertTrue(result.calculation().safetyBufferSatisfied());
    }

    @Test void returnAfterDeadlineIsInfeasible() {
        TripValidationInput base = input(1);
        TripValidationResult result = service.validate(new TripValidationInput(HOME, DEPART, DEPART.plusMinutes(64), 0, base.visits()));
        assertEquals(INFEASIBLE, result.feasibility());
        assertFalse(result.calculation().returnDeadlineMet());
        assertEquals(-1L, result.calculation().remainingMarginMin());
    }

    @Test void beforeDeadlineButInsufficientBufferIsInfeasible() {
        TripValidationResult result = service.validate(new TripValidationInput(HOME, DEPART, DEPART.plusMinutes(94), 30, input(1).visits()));
        assertEquals(INFEASIBLE, result.feasibility());
        assertTrue(result.calculation().returnDeadlineMet());
        assertFalse(result.calculation().safetyBufferSatisfied());
    }

    @Test void missingReturnEtaIsUnknownAndReturnIndexIsKept() {
        routes.handler = (i, q) -> i == 3 ? routeFailure(q, RoadRouteResult.Status.ETA_MISSING,
                ProviderFailure.of(ProviderFailure.Code.MISSING_ETA)) : found(q, 60);
        TripValidationResult result = service.validate(input(3));
        unknown(result, List.of(3));
        assertEquals(4, routes.calls.size());
        assertEquals(ETA_MISSING, result.routes().get(3).reason());
        assertEquals(HOME, result.routes().get(3).query().destination());
    }

    @Test void missingIntermediateEtaDoesNotShiftOrDropAnyLeg() {
        routes.handler = (i, q) -> i == 1 ? routeFailure(q, RoadRouteResult.Status.ETA_MISSING,
                ProviderFailure.of(ProviderFailure.Code.MISSING_ETA)) : found(q, 60);
        TripValidationResult result = service.validate(input(3));
        unknown(result, List.of(1));
        assertEquals(List.of(0, 1, 2, 3), result.routes().stream().map(RouteCheck::index).toList());
        assertEquals(3, result.places().size());
    }

    @Test void noSearchResultsKeepsAllVisitsAndBlocksAllRouting() {
        places.handler = (i, q) -> i == 1 ? search(PlaceSearchResult.Status.NO_RESULTS, null)
                : search(PlaceSearchResult.Status.FOUND, null, candidate(i));
        TripValidationResult result = service.validate(input(3));
        unknown(result, List.of(0, 1, 2, 3));
        assertEquals(3, places.calls.size());
        assertEquals(3, result.places().size());
        assertEquals(PLACE_NO_RESULTS, result.places().get(1).reason());
        assertEquals(0, routes.calls.size());
        assertTrue(result.routes().stream().allMatch(r -> r.query() == null && r.reason() == PLACES_UNRESOLVED));
    }

    @Test void missingSelectedIdNeverUsesFirstHit() {
        places.handler = (i, q) -> search(PlaceSearchResult.Status.FOUND, null, candidate(99));
        TripValidationResult result = service.validate(input(1));
        unknown(result, List.of(0, 1));
        assertEquals(SELECTED_PLACE_NOT_FOUND, result.places().getFirst().reason());
        assertNull(result.places().getFirst().candidate());
        assertTrue(routes.calls.isEmpty());
    }

    @Test void duplicateSelectedIdsAreRejectedBeforeProviderCalls() {
        assertThrows(IllegalArgumentException.class, () -> new TripValidationInput(HOME, DEPART, DEPART.plusHours(1), 0,
                List.of(visit(0, 10), new TripValidationInput.Visit("different keyword", id(0), 20))));
        assertTrue(places.calls.isEmpty());
        assertTrue(routes.calls.isEmpty());
    }

    @Test void namespaceAndBlankKeywordsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TripValidationInput.Visit("keyword", new ProviderPlaceId("PLEOS", "id"), 1));
        assertThrows(IllegalArgumentException.class, () -> new TripValidationInput.Visit(" ", id(0), 1));
        assertThrows(IllegalArgumentException.class, () -> new TripValidationInput.Visit(null, id(0), 1));
    }

    @Test void invalidCoordinatesAndStayTimesAreRejected() {
        for (double latitude : new double[]{91, -91, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new Wgs84Point(latitude, 127));
        }
        assertThrows(IllegalArgumentException.class, () -> new Wgs84Point(37, 181));
        assertThrows(IllegalArgumentException.class, () -> visit(0, -1));
        assertThrows(IllegalArgumentException.class, () -> visit(0, Long.MAX_VALUE));
    }

    @Test void emptyExcessNullVisitsAndMissingOriginAreRejected() {
        for (List<TripValidationInput.Visit> visits : Arrays.asList(null, List.<TripValidationInput.Visit>of(),
                Arrays.asList((TripValidationInput.Visit) null), IntStream.range(0, 6).mapToObj(i -> visit(i, 60)).toList())) {
            assertThrows(IllegalArgumentException.class, () -> new TripValidationInput(HOME, DEPART, DEPART.plusHours(1), 0, visits));
        }
        assertThrows(IllegalArgumentException.class, () -> new TripValidationInput(null, DEPART, DEPART.plusHours(1), 0, input(1).visits()));
        assertThrows(IllegalArgumentException.class, () -> service.validate(null));
    }

    @Test void explicitOffsetsAndValidTimeRangesAreRequired() {
        for (String time : Arrays.asList(null, "2026-11-07T08:00:00", "2026-02-30T08:00:00+09:00")) {
            assertThrows(IllegalArgumentException.class, () -> TripValidationInput.fromIso8601(HOME, time, DEPART.plusHours(1).toString(), 0, input(1).visits()));
        }
        assertThrows(IllegalArgumentException.class, () -> new TripValidationInput(HOME, DEPART, DEPART.minusSeconds(1), 0, input(1).visits()));
        assertThrows(IllegalArgumentException.class, () -> new TripValidationInput(HOME, DEPART, DEPART.plusHours(1), -1, input(1).visits()));
        TripValidationInput input = TripValidationInput.fromIso8601(HOME, DEPART.toString(), DEPART.plusHours(10).withOffsetSameInstant(ZoneOffset.UTC).toString(), 30, input(1).visits());
        assertEquals(FEASIBLE, service.validate(input).feasibility());
    }

    @Test void placeAuthenticationAndConnectionFailuresRemainDistinct() {
        for (ProviderFailure failure : List.of(ProviderFailure.fromHttpStatus(401), ProviderFailure.of(ProviderFailure.Code.CONNECTION_FAILED))) {
            places.handler = (i, q) -> search(PlaceSearchResult.Status.FAILED, failure);
            TripValidationResult result = service.validate(input(1));
            unknown(result, List.of(0, 1));
            assertEquals(PLACE_SEARCH_FAILED, result.places().getFirst().reason());
            assertEquals(failure, result.places().getFirst().failure());
            assertTrue(routes.calls.isEmpty());
        }
    }

    @Test void unconfiguredSearchIsNotNoResults() {
        places.handler = (i, q) -> search(PlaceSearchResult.Status.UNAVAILABLE, ProviderFailure.of(ProviderFailure.Code.NOT_CONFIGURED));
        TripValidationResult result = service.validate(input(1));
        assertEquals(PLACE_SEARCH_UNAVAILABLE, result.places().getFirst().reason());
        assertEquals(ProviderFailure.Code.NOT_CONFIGURED, result.places().getFirst().failure().code());
    }

    @Test void duplicateSearchIdsAreInvalidRatherThanAmbiguousSelection() {
        places.handler = (i, q) -> search(PlaceSearchResult.Status.FOUND, null, candidate(0), candidate(0));
        TripValidationResult result = service.validate(input(1));
        assertEquals(INVALID_PLACE_RESPONSE, result.places().getFirst().reason());
        unknown(result, List.of(0, 1));
    }

    @Test void wrongSearchProviderAndNullResponseAreRejected() {
        places.handler = (i, q) -> new PlaceSearchResult("OTHER", PlaceSearchResult.Status.NO_RESULTS, List.of(), null, NOW);
        assertEquals(INVALID_PLACE_RESPONSE, service.validate(input(1)).places().getFirst().reason());
        places.handler = (i, q) -> null;
        assertEquals(INVALID_PLACE_RESPONSE, service.validate(input(1)).places().getFirst().reason());
    }

    @Test void routeNoRouteMissingAndApiFailuresAreDistinctAndAllCallsContinue() {
        routes.handler = (i, q) -> switch (i) {
            case 0 -> routeFailure(q, RoadRouteResult.Status.NO_ROUTE, null);
            case 1 -> routeFailure(q, RoadRouteResult.Status.ETA_MISSING, ProviderFailure.of(ProviderFailure.Code.MISSING_ETA));
            case 2 -> routeFailure(q, RoadRouteResult.Status.FAILED, ProviderFailure.fromHttpStatus(429));
            default -> routeFailure(q, RoadRouteResult.Status.UNAVAILABLE, ProviderFailure.of(ProviderFailure.Code.NOT_CONFIGURED));
        };
        TripValidationResult result = service.validate(input(3));
        unknown(result, List.of(0, 1, 2, 3));
        assertEquals(List.of(NO_ROUTE, ETA_MISSING, ROUTE_FAILED, ROUTE_UNAVAILABLE), result.routes().stream().map(RouteCheck::reason).toList());
        assertEquals(ProviderFailure.Code.RATE_LIMITED, result.routes().get(2).response().failure().code());
        assertEquals(4, routes.calls.size());
    }

    @Test void wrongRouteEndpointsProviderAndNullNeverSupplyEta() {
        routes.handler = (i, q) -> found(new RoadRouteQuery(q.destination(), q.origin()), 60);
        unknown(service.validate(input(1)), List.of(0, 1));
        routes.handler = (i, q) -> new RoadRouteResult("OTHER", q, RoadRouteResult.Status.FOUND, 60L, demo("OTHER"), null, NOW);
        assertTrue(service.validate(input(1)).routes().stream().allMatch(r -> r.reason() == INVALID_ROUTE_RESPONSE));
        routes.handler = (i, q) -> null;
        unknown(service.validate(input(1)), List.of(0, 1));
    }

    @Test void providerExceptionsAreSanitizedAndVisitsRemainPresent(CapturedOutput output) {
        String secret = "synthetic-sensitive-" + UUID.randomUUID();
        places.handler = (i, q) -> { throw new IllegalStateException(secret); };
        TripValidationResult searchFailed = service.validate(input(3));
        assertEquals(3, searchFailed.places().size());
        assertTrue(searchFailed.places().stream().allMatch(p -> p.reason() == PLACE_PROVIDER_EXCEPTION));
        assertFalse(searchFailed.toString().contains(secret));
        // Fresh providers keep call indexes local to this second validation.
        FakePlaces good = new FakePlaces();
        routes.handler = (i, q) -> { throw new IllegalStateException(secret); };
        TripValidationResult routeFailed = new TripValidationService(good, routes).validate(input(3));
        unknown(routeFailed, List.of(0, 1, 2, 3));
        assertTrue(routeFailed.routes().stream().allMatch(r -> r.reason() == ROUTE_PROVIDER_EXCEPTION));
        assertFalse(routeFailed.toString().contains(secret));
        assertFalse(output.getAll().contains(secret));
    }

    @Test void hugeIndividualEtaBecomesUnknownAndPreservesOriginalSeconds() {
        routes.handler = (i, q) -> found(q, Long.MAX_VALUE);
        TripValidationResult result = service.validate(input(1));
        unknown(result, List.of(0, 1));
        assertTrue(result.routes().stream().allMatch(r -> r.reason() == DURATION_OVERFLOW));
        assertEquals(Long.MAX_VALUE, result.routes().getFirst().response().durationSeconds());
    }

    @Test void totalDurationOverflowIsExplicitAndNeverReturnsFeasible() {
        long hugeStay = Long.MAX_VALUE / 60;
        TripValidationInput input = new TripValidationInput(HOME, DEPART, DEPART.plusHours(10), 0,
                List.of(visit(0, hugeStay), visit(1, hugeStay)));
        TripValidationResult result = service.validate(input);
        assertEquals(UNKNOWN, result.feasibility());
        assertEquals(CALCULATION_OVERFLOW, result.calculationFailure());
        assertNull(result.calculation());
        assertEquals(2, result.places().size());
        assertEquals(3, result.routes().size());
        assertEquals(DataCoverage.INCOMPLETE, result.dataCoverage());
    }

    @Test void timestampOverflowIsExplicit() {
        TripValidationInput input = new TripValidationInput(HOME, OffsetDateTime.MAX.minusMinutes(1), OffsetDateTime.MAX,
                0, List.of(visit(0, 60)));
        TripValidationResult result = service.validate(input);
        assertEquals(CALCULATION_OVERFLOW, result.calculationFailure());
        assertEquals(UNKNOWN, result.feasibility());
    }

    @Test void sourceModesAreAggregatedWithoutInventingLivePlaceOrEtaFixtures() {
        // Metadata-label policy only: these objects are NOT attached to fake places or route ETAs.
        ProviderData liveLabel = new ProviderData("KAKAO_LOCAL", ProviderData.DataMode.VERIFIED_LIVE,
                ProviderData.Verification.VALIDATED_PROVIDER_DATA, NOW);
        ProviderData cachedLabel = new ProviderData("KAKAO_MOBILITY", ProviderData.DataMode.VERIFIED_CACHED,
                ProviderData.Verification.VALIDATED_PROVIDER_DATA, NOW.minusSeconds(3600));
        assertEquals(DataCoverage.CONTAINS_DEMO, TripValidationResult.coverageOf(List.of(liveLabel, demo("KAKAO_MOBILITY")), true));
        assertEquals(DataCoverage.CONTAINS_DEMO, TripValidationResult.coverageOf(List.of(demo("KAKAO_LOCAL"), cachedLabel), true));
        assertEquals(DataCoverage.ALL_PROVIDER_DATA_DECLARED_VALIDATED, TripValidationResult.coverageOf(List.of(liveLabel, cachedLabel), true));
        assertEquals(DataCoverage.INCOMPLETE, TripValidationResult.coverageOf(List.of(liveLabel), false));
        assertEquals(ProviderData.DataMode.VERIFIED_CACHED, cachedLabel.dataMode());
        assertEquals(NOW.minusSeconds(3600), cachedLabel.retrievedAt());
    }

    @Test void inputAndOutputListsAreImmutable() {
        List<TripValidationInput.Visit> visits = new ArrayList<>(List.of(visit(0, 60)));
        TripValidationInput input = new TripValidationInput(HOME, DEPART, DEPART.plusHours(10), 30, visits);
        visits.clear();
        TripValidationResult result = service.validate(input);
        assertEquals(1, input.visits().size());
        assertThrows(UnsupportedOperationException.class, () -> input.visits().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.places().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.routes().clear());
    }
}
