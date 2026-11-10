package com.tripback.backend.trip.recommendation;

import com.tripback.backend.trip.provider.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import static com.tripback.backend.trip.recommendation.RecommendationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class TripRecommendationServiceTests {
    RecommendationFixtures f;
    @BeforeEach void init() { f = new RecommendationFixtures(); }
    @AfterEach void close() { f.close(); }
    @Test void collectsRealProviderIdsForFakeModelWithoutInventingCandidateData() {
        var response = f.service.recommend(input());
        assertEquals("COMPLETED", response.recommendationStatus()); assertEquals(3, response.candidates().size());
        assertEquals(List.of("001", "002", "003"), f.model.lastPrompt.candidates().stream().map(TripRecommendationModel.PromptCandidate::placeId).toList());
        assertEquals("FAKE", response.aiProviderStatus().provider()); assertEquals("FAKE_MODEL", response.aiProviderStatus().invocation());
        assertEquals(1, f.model.calls.get());
        assertFalse(f.model.lastPrompt.preferences().has("origin")); assertFalse(f.model.lastPrompt.preferences().has("targetCenter"));
        assertTrue(response.candidates().stream().allMatch(c -> c.visitStatus().equals("NOT_VERIFIED") && c.data().dataMode().equals("DEMO_SIMULATED")));
    }
    @Test void generatesControlledInterestKeywordsAndCenteredFirstPageQueries() {
        f.service.recommend(input());
        assertEquals(List.of("천안 독립기념관 관광", "천안 독립기념관 맛집", "천안 독립기념관 카페"), f.places.calls.subList(0, 3).stream().map(PlaceSearchQuery::keyword).toList());
        for (var query : f.places.calls.subList(0, 3)) {
            assertEquals(input().targetCenter(), query.center()); assertEquals(20_000, query.radiusMeters());
            assertEquals(1, query.page()); assertEquals(15, query.size());
        }
        assertEquals(List.of("천안 독립기념관 관광"), CandidateCollector.keywords(input(REQUEST.replace("[\"CULTURE\",\"FOOD\",\"CAFE\"]", "[]"))));
    }
    @Test void removesOutsideRadiusAndRecordsReason() {
        f.places.handler = query -> found(place("001"), place("002"), place("outside", 37.5, 128.5));
        var response = f.service.recommend(input());
        assertEquals(2, response.candidates().size());
        assertTrue(response.excludedCandidates().stream().anyMatch(e -> e.reason().equals("OUTSIDE_TARGET_RADIUS") && e.id().value().equals("outside")));
        assertTrue(f.model.lastPrompt.candidates().stream().noneMatch(c -> c.placeId().equals("outside")));
    }
    @Test void deduplicatesIdsButNotIdenticalPlaceNamesAndPreservesFirstEvidence() {
        var response = f.service.recommend(input());
        assertEquals(3, response.candidates().size());
        assertTrue(response.excludedCandidates().stream().anyMatch(e -> e.reason().equals("DUPLICATE_ID")));
        assertTrue(response.candidates().stream().allMatch(c -> c.originalKeyword().equals("천안 독립기념관 관광")));
        assertTrue(response.candidates().stream().allMatch(c -> c.data().retrievedAt().equals(NOW)));
    }
    @Test void conflictingCoordinatesForSameIdAreRejected() {
        f.places.handler = query -> query.keyword().endsWith("관광") ? found(place("001"), place("002"))
                : found(place("001", 36.76, 127.26), place("002"));
        f.model.handler = prompt -> generation(plans(plan("one", 10, "002")));
        var response = f.service.recommend(input());
        assertEquals(1, response.candidates().size()); assertEquals("002", response.candidates().getFirst().id().value());
        assertTrue(response.excludedCandidates().stream().anyMatch(e -> e.reason().equals("CONFLICTING_PLACE_ID")));
    }
    @Test void invalidCoordinatesAndProviderResponsesNeverReachModel() {
        Wgs84Point point = mock(Wgs84Point.class); when(point.latitude()).thenReturn(Double.NaN); when(point.longitude()).thenReturn(127.25);
        var bad = new PlaceCandidate(new ProviderPlaceId("KAKAO_LOCAL", "invalid"), "bad", "", point, demo("KAKAO_LOCAL"));
        f.places.handler = query -> found(bad);
        var response = f.service.recommend(input());
        assertEquals("NO_CANDIDATES", response.recommendationStatus()); assertEquals(0, f.model.calls.get());
        assertTrue(response.excludedCandidates().stream().allMatch(e -> e.reason().equals("INVALID_CANDIDATE")));
    }
    @Test void providerNoResultsAuthenticationAndNetworkFailuresNeverBecomeDemoFallback() {
        for (var status : List.of(PlaceSearchResult.Status.NO_RESULTS, PlaceSearchResult.Status.UNAVAILABLE, PlaceSearchResult.Status.FAILED)) {
            f.places.handler = query -> new PlaceSearchResult("KAKAO_LOCAL", status, List.of(), status == PlaceSearchResult.Status.NO_RESULTS ? null
                    : status == PlaceSearchResult.Status.UNAVAILABLE ? ProviderFailure.of(ProviderFailure.Code.NOT_CONFIGURED) : ProviderFailure.fromHttpStatus(401), NOW);
            var response = f.service.recommend(input());
            assertEquals("NO_CANDIDATES", response.recommendationStatus()); assertTrue(response.candidates().isEmpty()); assertEquals(0, f.model.calls.get());
        }
    }
    @Test void twoPlansUseOriginalQueriesAndFullRoundTripNPlusOneLegs() {
        var response = f.service.recommend(input());
        assertEquals(2, response.alternatives().size()); assertEquals(7, f.places.calls.size()); assertEquals(6, f.routes.calls.size());
        for (var plan : response.alternatives()) {
            assertEquals("FEASIBLE", plan.feasibility()); assertEquals(3, plan.validation().legs().size());
            assertEquals(30L, plan.validation().totalTravelMinutes()); assertEquals(60L, plan.validation().totalStayMinutes());
            assertTrue(plan.validation().safetyBufferSatisfied()); assertTrue(plan.notices().contains("AI_REASON_UNVERIFIED"));
            assertEquals(List.of("DEMO_SIMULATED"), plan.dataModes());
            assertEquals("CONTAINS_DEMO", plan.validation().dataCoverage());
            assertEquals(2, plan.validation().legs().getLast().index());
            assertEquals(input().origin().latitude(), plan.validation().legs().getLast().destination().latitude());
        }
        assertEquals(List.of("001", "002"), response.alternatives().getFirst().orderedVisits().stream().map(v -> v.id().value()).toList());
        assertEquals(List.of("002", "001"), response.alternatives().getLast().orderedVisits().stream().map(v -> v.id().value()).toList());
        assertTrue(f.places.calls.subList(3, 7).stream().allMatch(q -> q.keyword().equals("천안 독립기념관 관광") && q.center().equals(input().targetCenter()) && q.radiusMeters() == 20_000));
    }
    @Test void unlistedIdRejectsOnlyBadPlanWithoutSubstitution() {
        f.model.handler = prompt -> generation(plans(plan("bad", 30, "invented"), plan("good", 30, "001")));
        var response = f.service.recommend(input());
        assertEquals("PARTIAL", response.recommendationStatus()); assertEquals(1, response.alternatives().size());
        assertEquals("UNLISTED_PLACE_ID", response.rejectedAlternatives().getFirst().reason());
        assertEquals(2, f.routes.calls.size());
    }
    @Test void duplicateVisitsInvalidStaysAndProviderNamespacesAreRejected() {
        for (String invalid : List.of(plan("duplicate", 30, "001", "001"), plan("negative", -1, "001"), plan("too-long", 241, "001"),
                plan("decimal", 30, "001").replace("\"stayMinutes\":30", "\"stayMinutes\":30.5"),
                plan("namespace", 30, "001").replace("KAKAO_LOCAL", "PLEOS"))) {
            f.model.handler = prompt -> generation(plans(invalid));
            var response = f.service.recommend(input());
            assertTrue(response.alternatives().isEmpty()); assertEquals(1, response.rejectedAlternatives().size());
        }
        assertTrue(f.routes.calls.isEmpty());
    }
    @Test void visitCountExtraCoordinatesAndMissingRequiredIdAreRejected() {
        for (String invalid : List.of(plan("zero", 10), plan("six", 10, "001", "002", "003", "001", "002", "003"),
                plan("coords", 10, "001").replace("\"stayMinutes\":10", "\"stayMinutes\":10,\"latitude\":37"))) {
            f.model.handler = prompt -> generation(plans(invalid)); assertTrue(f.service.recommend(input()).alternatives().isEmpty());
        }
        var required = input(REQUEST.replace("\"vehicleType\":", "\"mustVisitPlaceIds\":[{\"provider\":\"KAKAO_LOCAL\",\"value\":\"003\"}],\"vehicleType\":"));
        f.model.handler = prompt -> generation(plans(plan("missing required", 10, "001")));
        assertEquals("REQUIRED_VISIT_MISSING", f.service.recommend(required).rejectedAlternatives().getFirst().reason());
    }
    @Test void unavailableRequiredPlacePreventsPaidModelCall() {
        var input = input(REQUEST.replace("\"vehicleType\":", "\"mustVisitPlaceIds\":[{\"provider\":\"KAKAO_LOCAL\",\"value\":\"missing\"}],\"vehicleType\":"));
        var response = f.service.recommend(input);
        assertEquals("NO_ELIGIBLE_CANDIDATES", response.recommendationStatus()); assertEquals(0, f.model.calls.get());
    }
    @Test void canReturnOnePlanWithoutInventingAnother() {
        f.model.handler = prompt -> generation(plans(plan("single", 20, "001")));
        var response = f.service.recommend(input()); assertEquals("PARTIAL", response.recommendationStatus()); assertEquals(1, response.alternatives().size());
        assertTrue(response.rejectedAlternatives().isEmpty());
    }
    @Test void infeasiblePlansRetainValidationRatherThanBeingAdvertisedAsFeasible() {
        var response = f.service.recommend(input(REQUEST.replace("18:00:00", "09:00:00")));
        assertEquals("NO_FEASIBLE_ALTERNATIVES", response.recommendationStatus()); assertEquals(2, response.rejectedAlternatives().size());
        assertTrue(response.alternatives().isEmpty());
        assertTrue(response.rejectedAlternatives().stream().allMatch(p -> p.proposal().feasibility().equals("INFEASIBLE")));
    }
    @Test void missingSafetyBufferMakesEvenBeforeDeadlinePlanInfeasible() {
        var response = f.service.recommend(input(REQUEST.replace("18:00:00", "09:50:00")));
        assertEquals("VALIDATION_INFEASIBLE", response.rejectedAlternatives().getFirst().reason());
        var validation = response.rejectedAlternatives().getFirst().proposal().validation();
        assertTrue(validation.returnDeadlineMet()); assertFalse(validation.safetyBufferSatisfied());
    }
    @Test void unknownReturnEtaRemainsNullAndRejectedWithAllIndexes() {
        f.routes.handler = q -> q.destination().equals(input().origin()) ? new RoadRouteResult("KAKAO_MOBILITY", q, RoadRouteResult.Status.ETA_MISSING,
                null, null, ProviderFailure.of(ProviderFailure.Code.MISSING_ETA), NOW) : new RoadRouteResult("KAKAO_MOBILITY", q, RoadRouteResult.Status.FOUND, 600L, demo("KAKAO_MOBILITY"), null, NOW);
        var response = f.service.recommend(input());
        assertEquals(2, response.rejectedAlternatives().size()); assertEquals(6, f.routes.calls.size());
        var result = response.rejectedAlternatives().getFirst().proposal(); assertEquals("UNKNOWN", result.feasibility());
        assertNull(result.validation().predictedReturnAt()); assertNull(result.validation().remainingMarginMin());
        assertEquals(List.of(2), result.validation().missingEtaLegIndexes());
    }
    @Test void revalidationDoesNotAcceptPlaceThatMovedOutsideTargetArea() {
        f.places.handler = q -> f.model.calls.get() > 0 ? found(place("001", 39, 130), place("002")) : found(place("001"), place("002"));
        var response = f.service.recommend(input());
        assertTrue(response.alternatives().isEmpty()); assertTrue(f.routes.calls.isEmpty());
        assertEquals("VALIDATION_UNKNOWN", response.rejectedAlternatives().getFirst().reason());
    }
    @Test void oneWayLimitIsCheckedUsingActualFirstAndReturnEtas() {
        var response = f.service.recommend(input(REQUEST.replace("\"maximumOneWayMinutes\":120", "\"maximumOneWayMinutes\":5")));
        assertTrue(response.alternatives().isEmpty()); assertEquals("MAXIMUM_ONE_WAY_EXCEEDED", response.rejectedAlternatives().getFirst().reason());
        assertEquals("FEASIBLE", response.rejectedAlternatives().getFirst().proposal().validation().feasibility());
    }
    @Test void noModelConfigurationMeansNoExternalProviderCalls() {
        f.model.ready = false;
        var response = f.service.recommend(input()); assertEquals("AI_UNAVAILABLE", response.recommendationStatus());
        assertEquals("NOT_CONFIGURED", response.aiProviderStatus().status()); assertEquals(0, response.calls().modelGenerations());
        assertTrue(f.places.calls.isEmpty()); assertTrue(f.routes.calls.isEmpty());
    }
    @Test void modelTimeoutErrorsRefusalAndIncompleteResponsesNeverBecomeDemoRecommendations() {
        for (var status : List.of(TripRecommendationModel.Status.TIMEOUT, TripRecommendationModel.Status.HTTP_ERROR,
                TripRecommendationModel.Status.REFUSED, TripRecommendationModel.Status.INCOMPLETE)) {
            f.model.handler = prompt -> new TripRecommendationModel.Generation(status, TripRecommendationModel.Invocation.FAKE_MODEL, null);
            var response = f.service.recommend(input()); assertEquals("AI_FAILED", response.recommendationStatus());
            assertTrue(response.alternatives().isEmpty()); assertEquals(status.name(), response.aiProviderStatus().status());
        }
        assertTrue(f.routes.calls.isEmpty());
    }
    @Test void malformedOversizedDuplicateAndExtraModelJsonIsRejected() {
        for (String json : List.of("broken", "{\"plans\":[]}" + "x".repeat(33_000), "{\"plans\":[],\"plans\":[]}",
                "{\"plans\":[],\"newPlace\":\"001\"}", plans(plan("a", 30, "001"), plan("b", 30, "002"), plan("c", 30, "003")))) {
            f.model.handler = prompt -> generation(json); var response = f.service.recommend(input());
            assertEquals("INVALID_MODEL_OUTPUT", response.recommendationStatus()); assertTrue(response.alternatives().isEmpty());
        }
    }
    @Test void generatedContactsAndExceptionsAreSanitized(CapturedOutput output) {
        String secret = "synthetic-private-" + UUID.randomUUID();
        f.model.handler = prompt -> { throw new IllegalStateException(secret); };
        var response = f.service.recommend(input()); assertEquals("AI_FAILED", response.recommendationStatus());
        assertFalse(response.toString().contains(secret)); assertFalse(output.getAll().contains(secret));
        f.model.handler = prompt -> generation(plans(plan("contact@example.com", 30, "001")));
        assertEquals("UNSAFE_GENERATED_TEXT", f.service.recommend(input()).rejectedAlternatives().getFirst().reason());
        assertFalse(generation(secret).toString().contains(secret));
    }
    @Test void allEightSearchesAndTwoFiveStopPlansStayWithinProviderCaps() {
        f.places.handler = q -> found(place("001"), place("002"), place("003"), place("004"), place("005"));
        f.model.handler = prompt -> generation(plans(plan("five", 10, "001", "002", "003", "004", "005"), plan("reverse", 10, "005", "004", "003", "002", "001")));
        var request = input(REQUEST.replace("[\"CULTURE\",\"FOOD\",\"CAFE\"]", "[\"EXPERIENCE\",\"FOOD\",\"NATURE\",\"CAFE\",\"CULTURE\",\"SHOPPING\",\"SPA\",\"PHOTO\"]"));
        var response = f.service.recommend(request);
        assertEquals(18, response.calls().placeSearches()); assertEquals(12, response.calls().roadRoutes());
        assertEquals(1, response.calls().modelGenerations()); assertEquals(2, response.alternatives().size());
    }
    @Test void candidatePoolStopsAtThirtyAndRecordsUnperformedSearches() {
        f.places.handler = q -> found(IntStream.range(0, 15).mapToObj(i -> place(q.keyword() + "-" + i)).toArray(PlaceCandidate[]::new));
        f.model.handler = prompt -> generation(plans());
        var response = f.service.recommend(input());
        assertEquals(30, response.candidates().size()); assertEquals(2, f.places.calls.size());
        assertTrue(response.searches().stream().anyMatch(s -> s.status().equals("NOT_SEARCHED") && s.reason().equals("CANDIDATE_LIMIT")));
    }
    @Test void globalProcessingDeadlineInterruptsWorkAndPreventsFurtherCalls() {
        try (var shortRequest = new RecommendationFixtures(Duration.ofMillis(100))) {
            shortRequest.places.handler = q -> {
                try { Thread.sleep(5_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return found(place("001"));
            };
            var response = shortRequest.service.recommend(input());
            assertEquals("PROCESSING_TIMEOUT", response.recommendationStatus()); assertEquals(0, shortRequest.model.calls.get());
            assertEquals(1, shortRequest.places.calls.size());
        }
    }
    @Test void centeredRevalidationKeepsOriginalIdsWhenUnscopedKeywordWouldReturnAnotherRegion() {
        f.places.handler = q -> q.center() == null ? found(place("other-region", 39, 130)) : found(place("001"), place("002"));
        var response = f.service.recommend(input());
        assertEquals("COMPLETED", response.recommendationStatus());
        assertEquals(7, f.places.calls.size()); assertEquals(6, f.routes.calls.size());
        for (var query : f.places.calls) {
            assertEquals(input().targetCenter(), query.center()); assertEquals(20_000, query.radiusMeters());
            assertEquals(1, query.page()); assertEquals(15, query.size());
        }
        for (var alternative : response.alternatives()) {
            assertEquals(3, alternative.validation().legs().size());
            assertTrue(alternative.orderedVisits().stream().allMatch(v -> v.originalKeyword().equals("천안 독립기념관 관광")));
            assertEquals(alternative.orderedVisits().stream().map(v -> v.id().value()).toList(),
                    alternative.validation().visits().stream().map(v -> v.confirmedPlace().id().value()).toList());
            assertTrue(alternative.validation().visits().stream().allMatch(v -> v.identityStatus().equals("MATCHED_SELECTED_ID")));
        }
    }
    @Test void missingSelectedIdDuringRevalidationDoesNotSubstituteAnotherSearchHit() {
        f.places.handler = q -> f.model.calls.get() == 0 ? found(place("001"), place("002")) : found(place("other-region", 39, 130));
        var response = f.service.recommend(input());
        assertUnknownRevalidation(response);
        assertTrue(response.rejectedAlternatives().stream().flatMap(a -> a.proposal().validation().visits().stream())
                .allMatch(v -> v.reason().equals("SELECTED_PLACE_NOT_FOUND") && v.confirmedPlace() == null));
    }
    @Test void sameIdFromWrongProviderNamespaceIsRejectedDuringRevalidation() {
        var foreign = new PlaceCandidate(new ProviderPlaceId("OTHER", "001"), "가상 장소", "", input().targetCenter(), demo("OTHER"));
        f.places.handler = q -> f.model.calls.get() == 0 ? found(place("001"), place("002"))
                : new PlaceSearchResult("OTHER", PlaceSearchResult.Status.FOUND, List.of(foreign), null, NOW);
        var response = f.service.recommend(input());
        assertUnknownRevalidation(response);
        assertTrue(response.rejectedAlternatives().stream().flatMap(a -> a.proposal().validation().visits().stream())
                .allMatch(v -> v.failure().code().equals("INVALID_DATA")));
    }
    @Test void changedCoordinatesInsideRadiusAreRejectedRatherThanTrustingSameId() {
        f.places.handler = q -> f.model.calls.get() == 0 ? found(place("001"), place("002"))
                : found(place("001", 36.76, 127.26), place("002"));
        assertTrue(RegionDistance.contains(input().targetCenter(), place("001", 36.76, 127.26).coordinates()));
        assertUnknownRevalidation(f.service.recommend(input()));
    }
    @Test void duplicateSelectedIdsWithConflictingCoordinatesAreRejectedDuringRevalidation() {
        f.places.handler = q -> f.model.calls.get() == 0 ? found(place("001"), place("002"))
                : found(place("001"), place("001", 36.76, 127.26), place("002"));
        assertUnknownRevalidation(f.service.recommend(input()));
    }
    @Test void revalidationSearchFailuresRemainUnknownWithNoRouteOrReplacement() {
        for (var status : List.of(PlaceSearchResult.Status.NO_RESULTS, PlaceSearchResult.Status.UNAVAILABLE, PlaceSearchResult.Status.FAILED)) {
            try (var fixture = new RecommendationFixtures()) {
                fixture.places.handler = q -> fixture.model.calls.get() == 0 ? found(place("001"), place("002"))
                        : new PlaceSearchResult("KAKAO_LOCAL", status, List.of(), status == PlaceSearchResult.Status.NO_RESULTS ? null
                            : status == PlaceSearchResult.Status.UNAVAILABLE ? ProviderFailure.of(ProviderFailure.Code.NOT_CONFIGURED)
                            : ProviderFailure.fromHttpStatus(401), NOW);
                var response = fixture.service.recommend(input());
                assertTrue(response.alternatives().isEmpty()); assertEquals(2, response.rejectedAlternatives().size());
                assertTrue(response.rejectedAlternatives().stream().allMatch(a -> a.proposal().feasibility().equals("UNKNOWN")));
                assertTrue(fixture.routes.calls.isEmpty()); assertEquals(7, response.calls().placeSearches());
            }
        }
    }
    @Test void revalidationProviderExceptionRemainsUnknownWithoutRetry() {
        f.places.handler = q -> {
            if (f.model.calls.get() > 0) throw new IllegalStateException("synthetic-provider-error");
            return found(place("001"), place("002"));
        };
        var response = f.service.recommend(input());
        assertUnknownRevalidation(response);
        assertTrue(response.rejectedAlternatives().stream().flatMap(a -> a.proposal().validation().visits().stream())
                .allMatch(v -> v.reason().equals("PLACE_PROVIDER_EXCEPTION")));
    }
    private void assertUnknownRevalidation(RecommendationResponse response) {
        assertEquals("NO_FEASIBLE_ALTERNATIVES", response.recommendationStatus());
        assertTrue(response.alternatives().isEmpty()); assertEquals(2, response.rejectedAlternatives().size());
        assertTrue(response.rejectedAlternatives().stream().allMatch(a -> a.reason().equals("VALIDATION_UNKNOWN")
                && a.proposal().feasibility().equals("UNKNOWN") && a.proposal().validation().legs().size() == 3));
        assertEquals(7, response.calls().placeSearches()); assertEquals(0, response.calls().roadRoutes());
        assertEquals(1, response.calls().modelGenerations()); assertTrue(f.routes.calls.isEmpty());
    }
    @Test void regionDistanceHandlesBoundaryDatelineAndAntipodesConservatively() {
        assertEquals(0, RegionDistance.metres(new Wgs84Point(0, 0), new Wgs84Point(0, 0)));
        assertTrue(RegionDistance.contains(new Wgs84Point(0, 0), new Wgs84Point(0, .17)));
        assertFalse(RegionDistance.contains(new Wgs84Point(0, 0), new Wgs84Point(0, .19)));
        assertTrue(RegionDistance.contains(new Wgs84Point(0, 179.99), new Wgs84Point(0, -179.99)));
        assertFalse(RegionDistance.contains(new Wgs84Point(0, 0), new Wgs84Point(0, 180)));
    }
}
