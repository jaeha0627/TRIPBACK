package com.tripback.backend.trip.recommendation;

import com.tripback.backend.trip.provider.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.system.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import static com.tripback.backend.trip.recommendation.RecommendationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "TRIPBACK_ENABLE_AI_RECOMMENDATION_API=true")
@Import(TripRecommendationApiTests.Fakes.class)
@ExtendWith(OutputCaptureExtension.class)
class TripRecommendationApiTests {
    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {
        @Bean @Primary FakePlaces recommendationPlaces() { return new FakePlaces(); }
        @Bean @Primary FakeRoutes recommendationRoutes() { return new FakeRoutes(); }
        @Bean @Primary FakeModel recommendationModel() { return new FakeModel(); }
    }
    @Autowired FakePlaces places;
    @Autowired FakeRoutes routes;
    @Autowired FakeModel model;
    @Autowired ObjectMapper mapper;
    @Autowired Environment environment;
    @BeforeEach void reset() {
        places.calls.clear(); routes.calls.clear(); model.calls.set(0); model.ready = true;
        places.handler = q -> found(place("001"), place("002"), place("003"));
        routes.handler = q -> new RoadRouteResult("KAKAO_MOBILITY", q, RoadRouteResult.Status.FOUND, 600L, demo("KAKAO_MOBILITY"), null, NOW);
        model.handler = prompt -> generation(twoPlans());
    }
    HttpResponse<String> post(String path, HttpRequest.BodyPublisher body, String contentType) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", contentType).POST(body).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }
    HttpResponse<String> post(String body) throws Exception {
        return post("/api/v1/trips/recommend", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8), "application/json; charset=UTF-8");
    }
    void rejected(String body) throws Exception {
        var response = post(body); assertEquals(400, response.statusCode()); assertEquals("INVALID_REQUEST", mapper.readTree(response.body()).get("code").asString());
        assertTrue(places.calls.isEmpty()); assertTrue(routes.calls.isEmpty()); assertEquals(0, model.calls.get());
    }
    @Test void enabledLoopbackApiReturnsTwoValidatedFakeProposalsAndExplicitSourceLabels() throws Exception {
        var response = post(REQUEST); assertEquals(200, response.statusCode()); var root = mapper.readTree(response.body());
        assertEquals("COMPLETED", root.get("recommendationStatus").asString()); assertEquals(2, root.get("alternatives").size());
        assertEquals("FAKE_MODEL", root.get("aiProviderStatus").get("invocation").asString());
        assertEquals("FAKE", root.get("aiProviderStatus").get("provider").asString());
        var alternative = root.get("alternatives").get(0);
        assertEquals("FEASIBLE", alternative.get("feasibility").asString());
        assertEquals("DEMO_SIMULATED", alternative.get("dataModes").get(0).asString());
        assertEquals(3, alternative.get("validation").get("legs").size()); assertEquals(2, alternative.get("orderedVisits").size());
        assertEquals(3, root.get("candidates").size()); assertTrue(root.has("limitations"));
        assertFalse(root.has("rawModelResponse")); assertFalse(root.has("apiKey")); assertEquals(1, model.calls.get());
    }
    @Test void oneInvalidAlternativeIsRejectedWithoutSubstitution() throws Exception {
        model.handler = prompt -> generation(plans(plan("invalid", 30, "invented"), plan("valid", 30, "001")));
        var root = mapper.readTree(post(REQUEST).body());
        assertEquals(1, root.get("alternatives").size()); assertEquals("PARTIAL", root.get("recommendationStatus").asString());
        assertEquals("UNLISTED_PLACE_ID", root.get("rejectedAlternatives").get(0).get("reason").asString());
    }
    @Test void allInfeasibleAndUnknownResultsRemainVisibleInRejectedAlternatives() throws Exception {
        var root = mapper.readTree(post(REQUEST.replace("18:00:00", "09:00:00")).body());
        assertEquals("NO_FEASIBLE_ALTERNATIVES", root.get("recommendationStatus").asString());
        assertEquals("INFEASIBLE", root.get("rejectedAlternatives").get(0).get("proposal").get("feasibility").asString());
        routes.handler = q -> new RoadRouteResult("KAKAO_MOBILITY", q, RoadRouteResult.Status.NO_ROUTE, null, null, null, NOW);
        root = mapper.readTree(post(REQUEST).body());
        var rejected = root.get("rejectedAlternatives").get(0).get("proposal");
        assertEquals("UNKNOWN", rejected.get("feasibility").asString());
        assertTrue(rejected.get("validation").get("predictedReturnAt").isNull());
    }
    @Test void missingModelConfigurationPerformsNoSearchOrModelCall() throws Exception {
        model.ready = false; var response = post(REQUEST); assertEquals(200, response.statusCode());
        var root = mapper.readTree(response.body()); assertEquals("AI_UNAVAILABLE", root.get("recommendationStatus").asString());
        assertEquals("NOT_CALLED", root.get("aiProviderStatus").get("invocation").asString());
        assertTrue(places.calls.isEmpty()); assertEquals(0, model.calls.get());
    }
    @Test void preferencesAreBoundedHintsAndNotClaimedVerifiedFacts() throws Exception {
        String request = REQUEST.replace("\"vehicleType\":", "\"budgetPerPersonKrw\":50000,\"walkingPreference\":\"LOW\",\"parkingPreferences\":[\"FREE\"],\"amenities\":[\"ACCESSIBLE\"],\"preferIndoorWhenRaining\":true,\"vehicleType\":");
        var root = mapper.readTree(post(request).body());
        assertEquals(50000, model.lastPrompt.preferences().get("budgetPerPersonKrw").intValue());
        assertTrue(root.get("conditionStatus").get("parkingPreferences").asString().contains("NOT_VERIFIED"));
        assertTrue(root.get("conditionStatus").get("vehicleType").asString().contains("NOT_VEHICLE_SPECIFIC"));
    }
    @Test void malformedJsonUnknownFieldsAndDuplicateKeysNeverCallProviders(CapturedOutput output) throws Exception {
        for (String body : java.util.List.of("", "null", "[]", "{}", "broken", REQUEST + " {}", REQUEST.replace("\"vehicleType\":", "\"constraints\":{\"required\":true},\"vehicleType\":"),
                REQUEST.replace("\"safetyBufferMin\":30", "\"safetyBufferMin\":30,\"safetyBufferMin\":15"))) rejected(body);
        String sensitive = "synthetic-input-sensitive-" + java.util.UUID.randomUUID(); rejected(sensitive);
        assertFalse(output.getAll().contains(sensitive));
    }
    @Test void missingNullFieldsInvalidCoordinatesAndDatesNeverCallProviders() throws Exception {
        for (String field : RecommendationRequest.REQUIRED) {
            var root = (ObjectNode) mapper.readTree(REQUEST); root.remove(field); rejected(root.toString());
            root = (ObjectNode) mapper.readTree(REQUEST); root.putNull(field); rejected(root.toString());
        }
        rejected(REQUEST.replace("36.75", "91")); rejected(REQUEST.replace("127.25", "181"));
        rejected(REQUEST.replace("2026-11-07T08:00:00+09:00", "2026-11-07T08:00:00"));
        rejected(REQUEST.replace("2026-11-07T08:00:00+09:00", "2026-02-30T08:00:00+09:00"));
        rejected(REQUEST.replace("18:00:00", "08:00:00")); rejected(REQUEST.replace("18:00:00", "07:00:00"));
    }
    @Test void enumTypesBufferAndRequiredPlaceConstraintsAreStrict() throws Exception {
        for (String value : java.util.List.of("30.0", "\"30\"", "0", "20", "true", "9223372036854775808")) rejected(REQUEST.replace("\"safetyBufferMin\":30", "\"safetyBufferMin\":" + value));
        rejected(REQUEST.replace("\"SOLO\"", "\"FAMILY\"")); rejected(REQUEST.replace("\"EV\"", "\"UNKNOWN\""));
        rejected(REQUEST.replace("\"CULTURE\"", "\"UNSUPPORTED\"")); rejected(REQUEST.replace("\"CAFE\"", "\"FOOD\""));
        rejected(REQUEST.replace("\"maximumOneWayMinutes\":120", "\"maximumOneWayMinutes\":181"));
        rejected(REQUEST.replace("\"vehicleType\":", "\"mustVisitPlaceIds\":[{\"provider\":\"PLEOS\",\"value\":\"id\"}],\"vehicleType\":"));
    }
    @Test void declaredAndChunkedRequestSizeAndEncodingAreBounded() throws Exception {
        String oversized = REQUEST + " ".repeat(RecommendationRequest.MAX_BYTES);
        assertEquals(413, post(oversized).statusCode());
        var response = post("/api/v1/trips/recommend", HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(oversized.getBytes(StandardCharsets.UTF_8))), "application/json");
        assertEquals(413, response.statusCode());
        assertEquals(400, post("/api/v1/trips/recommend", HttpRequest.BodyPublishers.ofByteArray(new byte[]{(byte) 0xc3, 0x28}), "application/json").statusCode());
        assertEquals(415, post("/api/v1/trips/recommend", HttpRequest.BodyPublishers.ofString(REQUEST), "application/json; charset=UTF-16").statusCode());
        assertTrue(places.calls.isEmpty()); assertEquals(0, model.calls.get());
    }
    @Test void modelFailureDoesNotExposePrivateResponseOrInventRecommendations(CapturedOutput output) throws Exception {
        String secret = "synthetic-model-private-" + java.util.UUID.randomUUID();
        model.handler = prompt -> { throw new IllegalStateException(secret); };
        var response = post(REQUEST); var root = mapper.readTree(response.body());
        assertEquals("AI_FAILED", root.get("recommendationStatus").asString());
        assertEquals(0, root.get("alternatives").size()); assertFalse(response.body().contains(secret)); assertFalse(output.getAll().contains(secret));
    }
    @Test void demoPlanStillHasOriginalContractAndNoRecommendationCalls() throws Exception {
        String plan = """
            {"region":"fixture","departAt":"2026-11-07T08:00:00+09:00","returnDeadline":"2026-11-07T18:00:00+09:00",
             "safetyBufferMin":30,"companions":{"type":"SOLO"},"interests":[],"vehicleType":"EV","maximumOneWayMinutes":120}
            """;
        var response = post("/api/v1/trips/plan", HttpRequest.BodyPublishers.ofString(plan), "application/json");
        assertEquals(200, response.statusCode()); var root = mapper.readTree(response.body());
        assertEquals("DEMO_SIMULATED", root.get("dataMode").asString()); assertEquals(2, root.get("alternatives").size());
        assertEquals(0, model.calls.get()); assertTrue(places.calls.isEmpty());
    }
}
