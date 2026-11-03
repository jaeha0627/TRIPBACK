package com.tripback.backend.trip.validationapi;

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
import tools.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.function.BiFunction;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "TRIPBACK_ENABLE_TRIP_VALIDATION_API=true")
@Import(TripValidationApiTests.Fakes.class)
@ExtendWith(OutputCaptureExtension.class)
class TripValidationApiTests {
    static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
    static final String DEPART = "2026-11-07T08:00:00+09:00";
    @Autowired Environment environment;
    @Autowired ObjectMapper mapper;
    @Autowired FakeProviders fake;

    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {
        @Bean @Primary FakeProviders fakeProviders() { return new FakeProviders(); }
    }
    static class FakeProviders implements PlaceSearchProvider, RoadRouteProvider {
        final List<PlaceSearchQuery> searches = new ArrayList<>();
        final List<RoadRouteQuery> routes = new ArrayList<>();
        BiFunction<Integer, PlaceSearchQuery, PlaceSearchResult> searchHandler;
        BiFunction<Integer, RoadRouteQuery, RoadRouteResult> routeHandler;
        void reset() {
            searches.clear(); routes.clear();
            searchHandler = (i, q) -> new PlaceSearchResult("KAKAO_LOCAL", PlaceSearchResult.Status.FOUND,
                    List.of(candidate(99), candidate(Integer.parseInt(q.keyword().substring(8)))), null, NOW);
            routeHandler = (i, q) -> new RoadRouteResult("KAKAO_MOBILITY", q, RoadRouteResult.Status.FOUND,
                    600L, demo("KAKAO_MOBILITY"), null, NOW);
        }
        public PlaceSearchResult search(PlaceSearchQuery query) {
            int i = searches.size(); searches.add(query); return searchHandler.apply(i, query);
        }
        public RoadRouteResult route(RoadRouteQuery query) {
            int i = routes.size(); routes.add(query); return routeHandler.apply(i, query);
        }
    }
    static ProviderData demo(String provider) {
        return new ProviderData(provider, ProviderData.DataMode.DEMO_SIMULATED, ProviderData.Verification.UNVERIFIED, NOW);
    }
    static PlaceCandidate candidate(int i) {
        return new PlaceCandidate(new ProviderPlaceId("KAKAO_LOCAL", "fixture-" + i), "Synthetic place " + i,
                "Synthetic address", new Wgs84Point(36 + i * .01, 126 + i * .01), demo("KAKAO_LOCAL"));
    }
    @BeforeEach void reset() { fake.reset(); }

