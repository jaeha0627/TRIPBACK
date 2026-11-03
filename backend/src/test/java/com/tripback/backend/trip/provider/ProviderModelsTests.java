package com.tripback.backend.trip.provider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProviderModelsTests {
    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
    private static final String PROVIDER = "TEST_ONLY";
    private final ProviderData demo = new ProviderData(PROVIDER, ProviderData.DataMode.DEMO_SIMULATED,
            ProviderData.Verification.UNVERIFIED, NOW);
    private final Wgs84Point point = new Wgs84Point(37, 127);

    private PlaceCandidate candidate() {
        return new PlaceCandidate(new ProviderPlaceId(PROVIDER, "fixture-001"), "테스트 전용 가상 장소",
                "테스트 전용 가상 주소", point, demo);
    }

    private PlaceSearchQuery query() { return new PlaceSearchQuery("테스트", null, null, 1, 15); }

    @Test
    void validatesFiniteCoordinateRangesAndAcceptsBoundaries() {
        assertEquals(-90, new Wgs84Point(-90, -180).latitude());
        assertEquals(180, new Wgs84Point(90, 180).longitude());
        for (double latitude : new double[] { -90.001, 90.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY }) {
            assertThrows(IllegalArgumentException.class, () -> new Wgs84Point(latitude, 0));
        }
        for (double longitude : new double[] { -180.001, 180.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY }) {
            assertThrows(IllegalArgumentException.class, () -> new Wgs84Point(0, longitude));
        }
        assertEquals(37, point.latitude());
        assertEquals(127, point.longitude());
    }

    @Test
    void opaquePlaceIdsStayProviderScopedAndAreNotPleosPoiIds() {
        ProviderPlaceId kakao = new ProviderPlaceId("KAKAO_LOCAL", "00123");
        assertEquals("00123", kakao.value());
        assertNotEquals(kakao, new ProviderPlaceId("PLEOS", "00123"));
        assertNotEquals(kakao, new ProviderPlaceId("OTHER_PROVIDER", "00123"));
        for (String id : new String[] { null, "", " ", " fixture-001" }) {
            assertThrows(IllegalArgumentException.class, () -> new ProviderPlaceId(PROVIDER, id));
        }
        assertThrows(IllegalArgumentException.class, () -> new ProviderPlaceId("", "fixture-001"));
    }

    @Test
    void candidateKeepsIdentityCoordinatesAndProvenanceWithoutClaimingVisitability() {
        PlaceCandidate place = candidate();
        assertEquals(point, place.coordinates());
        assertEquals(demo, place.data());
        assertEquals(PlaceCandidate.VisitStatus.NOT_VERIFIED, place.visitStatus());
        // Constructor metadata is a test declaration, not an actual external lookup.
        ProviderData declared = new ProviderData(PROVIDER, ProviderData.DataMode.VERIFIED_LIVE,
                ProviderData.Verification.VALIDATED_PROVIDER_DATA, NOW);
        PlaceCandidate declaredPlace = new PlaceCandidate(place.id(), place.name(), place.address(), point, declared);
        assertEquals(PlaceCandidate.VisitStatus.NOT_VERIFIED, declaredPlace.visitStatus());
    }

    @Test
    void candidateRejectsMissingRequiredFieldsAndConflictingProviderNamespace() {
        assertThrows(IllegalArgumentException.class, () -> new PlaceCandidate(null, "test", "", point, demo));
        assertThrows(IllegalArgumentException.class, () -> new PlaceCandidate(candidate().id(), " ", "", point, demo));
        assertThrows(IllegalArgumentException.class, () -> new PlaceCandidate(candidate().id(), "test", null, point, demo));
        assertThrows(IllegalArgumentException.class, () -> new PlaceCandidate(candidate().id(), "test", "", null, demo));
        assertThrows(IllegalArgumentException.class, () -> new PlaceCandidate(new ProviderPlaceId("OTHER", "fixture-001"), "test", "", point, demo));
    }

    @Test
    void sourceLabelsCannotPromoteDemoOrUnvalidatedData() {
        assertThrows(IllegalArgumentException.class, () -> new ProviderData(PROVIDER,
                ProviderData.DataMode.DEMO_SIMULATED, ProviderData.Verification.VALIDATED_PROVIDER_DATA, NOW));
        for (ProviderData.DataMode mode : List.of(ProviderData.DataMode.VERIFIED_LIVE, ProviderData.DataMode.VERIFIED_CACHED)) {
            assertThrows(IllegalArgumentException.class, () -> new ProviderData(PROVIDER, mode, ProviderData.Verification.UNVERIFIED, NOW));
        }
        assertThrows(IllegalArgumentException.class, () -> new ProviderData(PROVIDER,
                ProviderData.DataMode.DEMO_SIMULATED, ProviderData.Verification.UNVERIFIED, null));
    }

    @Test
    void searchCriteriaValidatePageSizeAndCenteredRadius() {
        assertEquals(15, query().size());
        new PlaceSearchQuery("test", point, 20000, 45, 15);
        new PlaceSearchQuery("test", point, 0, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> new PlaceSearchQuery("", null, null, 1, 15));
        assertThrows(IllegalArgumentException.class, () -> new PlaceSearchQuery("test", null, 100, 1, 15));
        assertThrows(IllegalArgumentException.class, () -> new PlaceSearchQuery("test", point, -1, 1, 15));
        assertThrows(IllegalArgumentException.class, () -> new PlaceSearchQuery("test", point, 20001, 1, 15));
        assertThrows(IllegalArgumentException.class, () -> new PlaceSearchQuery("test", null, null, 0, 15));
        assertThrows(IllegalArgumentException.class, () -> new PlaceSearchQuery("test", null, null, 46, 15));
        assertThrows(IllegalArgumentException.class, () -> new PlaceSearchQuery("test", null, null, 1, 16));
    }

    @Test
    void emptySearchResultsAreDifferentFromUnavailableProviders() {
        PlaceSearchProvider emptyStub = query -> new PlaceSearchResult(PROVIDER, PlaceSearchResult.Status.NO_RESULTS,
                List.of(), null, NOW);
        PlaceSearchResult empty = emptyStub.search(query());
        PlaceSearchResult unavailable = UnavailableProviders.placeSearch(Clock.fixed(NOW, ZoneOffset.UTC)).search(query());
        assertEquals(PlaceSearchResult.Status.NO_RESULTS, empty.status());
        assertNull(empty.failure());
        assertEquals(PlaceSearchResult.Status.UNAVAILABLE, unavailable.status());
        assertEquals(ProviderFailure.Code.ADAPTER_NOT_IMPLEMENTED, unavailable.failure().code());
        assertTrue(unavailable.candidates().isEmpty());
        assertEquals(NOW, unavailable.attemptedAt());
    }

    @Test
    void defaultRouteProviderIsUnavailableWithNoFabricatedEtaOrData() {
        RoadRouteQuery query = new RoadRouteQuery(point, new Wgs84Point(38, 128));
        RoadRouteResult result = UnavailableProviders.roadRoute(Clock.fixed(NOW, ZoneOffset.UTC)).route(query);
        assertEquals(RoadRouteResult.Status.UNAVAILABLE, result.status());
        assertEquals(query, result.query());
        assertNull(result.durationSeconds());
        assertNull(result.data());
        assertTrue(result.failure().unavailable());
    }

    @Test
    void inconsistentSearchStatesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PlaceSearchResult(PROVIDER, PlaceSearchResult.Status.FOUND, List.of(), null, NOW));
        assertThrows(IllegalArgumentException.class, () -> new PlaceSearchResult(PROVIDER, PlaceSearchResult.Status.NO_RESULTS, List.of(candidate()), null, NOW));
        assertThrows(IllegalArgumentException.class, () -> new PlaceSearchResult(PROVIDER, PlaceSearchResult.Status.UNAVAILABLE, List.of(), null, NOW));
        assertThrows(IllegalArgumentException.class, () -> new PlaceSearchResult("OTHER", PlaceSearchResult.Status.FOUND, List.of(candidate()), null, NOW));
        assertThrows(IllegalArgumentException.class, () -> new PlaceSearchResult(PROVIDER, PlaceSearchResult.Status.FAILED, List.of(), ProviderFailure.of(ProviderFailure.Code.NOT_CONFIGURED), NOW));
    }

    @Test
    void searchCandidatesAreImmutableSnapshots() {
        List<PlaceCandidate> candidates = new ArrayList<>(List.of(candidate()));
        PlaceSearchResult result = new PlaceSearchResult(PROVIDER, PlaceSearchResult.Status.FOUND, candidates, null, NOW);
        candidates.clear();
        assertEquals(1, result.candidates().size());
        assertThrows(UnsupportedOperationException.class, () -> result.candidates().clear());
    }

    @Test
    void httpAuthenticationRateLimitServerErrorsAndTimeoutsRemainDistinct() {
        assertEquals(ProviderFailure.Code.AUTHENTICATION_FAILED, ProviderFailure.fromHttpStatus(401).code());
        assertEquals(ProviderFailure.Code.AUTHENTICATION_FAILED, ProviderFailure.fromHttpStatus(403).code());
        assertFalse(ProviderFailure.fromHttpStatus(401).retryable());
        assertEquals(ProviderFailure.Code.RATE_LIMITED, ProviderFailure.fromHttpStatus(429).code());
        assertTrue(ProviderFailure.fromHttpStatus(429).retryable());
        assertEquals(ProviderFailure.Code.HTTP_ERROR, ProviderFailure.fromHttpStatus(500).code());
        assertTrue(ProviderFailure.fromHttpStatus(500).retryable());
        assertFalse(ProviderFailure.fromHttpStatus(400).retryable());
        assertTrue(ProviderFailure.of(ProviderFailure.Code.TIMEOUT).retryable());
        assertTrue(ProviderFailure.of(ProviderFailure.Code.CONNECTION_FAILED).retryable());
        assertFalse(ProviderFailure.of(ProviderFailure.Code.INVALID_DATA).retryable());
        ProviderFailure limited = new ProviderFailure(ProviderFailure.Code.RATE_LIMITED, 429, Duration.ofSeconds(30));
        assertEquals(Duration.ofSeconds(30), limited.retryAfter());
    }

    @Test
    void notConfiguredIsUnavailableRatherThanAnEmptySuccessfulSearch() {
        PlaceSearchResult result = new PlaceSearchResult(PROVIDER, PlaceSearchResult.Status.UNAVAILABLE,
                List.of(), ProviderFailure.of(ProviderFailure.Code.NOT_CONFIGURED), NOW);
        assertEquals(PlaceSearchResult.Status.UNAVAILABLE, result.status());
        assertNotNull(result.failure());
        assertFalse(result.failure().retryable());
    }

    @Test
    void invalidFailureMetadataIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ProviderFailure.fromHttpStatus(200));
        assertThrows(IllegalArgumentException.class, () -> ProviderFailure.fromHttpStatus(600));
        assertThrows(IllegalArgumentException.class, () -> new ProviderFailure(ProviderFailure.Code.HTTP_ERROR, 401, null));
        assertThrows(IllegalArgumentException.class, () -> new ProviderFailure(ProviderFailure.Code.TIMEOUT, null, Duration.ofSeconds(-1)));
    }

    @Test
    void routeResultsDoNotConflateNoRouteMissingEtaFailureAndUnavailable() {
        RoadRouteQuery query = new RoadRouteQuery(point, new Wgs84Point(38, 128));
        RoadRouteResult noRoute = new RoadRouteResult(PROVIDER, query, RoadRouteResult.Status.NO_ROUTE, null, null, null, NOW);
        RoadRouteResult missing = new RoadRouteResult(PROVIDER, query, RoadRouteResult.Status.ETA_MISSING,
                null, null, ProviderFailure.of(ProviderFailure.Code.MISSING_ETA), NOW);
        RoadRouteResult failure = new RoadRouteResult(PROVIDER, query, RoadRouteResult.Status.FAILED,
                null, null, ProviderFailure.of(ProviderFailure.Code.TIMEOUT), NOW);
        assertNotEquals(noRoute.status(), missing.status());
        assertNotEquals(missing.status(), failure.status());
        for (RoadRouteResult result : List.of(noRoute, missing, failure)) {
            assertNull(result.durationSeconds());
            assertNull(result.data());
        }
    }

    @Test
    void inconsistentOrNegativeRouteEtasAreRejected() {
        RoadRouteQuery query = new RoadRouteQuery(point, new Wgs84Point(38, 128));
        assertThrows(IllegalArgumentException.class, () -> new RoadRouteQuery(null, point));
        assertThrows(IllegalArgumentException.class, () -> new RoadRouteResult(PROVIDER, query, RoadRouteResult.Status.FOUND, null, demo, null, NOW));
        assertThrows(IllegalArgumentException.class, () -> new RoadRouteResult(PROVIDER, query, RoadRouteResult.Status.FOUND, -1L, demo, null, NOW));
        assertThrows(IllegalArgumentException.class, () -> new RoadRouteResult(PROVIDER, query, RoadRouteResult.Status.FOUND, 10L, null, null, NOW));
        assertThrows(IllegalArgumentException.class, () -> new RoadRouteResult(PROVIDER, query, RoadRouteResult.Status.UNAVAILABLE, 0L, null, ProviderFailure.of(ProviderFailure.Code.NOT_CONFIGURED), NOW));
        assertThrows(IllegalArgumentException.class, () -> new RoadRouteResult("OTHER", query, RoadRouteResult.Status.FOUND, 10L, demo, null, NOW));
    }
}
