package com.tripback.backend.places;

import com.tripback.backend.trip.provider.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import tools.jackson.databind.*;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "TRIPBACK_ENABLE_PLACE_SEARCH_API=true", "TRIPBACK_ENABLE_TRIP_VALIDATION_API=true"})
@Import(PlaceCandidatesApiTests.Fakes.class)
@ExtendWith(OutputCaptureExtension.class)
class PlaceCandidatesApiTests {
    static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
    @Autowired Environment environment;
    @Autowired ObjectMapper mapper;
    @Autowired FakeProvider fake;

    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {
        @Bean @Primary FakeProvider placeCandidatesFake() { return new FakeProvider(); }
    }
    static class FakeProvider implements PlaceSearchProvider, RoadRouteProvider {
        final List<PlaceSearchQuery> calls = new ArrayList<>();
        int routeCalls;
        Function<PlaceSearchQuery, PlaceSearchResult> handler;
        void reset() {
            calls.clear(); routeCalls = 0;
            handler = query -> result(PlaceSearchResult.Status.FOUND, null, candidate("0002"), candidate("0001"));
        }
        public PlaceSearchResult search(PlaceSearchQuery query) { calls.add(query); return handler.apply(query); }
        public RoadRouteResult route(RoadRouteQuery query) {
            routeCalls++;
            return new RoadRouteResult("KAKAO_MOBILITY", query, RoadRouteResult.Status.UNAVAILABLE, null, null,
                    ProviderFailure.of(ProviderFailure.Code.NOT_CONFIGURED), NOW);
        }
    }
    static PlaceCandidate candidate(String id) {
        return new PlaceCandidate(new ProviderPlaceId("KAKAO_LOCAL", id), "가상 후보 " + id, "가상 주소",
                new Wgs84Point(36.75, 127.25), new ProviderData("KAKAO_LOCAL", ProviderData.DataMode.DEMO_SIMULATED,
                ProviderData.Verification.UNVERIFIED, NOW.minusSeconds(60)));
    }
    static PlaceSearchResult result(PlaceSearchResult.Status status, ProviderFailure failure, PlaceCandidate... candidates) {
        return new PlaceSearchResult("KAKAO_LOCAL", status, List.of(candidates), failure, NOW);
    }
    @BeforeEach void reset() { fake.reset(); }