    String request(int count) {
        String visits = java.util.stream.IntStream.range(0, count).mapToObj(i ->
                "{\"keyword\":\"keyword-" + i + "\",\"selectedId\":{\"provider\":\"KAKAO_LOCAL\",\"value\":\"fixture-" + i + "\"},\"stayMinutes\":60}")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"origin\":{\"latitude\":37.1,\"longitude\":127.1},\"departAt\":\"" + DEPART
                + "\",\"returnDeadline\":\"2026-11-07T18:00:00+09:00\",\"safetyBufferMin\":30,\"visits\":[" + visits + "]}";
    }
    HttpResponse<String> post(String path, String body) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port") + path))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    JsonNode validate(String body) throws Exception {
        HttpResponse<String> response = post("/api/v1/trips/validate", body);
        assertEquals(200, response.statusCode());
        return mapper.readTree(response.body());
    }
    void rejected(String body) throws Exception {
        int searches = fake.searches.size(), routes = fake.routes.size();
        HttpResponse<String> response = post("/api/v1/trips/validate", body);
        assertEquals(400, response.statusCode());
        assertEquals("INVALID_REQUEST", mapper.readTree(response.body()).get("code").asString());
        assertEquals(searches, fake.searches.size()); assertEquals(routes, fake.routes.size());
    }
    void unknown(JsonNode result, int... missing) {
        assertEquals("UNKNOWN", result.get("feasibility").asString());
        for (String field : List.of("predictedReturnAt", "remainingMarginMin", "returnDeadlineMet", "safetyBufferSatisfied", "totalTravelMinutes")) {
            assertTrue(result.get(field).isNull());
        }
        assertEquals(Arrays.stream(missing).boxed().toList(), mapper.convertValue(result.get("missingEtaLegIndexes"), List.class));
        assertEquals("INCOMPLETE", result.get("dataCoverage").asString());
    }
    void roundTrip(int n) throws Exception {
        JsonNode result = validate(request(n));
        assertEquals("FEASIBLE", result.get("feasibility").asString());
        assertEquals(n, result.get("visits").size()); assertEquals(n + 1, result.get("legs").size());
        assertEquals(n, fake.searches.size()); assertEquals(n + 1, fake.routes.size());
        for (int i = 0; i < n; i++) {
            JsonNode visit = result.get("visits").get(i);
            assertEquals(i, visit.get("index").intValue());
            assertEquals("fixture-" + i, visit.get("requestedId").get("value").asString());
            assertEquals("fixture-" + i, visit.get("confirmedPlace").get("id").get("value").asString());
            assertEquals("MATCHED_SELECTED_ID", visit.get("identityStatus").asString());
            assertEquals("NOT_VERIFIED", visit.get("visitStatus").asString());
        }
        for (int i = 0; i <= n; i++) {
            JsonNode leg = result.get("legs").get(i);
            assertEquals(i, leg.get("index").intValue()); assertEquals(600, leg.get("durationSeconds").intValue());
            assertEquals("FOUND", leg.get("status").asString());
            assertEquals("DEMO_SIMULATED", leg.get("data").get("dataMode").asString());
            assertEquals(NOW.toString(), leg.get("data").get("retrievedAt").asString());
            Wgs84Point origin = i == 0 ? new Wgs84Point(37.1, 127.1) : candidate(i - 1).coordinates();
            Wgs84Point destination = i == n ? new Wgs84Point(37.1, 127.1) : candidate(i).coordinates();
            assertEquals(new RoadRouteQuery(origin, destination), fake.routes.get(i));
        }
        assertEquals((n + 1) * 10, result.get("totalTravelMinutes").intValue());
        assertEquals(n * 60, result.get("totalStayMinutes").intValue());
        assertEquals("CONTAINS_DEMO", result.get("dataCoverage").asString());
        assertEquals("DEMO_SIMULATED", result.get("dataModes").get(0).asString());
        assertTrue(result.get("notice").asString().contains("RETURN_NOT_GUARANTEED"));
        assertFalse(result.has("response"));
    }
    @Test void oneVisitRoundTrip() throws Exception { roundTrip(1); }
    @Test void threeVisitsPreserveOrder() throws Exception { roundTrip(3); }
    @Test void fiveVisitsIncludeSixthReturnLeg() throws Exception { roundTrip(5); }
    @Test void enoughMarginIsMathematicallyFeasible() throws Exception {
        JsonNode result = validate(request(1));
        assertEquals("2026-11-07T09:20+09:00", result.get("predictedReturnAt").asString());
        assertEquals(520, result.get("remainingMarginMin").intValue());
        assertTrue(result.get("safetyBufferSatisfied").booleanValue());
    }
    @Test void deadlineOverrunIsInfeasible() throws Exception {
        JsonNode result = validate(request(1).replace("18:00:00", "09:19:00"));
        assertEquals("INFEASIBLE", result.get("feasibility").asString());
        assertFalse(result.get("returnDeadlineMet").booleanValue());
    }
    @Test void inadequateSafetyBufferIsInfeasible() throws Exception {
        JsonNode result = validate(request(1).replace("18:00:00", "09:49:00"));
        assertEquals("INFEASIBLE", result.get("feasibility").asString());
        assertTrue(result.get("returnDeadlineMet").booleanValue());
        assertFalse(result.get("safetyBufferSatisfied").booleanValue());
    }
    void missingEta(int index) {
        var normal = fake.routeHandler;
        fake.routeHandler = (i, q) -> i == index ? new RoadRouteResult("KAKAO_MOBILITY", q,
                RoadRouteResult.Status.ETA_MISSING, null, null, ProviderFailure.of(ProviderFailure.Code.MISSING_ETA), NOW) : normal.apply(i, q);
    }
    @Test void missingReturnEtaRemainsUnknown() throws Exception {
        missingEta(3); JsonNode result = validate(request(3)); unknown(result, 3);
        assertEquals("ETA_MISSING", result.get("legs").get(3).get("reason").asString()); assertEquals(4, fake.routes.size());
    }
    @Test void missingIntermediateEtaKeepsIndexes() throws Exception {
        missingEta(1); JsonNode result = validate(request(3)); unknown(result, 1);
        assertEquals(3, result.get("legs").get(3).get("index").intValue());
    }
    @Test void selectedPlaceMismatchNeverSubstitutes() throws Exception {
        JsonNode result = validate(request(1).replace("\"value\":\"fixture-0\"", "\"value\":\"missing\"")); unknown(result, 0, 1);
        assertEquals("SELECTED_PLACE_NOT_FOUND", result.get("visits").get(0).get("reason").asString());
        assertTrue(result.get("visits").get(0).get("confirmedPlace").isNull()); assertTrue(fake.routes.isEmpty());
    }
    @Test void searchNoResultsKeepsVisit() throws Exception {
        fake.searchHandler = (i, q) -> new PlaceSearchResult("KAKAO_LOCAL", PlaceSearchResult.Status.NO_RESULTS, List.of(), null, NOW);
        JsonNode result = validate(request(1)); unknown(result, 0, 1);
        assertEquals("PLACE_NO_RESULTS", result.get("visits").get(0).get("reason").asString());
    }
    @Test void searchApiAuthenticationFailureIsNormalUnknown() throws Exception {
        fake.searchHandler = (i, q) -> new PlaceSearchResult("KAKAO_LOCAL", PlaceSearchResult.Status.FAILED, List.of(), ProviderFailure.fromHttpStatus(401), NOW);
        JsonNode result = validate(request(1)); unknown(result, 0, 1);
        assertEquals("AUTHENTICATION_FAILED", result.get("visits").get(0).get("failure").get("code").asString());
    }
    @Test void routeApiFailureIsNormalUnknown() throws Exception {
        fake.routeHandler = (i, q) -> new RoadRouteResult("KAKAO_MOBILITY", q, RoadRouteResult.Status.FAILED, null, null, ProviderFailure.fromHttpStatus(500), NOW);
        JsonNode result = validate(request(1)); unknown(result, 0, 1);
        assertEquals("HTTP_ERROR", result.get("legs").get(0).get("failure").get("code").asString());
    }
    @Test void noRouteIsDistinctFromMissingEta() throws Exception {
        fake.routeHandler = (i, q) -> new RoadRouteResult("KAKAO_MOBILITY", q, RoadRouteResult.Status.NO_ROUTE, null, null, null, NOW);
        JsonNode result = validate(request(1)); unknown(result, 0, 1);
        assertEquals("NO_ROUTE", result.get("legs").get(0).get("status").asString());
    }
    @Test void missingPlaceCredentialsAreUnavailableNotSuccess() throws Exception {
        fake.searchHandler = (i, q) -> new PlaceSearchResult("KAKAO_LOCAL", PlaceSearchResult.Status.UNAVAILABLE, List.of(), ProviderFailure.of(ProviderFailure.Code.NOT_CONFIGURED), NOW);
        JsonNode result = validate(request(1)); unknown(result, 0, 1);
        assertEquals("NOT_CONFIGURED", result.get("visits").get(0).get("failure").get("code").asString());
    }
    @Test void missingRouteCredentialsAreUnavailableNotSuccess() throws Exception {
        fake.routeHandler = (i, q) -> new RoadRouteResult("KAKAO_MOBILITY", q, RoadRouteResult.Status.UNAVAILABLE, null, null, ProviderFailure.of(ProviderFailure.Code.NOT_CONFIGURED), NOW);
        JsonNode result = validate(request(1)); unknown(result, 0, 1);
        assertEquals("NOT_CONFIGURED", result.get("legs").get(1).get("failure").get("code").asString());
    }
    @Test void providerExceptionTextIsNotReturnedOrLogged(CapturedOutput output) throws Exception {
        String secret = "synthetic-private-" + UUID.randomUUID();
        fake.routeHandler = (i, q) -> { throw new IllegalStateException(secret); };
        JsonNode result = validate(request(1)); unknown(result, 0, 1);
        assertFalse(result.toString().contains(secret)); assertFalse(output.getAll().contains(secret));
    }
    @Test void invalidTimesAndMissingOffsetsNeverCallProviders() throws Exception {
        for (String value : List.of("2026-11-07T08:00:00", "2026-02-30T08:00:00+09:00", "wrong", "2026-11-07T08:00:00+25:00")) {
            rejected(request(1).replace(DEPART, value));
        }
        rejected(request(1).replace("18:00:00", "08:00:00")); rejected(request(1).replace("18:00:00", "07:59:00"));
    }
    @Test void invalidNumbersTypesAndCoordinatesNeverCallProviders() throws Exception {
        for (String value : List.of("-1", "1.5", "1.0", "\"60\"", "null", "true", "{}", "9223372036854775808", "153722867280912931")) {
            rejected(request(1).replace("\"stayMinutes\":60", "\"stayMinutes\":" + value));
        }
        for (String value : List.of("0", "20", "-30", "30.0", "\"30\"", "null")) {
            rejected(request(1).replace("\"safetyBufferMin\":30", "\"safetyBufferMin\":" + value));
        }
        for (String value : List.of("91", "-91", "NaN", "1e9999", "\"37.1\"", "null", "true")) {
            rejected(request(1).replace("\"latitude\":37.1", "\"latitude\":" + value));
        }
        rejected(request(1).replace("127.1", "181"));
    }
    @Test void requiredFieldsAndNestedNullsNeverCallProviders() throws Exception {
        for (String field : List.of("origin", "departAt", "returnDeadline", "safetyBufferMin", "visits")) {
            ObjectNode node = (ObjectNode) mapper.readTree(request(1)); node.remove(field); rejected(node.toString());
            node = (ObjectNode) mapper.readTree(request(1)); node.putNull(field); rejected(node.toString());
        }
        for (String field : List.of("keyword", "selectedId", "stayMinutes")) {
            ObjectNode node = (ObjectNode) mapper.readTree(request(1)); ((ObjectNode) node.get("visits").get(0)).remove(field); rejected(node.toString());
        }
        rejected(request(1).replace("\"provider\":\"KAKAO_LOCAL\"", "\"provider\":null"));
        rejected(request(1).replace("\"value\":\"fixture-0\"", "\"value\":null"));
        rejected(request(1).replace("\"longitude\":127.1", "\"longitude\":null"));
    }
    @Test void namespaceDuplicateIdsAndVisitLimitsAreRejected() throws Exception {
        rejected(request(1).replace("KAKAO_LOCAL", "PLEOS"));
        rejected(request(2).replace("fixture-1", "fixture-0")); rejected(request(0)); rejected(request(6));
    }
    @Test void unsupportedConstraintsAtAnyLevelAreRejected() throws Exception {
        rejected(request(1).replace("\"origin\":", "\"constraints\":{\"budget\":1},\"origin\":"));
        rejected(request(1).replace("\"latitude\":", "\"name\":\"unsupported\",\"latitude\":"));
        rejected(request(1).replace("\"keyword\":", "\"requiredStop\":true,\"keyword\":"));
        rejected(request(1).replace("\"provider\":", "\"extra\":1,\"provider\":"));
    }
    @Test void malformedAmbiguousOrWrongJsonStructureIsRejected(CapturedOutput output) throws Exception {
        for (String body : List.of("", "null", "[]", "{}", "broken", request(1) + " {}", request(1).replace("\"stayMinutes\":60", "\"stayMinutes\":60,\"stayMinutes\":10"),
                request(1).replace("\"visits\":[", "\"visits\":{\"wrong\":["), request(1).replace("\"keyword-0\"", "42"))) rejected(body);
        String sensitive = "not-valid-json-" + UUID.randomUUID(); rejected(sensitive); assertFalse(output.getAll().contains(sensitive));
    }
    @Test void zeroStayAndAllSupportedBuffersAreAccepted() throws Exception {
        for (int buffer : new int[]{15, 30, 45}) {
            JsonNode result = validate(request(1).replace("\"stayMinutes\":60", "\"stayMinutes\":0").replace("\"safetyBufferMin\":30", "\"safetyBufferMin\":" + buffer));
            assertEquals("FEASIBLE", result.get("feasibility").asString()); assertEquals(0, result.get("totalStayMinutes").intValue());
        }
    }
    @Test void excessiveProviderDurationIsUnknown() throws Exception {
        fake.routeHandler = (i, q) -> new RoadRouteResult("KAKAO_MOBILITY", q, RoadRouteResult.Status.FOUND, Long.MAX_VALUE, demo("KAKAO_MOBILITY"), null, NOW);
        JsonNode result = validate(request(1)); unknown(result, 0, 1);
        assertEquals("DURATION_OVERFLOW", result.get("legs").get(0).get("reason").asString());
        assertTrue(result.get("legs").get(0).get("durationSeconds").isNull());
    }
    @Test void aggregateCalculationOverflowHasExplicitReasonAndNullTotals() throws Exception {
        fake.routeHandler = (i, q) -> new RoadRouteResult("KAKAO_MOBILITY", q, RoadRouteResult.Status.FOUND, Long.MAX_VALUE / 2, demo("KAKAO_MOBILITY"), null, NOW);
        JsonNode result = validate(request(1));
        assertEquals("UNKNOWN", result.get("feasibility").asString());
        assertEquals("CALCULATION_OVERFLOW", result.get("calculationFailure").asString());
        assertTrue(result.get("predictedReturnAt").isNull()); assertTrue(result.get("remainingMarginMin").isNull());
    }
    @Test void inputOnlyAggregateOrTimestampOverflowIsRejectedBeforeProviders() throws Exception {
        rejected(request(1).replace("\"stayMinutes\":60", "\"stayMinutes\":153722867280912930"));
        rejected(request(2).replace("\"stayMinutes\":60", "\"stayMinutes\":100000000000000000"));
    }
    @Test void demoPlanStillWorksWhileValidationApiIsEnabled() throws Exception {
        String body = """
            {"region":"fixture","departAt":"2026-11-07T08:00:00+09:00","returnDeadline":"2026-11-07T18:00:00+09:00",
             "safetyBufferMin":30,"companions":{"type":"SOLO"},"interests":[],"vehicleType":"EV","maximumOneWayMinutes":120}
            """;
        HttpResponse<String> response = post("/api/v1/trips/plan", body);
        assertEquals(200, response.statusCode()); JsonNode result = mapper.readTree(response.body());
        assertEquals("DEMO_SIMULATED", result.get("dataMode").asString());
        assertEquals(2, result.get("alternatives").size()); assertTrue(fake.searches.isEmpty()); assertTrue(fake.routes.isEmpty());
    }
}