    HttpResponse<String> post(String path, HttpRequest.BodyPublisher body, String type) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port") + path))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", type).POST(body).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }
    HttpResponse<String> post(String body) throws Exception {
        return post("/api/v1/places/search", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8), "application/json; charset=UTF-8");
    }
    JsonNode search() throws Exception {
        var response = post("{\"keyword\":\"독립기념관 주차장\"}");
        assertEquals(200, response.statusCode()); return mapper.readTree(response.body());
    }
    void rejected(String body, int status) throws Exception {
        int calls = fake.calls.size(); var response = post(body);
        assertEquals(status, response.statusCode());
        assertEquals(status == 413 ? "REQUEST_TOO_LARGE" : "INVALID_REQUEST", mapper.readTree(response.body()).get("code").asString());
        assertEquals(calls, fake.calls.size()); assertEquals(0, fake.routeCalls);
    }
    void emptyOutcome(JsonNode root, String status) {
        assertEquals(status, root.get("status").asString());
        assertEquals(0, root.get("candidates").size()); assertEquals(0, root.get("dataModes").size());
    }

    @Test void returnsAllCandidatesInOriginalOrderWithoutAutomaticSelection() throws Exception {
        JsonNode root = search();
        assertEquals("FOUND", root.get("status").asString()); assertEquals("KAKAO_LOCAL", root.get("provider").asString());
        assertEquals(2, root.get("candidates").size());
        assertEquals("0002", root.get("candidates").get(0).get("id").get("value").asString());
        assertEquals("0001", root.get("candidates").get(1).get("id").get("value").asString());
        assertEquals(0, root.get("candidates").get(0).get("index").intValue());
        assertEquals(1, root.get("candidates").get(1).get("index").intValue());
        assertFalse(root.has("selectedId")); assertFalse(root.has("selectedCandidate"));
        assertEquals(1, fake.calls.size()); assertEquals(0, fake.routeCalls);
        assertTrue(root.get("notice").asString().contains("NO_AUTOMATIC_SELECTION"));
    }
    @Test void koreanKeywordIsUtf8AndQueryIsAlwaysFirstPageFifteen() throws Exception {
        JsonNode root = search();
        PlaceSearchQuery query = fake.calls.getFirst();
        assertEquals("독립기념관 주차장", query.keyword()); assertEquals(1, query.page()); assertEquals(15, query.size());
        assertNull(query.center()); assertNull(query.radiusMeters());
        assertEquals("가상 후보 0002", root.get("candidates").get(0).get("name").asString());
    }
    @Test void preservesCoordinatesNamespaceAndOpaqueIds() throws Exception {
        JsonNode candidate = search().get("candidates").get(0);
        assertEquals("KAKAO_LOCAL", candidate.get("id").get("provider").asString());
        assertEquals("0002", candidate.get("id").get("value").asString());
        assertEquals(36.75, candidate.get("coordinates").get("latitude").doubleValue());
        assertEquals(127.25, candidate.get("coordinates").get("longitude").doubleValue());
        assertEquals("가상 주소", candidate.get("address").asString());
    }
    @Test void keepsDemoProvenanceAndVisitabilityUnverified() throws Exception {
        JsonNode root = search(); JsonNode candidate = root.get("candidates").get(0);
        assertEquals("NOT_VERIFIED", candidate.get("visitStatus").asString());
        assertEquals("DEMO_SIMULATED", candidate.get("data").get("dataMode").asString());
        assertEquals("UNVERIFIED", candidate.get("data").get("verification").asString());
        assertEquals(NOW.minusSeconds(60).toString(), candidate.get("data").get("retrievedAt").asString());
        assertEquals(NOW.toString(), root.get("attemptedAt").asString());
        assertEquals("DEMO_SIMULATED", root.get("dataModes").get(0).asString());
    }
    @Test void permitsFifteenCandidatesWithoutDroppingAny() throws Exception {
        fake.handler = query -> result(PlaceSearchResult.Status.FOUND, null,
                IntStream.range(0, 15).mapToObj(i -> candidate("fixture-" + i)).toArray(PlaceCandidate[]::new));
        JsonNode root = search(); assertEquals(15, root.get("candidates").size());
        assertEquals(14, root.get("candidates").get(14).get("index").intValue());
    }
    @Test void emptySearchIsNoResultsNotProviderFailure() throws Exception {
        fake.handler = query -> result(PlaceSearchResult.Status.NO_RESULTS, null);
        JsonNode root = search(); emptyOutcome(root, "NO_RESULTS");
        assertTrue(root.get("failure").isNull()); assertTrue(root.get("reason").isNull());
    }
    @Test void missingCredentialsAreUnavailable() throws Exception {
        fake.handler = query -> result(PlaceSearchResult.Status.UNAVAILABLE, ProviderFailure.of(ProviderFailure.Code.NOT_CONFIGURED));
        JsonNode root = search(); emptyOutcome(root, "UNAVAILABLE");
        assertEquals("NOT_CONFIGURED", root.get("failure").get("code").asString());
        assertEquals("PROVIDER_UNAVAILABLE", root.get("reason").asString());
    }
    @Test void authenticationFailuresRemainSafeClassifiedFailures() throws Exception {
        for (int http : new int[]{401, 403}) {
            fake.handler = query -> result(PlaceSearchResult.Status.FAILED, ProviderFailure.fromHttpStatus(http));
            JsonNode root = search(); emptyOutcome(root, "FAILED");
            assertEquals("AUTHENTICATION_FAILED", root.get("failure").get("code").asString());
            assertEquals(http, root.get("failure").get("httpStatus").intValue());
        }
    }
    @Test void networkAndTimeoutFailuresRemainDistinct() throws Exception {
        for (var code : List.of(ProviderFailure.Code.CONNECTION_FAILED, ProviderFailure.Code.TIMEOUT)) {
            fake.handler = query -> result(PlaceSearchResult.Status.FAILED, ProviderFailure.of(code));
            JsonNode root = search(); emptyOutcome(root, "FAILED"); assertEquals(code.name(), root.get("failure").get("code").asString());
        }
    }
    @Test void rateLimitAndUpstreamServerErrorsDoNotRetry() throws Exception {
        for (int http : new int[]{429, 500}) {
            fake.handler = query -> result(PlaceSearchResult.Status.FAILED, ProviderFailure.fromHttpStatus(http));
            int before = fake.calls.size(); JsonNode root = search(); emptyOutcome(root, "FAILED");
            assertEquals(before + 1, fake.calls.size());
            assertEquals(http == 429 ? "RATE_LIMITED" : "HTTP_ERROR", root.get("failure").get("code").asString());
        }
    }
    @Test void invalidUpstreamJsonClassificationIsNotAValidEmptySearch() throws Exception {
        fake.handler = query -> result(PlaceSearchResult.Status.FAILED, ProviderFailure.of(ProviderFailure.Code.INVALID_DATA));
        JsonNode root = search(); emptyOutcome(root, "FAILED"); assertEquals("INVALID_DATA", root.get("failure").get("code").asString());
    }
    @Test void nullWrongNamespaceAndDuplicateResponsesFailClosed() throws Exception {
        List<PlaceSearchResult> responses = Arrays.asList(null,
                new PlaceSearchResult("OTHER", PlaceSearchResult.Status.NO_RESULTS, List.of(), null, NOW),
                result(PlaceSearchResult.Status.FOUND, null, candidate("same"), candidate("same")));
        for (PlaceSearchResult response : responses) {
            fake.handler = query -> response;
            JsonNode root = search(); emptyOutcome(root, "FAILED");
            assertEquals("INVALID_PROVIDER_RESPONSE", root.get("reason").asString());
            assertEquals("INVALID_DATA", root.get("failure").get("code").asString());
        }
    }
    @Test void excessiveProviderCandidatesAreRejectedRatherThanTruncated() throws Exception {
        fake.handler = query -> result(PlaceSearchResult.Status.FOUND, null,
                IntStream.range(0, 16).mapToObj(i -> candidate("fixture-" + i)).toArray(PlaceCandidate[]::new));
        emptyOutcome(search(), "FAILED");
    }
    @Test void providerExceptionCannotLeakSyntheticCredentialOrErrorBody(CapturedOutput output) throws Exception {
        String secret = "synthetic-private-" + UUID.randomUUID();
        fake.handler = query -> { throw new IllegalStateException("Authorization: KakaoAK " + secret + " raw upstream error body"); };
        JsonNode root = search(); emptyOutcome(root, "FAILED");
        assertEquals("PROVIDER_EXCEPTION", root.get("reason").asString());
        assertTrue(root.get("failure").isNull()); assertFalse(root.toString().contains(secret));
        assertFalse(root.toString().contains("Authorization")); assertFalse(output.getAll().contains(secret));
        assertFalse(output.getAll().contains("raw upstream error body"));
    }
    @Test void invalidKeywordValuesNeverCallProvider() throws Exception {
        for (String value : List.of("null", "12", "true", "[]", "{}", "\"\"", "\" \"", "\" leading\"", "\"trailing \"", "\"line\\nfeed\"", "\"" + "x".repeat(201) + "\"", "\"\\ud800\"")) {
            rejected("{\"keyword\":" + value + "}", 400);
        }
    }
    @Test void malformedDuplicateTrailingAndUnsupportedInputNeverCallsProvider(CapturedOutput output) throws Exception {
        for (String body : List.of("", "{}", "[]", "null", "broken", "{\"keyword\":\"a\",\"keyword\":\"b\"}",
                "{\"keyword\":\"a\"} {}", "{\"keyword\":\"a\",\"page\":2}", "{\"keyword\":\"a\",\"size\":16}",
                "{\"keyword\":\"a\",\"constraints\":{\"required\":true}}")) rejected(body, 400);
        String secret = "synthetic-input-private-" + UUID.randomUUID();
        var response = post("{\"keyword\":\"a\",\"apiKey\":\"" + secret + "\"}");
        assertEquals(400, response.statusCode()); assertFalse(response.body().contains(secret)); assertFalse(output.getAll().contains(secret));
        assertTrue(fake.calls.isEmpty());
    }
    @Test void declaredAndChunkedOversizedBodyAreRejectedBeforeProvider() throws Exception {
        String oversized = "{\"keyword\":\"a\"}" + " ".repeat(4096);
        rejected(oversized, 413);
        var response = post("/api/v1/places/search", HttpRequest.BodyPublishers.ofInputStream(
                () -> new ByteArrayInputStream(oversized.getBytes(StandardCharsets.UTF_8))), "application/json");
        assertEquals(413, response.statusCode()); assertTrue(fake.calls.isEmpty());
    }
    @Test void malformedUtf8AndUnsupportedCharsetNeverCallProvider() throws Exception {
        var malformed = post("/api/v1/places/search", HttpRequest.BodyPublishers.ofByteArray(new byte[]{(byte) 0xc3, 0x28}), "application/json");
        assertEquals(400, malformed.statusCode());
        for (String charset : List.of("UTF-16", "ISO-8859-1", "not-a-real-charset")) {
            var response = post("/api/v1/places/search", HttpRequest.BodyPublishers.ofString("{\"keyword\":\"a\"}"), "application/json; charset=" + charset);
            assertEquals(415, response.statusCode());
        }
        assertTrue(fake.calls.isEmpty());
    }
    @Test void maximumKeywordAndNoCharsetAreAccepted() throws Exception {
        var response = post("/api/v1/places/search", HttpRequest.BodyPublishers.ofString("{\"keyword\":\"" + "가".repeat(200) + "\"}"), "application/json");
        assertEquals(200, response.statusCode()); assertEquals(200, fake.calls.getFirst().keyword().length());
    }
    @Test void candidateProjectionContainsOnlyDocumentedFields() throws Exception {
        JsonNode root = search();
        assertEquals(Set.of("provider", "status", "page", "size", "candidates", "dataModes", "attemptedAt", "reason", "failure", "notice"), Set.copyOf(root.propertyNames()));
        assertEquals(Set.of("index", "id", "name", "address", "coordinates", "data", "visitStatus"), Set.copyOf(root.get("candidates").get(0).propertyNames()));
    }
    @Test void demoPlanAndValidationEndpointsKeepTheirContracts() throws Exception {
        String plan = """
                {"region":"fixture","departAt":"2026-11-07T08:00:00+09:00","returnDeadline":"2026-11-07T18:00:00+09:00",
                 "safetyBufferMin":30,"companions":{"type":"SOLO"},"interests":[],"vehicleType":"EV","maximumOneWayMinutes":120}
                """;
        var response = post("/api/v1/trips/plan", HttpRequest.BodyPublishers.ofString(plan), "application/json");
        assertEquals(200, response.statusCode()); assertEquals("DEMO_SIMULATED", mapper.readTree(response.body()).get("dataMode").asString());
        assertTrue(fake.calls.isEmpty());
        fake.handler = query -> result(PlaceSearchResult.Status.NO_RESULTS, null);
        String validation = """
                {"origin":{"latitude":37,"longitude":127},"departAt":"2026-11-07T08:00:00+09:00",
                 "returnDeadline":"2026-11-07T18:00:00+09:00","safetyBufferMin":30,
                 "visits":[{"keyword":"fixture","selectedId":{"provider":"KAKAO_LOCAL","value":"fixture"},"stayMinutes":30}]}
                """;
        response = post("/api/v1/trips/validate", HttpRequest.BodyPublishers.ofString(validation), "application/json");
        assertEquals(200, response.statusCode()); assertEquals("UNKNOWN", mapper.readTree(response.body()).get("feasibility").asString());
        assertEquals(0, fake.routeCalls);
    }
}
