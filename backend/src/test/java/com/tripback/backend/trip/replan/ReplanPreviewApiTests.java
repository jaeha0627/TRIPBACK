package com.tripback.backend.trip.replan;

import org.junit.jupiter.api.*;
import com.tripback.backend.trip.recommendation.TripRecommendationModel;
import static org.mockito.Mockito.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.tripback.backend.trip.replan.ReplanApiFixtures.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "TRIPBACK_ENABLE_TRIP_VALIDATION_API=true", "TRIPBACK_ENABLE_REPLAN_PREVIEW_API=true"})
@Import(ReplanPreviewApiTests.Fakes.class)
class ReplanPreviewApiTests {
    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {
        @Bean @Primary Providers fakeReplanProviders() { return new Providers(); }
        @Bean @Primary TripRecommendationModel previewModelGuard() { return mock(TripRecommendationModel.class); }
        @Bean MutableClock fixtureReplanClock() { return new MutableClock(); }
        @Bean @Primary ReplanPlanStore fixtureReplanStore(MutableClock clock) {
            return new ReplanPlanStore(clock, Duration.ofMinutes(30), 256);
        }
    }
    @Autowired Environment environment;
    @Autowired ObjectMapper mapper;
    @Autowired Providers providers;
    @Autowired MutableClock clock;
    @Autowired ReplanPlanStore store;
    @Autowired TripRecommendationModel model;
    @AfterEach void modelWasNeverCalled() { verifyNoInteractions(model); }
    @BeforeEach void reset() { providers.reset(); clock.at = NOW; }
    private String validationBody(Instant deadline) {
        return "{\"origin\":{\"latitude\":37,\"longitude\":127},\"departAt\":\"" + NOW.minusSeconds(7200)
                + "\",\"returnDeadline\":\"" + deadline + "\",\"safetyBufferMin\":30,\"visits\":["
                + "{\"keyword\":\"0\",\"selectedId\":{\"provider\":\"KAKAO_LOCAL\",\"value\":\"replan-fixture-0\"},\"stayMinutes\":30},"
                + "{\"keyword\":\"1\",\"selectedId\":{\"provider\":\"KAKAO_LOCAL\",\"value\":\"replan-fixture-1\"},\"stayMinutes\":20}]}";
    }
    private HttpResponse<String> post(String path, byte[] body, String contentType, boolean register) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:"
                    + environment.getRequiredProperty("local.server.port") + path)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", contentType);
            if (register) builder.header("X-Tripback-Register-Replan", "true");
            return client.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    private HttpResponse<String> validate(Instant deadline, boolean register) throws Exception {
        return post("/api/v1/trips/validate", validationBody(deadline).getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/json", register);
    }
    private String registered(Instant deadline) throws Exception {
        var response = validate(deadline, true); assertEquals(200, response.statusCode());
        assertEquals("REGISTERED", response.headers().firstValue("X-Tripback-Plan-Registration").orElseThrow());
        assertEquals("1", response.headers().firstValue("X-Tripback-Plan-Revision").orElseThrow());
        assertEquals(NOW.plusSeconds(1800).toString(), response.headers().firstValue("X-Tripback-Plan-Expires-At").orElseThrow());
        assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        return response.headers().firstValue("X-Tripback-Plan-Ref").orElseThrow();
    }
    private ObjectNode request(String reference, long total) {
        var n = mapper.createObjectNode(); n.put("planRef", reference); n.put("revision", 1); n.put("currentVisitIndex", 0);
        n.put("phase", "STAYING"); n.put("observedAt", clock.instant().toString());
        n.put("elapsedStaySeconds", 600); n.put("requestedTotalStayMinutes", total); return n;
    }
    private HttpResponse<String> preview(ObjectNode body) throws Exception {
        int searches = providers.searches.get(), routes = providers.routes.get();
        var response = post("/api/v1/trips/replan-preview", mapper.writeValueAsBytes(body), "application/json", false);
        assertEquals(searches, providers.searches.get()); assertEquals(routes, providers.routes.get());
        assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        return response;
    }
    private JsonNode ok(ObjectNode body) throws Exception {
        var response = preview(body); assertEquals(200, response.statusCode(), response.body()); return mapper.readTree(response.body());
    }
    private void error(ObjectNode body, int status, String code) throws Exception {
        var response = preview(body); assertEquals(status, response.statusCode());
        assertEquals(code, mapper.readTree(response.body()).get("code").asString());
    }
    @Test void normalPreviewExposesOnlyCachedEvidenceAndIsNotApproval() throws Exception {
        String ref = registered(NOW.plusSeconds(10800)); var result = ok(request(ref, 50));
        assertEquals("PREVIEW_ONLY", result.get("status").asString()); assertEquals(1, result.get("baseRevision").asInt());
        assertEquals("CLIENT_REPORTED_NOT_VERIFIED", result.get("progressVerification").asString());
        assertEquals(NOW.plusSeconds(3600), Instant.parse(result.get("before").get("predictedReturnAt").asString()));
        assertEquals(NOW.plusSeconds(4800), Instant.parse(result.get("after").get("predictedReturnAt").asString()));
        assertEquals("FEASIBLE", result.get("after").get("feasibility").asString());
        assertEquals(2, result.get("orderedVisits").size()); assertEquals(2, result.get("requiredVisits").size());
        for (var leg : result.get("after").get("remainingLegs")) {
            assertEquals("VERIFIED_CACHED", leg.get("dataMode").asString()); assertTrue(leg.get("cached").asBoolean());
            assertEquals(NOW, Instant.parse(leg.get("retrievedAt").asString()));
        }
        assertFalse(result.toString().contains("VERIFIED_LIVE"));
    }
    @Test void shorteningAndSecondRounding() throws Exception {
        String ref = registered(NOW.plusSeconds(10800)); var body = request(ref, 20); body.put("elapsedStaySeconds", 601);
        var result = ok(body); assertEquals(10, result.get("after").get("currentRemainingStayMinutes").asLong());
        assertEquals(NOW.plusSeconds(3000), Instant.parse(result.get("after").get("predictedReturnAt").asString()));
    }
    @Test void exactDeadlineAndBufferBoundaries() throws Exception {
        var exact = ok(request(registered(NOW.plusSeconds(3600)), 30)).get("after");
        assertTrue(exact.get("returnDeadlineMet").asBoolean()); assertFalse(exact.get("safetyBufferSatisfied").asBoolean());
        assertEquals("INFEASIBLE", exact.get("feasibility").asString());
        var buffer = ok(request(registered(NOW.plusSeconds(5400)), 30)).get("after");
        assertEquals("FEASIBLE", buffer.get("feasibility").asString());
        var shortfall = ok(request(registered(NOW.plusSeconds(5399)), 30)).get("after");
        assertEquals("INFEASIBLE", shortfall.get("feasibility").asString());
    }
    @Test void pastDeadlineReturnsInfeasibleNotBadRequest() throws Exception {
        var result = ok(request(registered(NOW.minusSeconds(1)), 30)).get("after");
        assertEquals("INFEASIBLE", result.get("feasibility").asString()); assertFalse(result.get("returnDeadlineMet").asBoolean());
    }
    @Test void staleEtaIsUnknownWithOriginalTimestamp() throws Exception {
        providers.retrievedAt = NOW.minusSeconds(901);
        var result = ok(request(registered(NOW.plusSeconds(10800)), 50)).get("after");
        assertEquals("UNKNOWN", result.get("feasibility").asString()); assertTrue(result.get("reasons").toString().contains("STALE_ETA"));
        assertEquals(NOW.minusSeconds(901), Instant.parse(result.get("remainingLegs").get(0).get("retrievedAt").asString()));
    }
    @Test void serverClockPreventsBackdatingToFreshenEta() throws Exception {
        String ref = registered(NOW.plusSeconds(10800)); clock.at = NOW.plusSeconds(901);
        var body = request(ref, 50); body.put("observedAt", NOW.toString());
        error(body, 400, "INVALID_OBSERVATION_TIME");
        body.put("observedAt", clock.instant().toString()); assertEquals("UNKNOWN", ok(body).get("after").get("feasibility").asString());
    }
    @Test void unknownAndExpiredReferencesUseExplicitError() throws Exception {
        error(request("A".repeat(43), 30), 404, "PLAN_NOT_FOUND_OR_EXPIRED");
        String ref = registered(NOW.plusSeconds(10800)); clock.at = NOW.plusSeconds(1800);
        error(request(ref, 30), 404, "PLAN_NOT_FOUND_OR_EXPIRED");
    }
    @Test void revisionMismatchIsConflict() throws Exception {
        var body = request(registered(NOW.plusSeconds(10800)), 30); body.put("revision", 2);
        error(body, 409, "PLAN_REVISION_MISMATCH");
    }
    @Test void repeatedPreviewAndErrorsNeverMutateOriginalPlan() throws Exception {
        String ref = registered(NOW.plusSeconds(10800)); var entry = store.find(ref); var original = entry.plan();
        var first = ok(request(ref, 50)); ok(request(ref, 20));
        var invalid = request(ref, 50); invalid.put("revision", 2); error(invalid, 409, "PLAN_REVISION_MISMATCH");
        assertEquals(first, ok(request(ref, 50))); assertSame(original, store.find(ref).plan());
        assertEquals(List.of(30L, 20L), original.stayMinutes()); assertEquals(HOME, original.home());
        assertEquals(Set.of(id(0), id(1)), original.requiredVisits());
    }
    @Test void invalidProgressPhaseIndexDurationAndObservationAreErrors() throws Exception {
        String ref = registered(NOW.plusSeconds(10800));
        var n = request(ref, 30); n.put("phase", "TRAVELING"); error(n, 400, "INVALID_REQUEST");
        n = request(ref, 30); n.put("currentVisitIndex", 2); error(n, 400, "INVALID_CURRENT_VISIT");
        n = request(ref, 30); n.put("elapsedStaySeconds", -1); error(n, 400, "INVALID_REQUEST");
        error(request(ref, 9), 400, "TOTAL_STAY_BELOW_ELAPSED");
        error(request(ref, 1441), 400, "INVALID_REQUEST");
        n = request(ref, 30); n.put("observedAt", NOW.plusSeconds(1).toString()); error(n, 400, "INVALID_OBSERVATION_TIME");
        n = request(ref, 30); n.put("elapsedStaySeconds", 7201); error(n, 400, "INVALID_STAY_OBSERVATION");
    }
    @Test void clientEtaOrderAndSourceAreRejected() throws Exception {
        String ref = registered(NOW.plusSeconds(10800));
        for (String field : List.of("eta", "dataMode", "orderedVisits", "requiredVisits", "home", "returnDeadline")) {
            var n = request(ref, 30); n.put(field, "forged"); error(n, 400, "INVALID_REQUEST");
        }
    }
    @Test void strictJsonRejectsDuplicateTrailingFloatStringMissingAndNull() throws Exception {
        String ref = registered(NOW.plusSeconds(10800)); String valid = request(ref, 30).toString();
        for (String bad : List.of(valid.substring(0, valid.length() - 1) + ",\"revision\":1}", valid + "{}", "[]", "null", "{}")) {
            var response = post("/api/v1/trips/replan-preview", bad.getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/json", false);
            assertEquals(400, response.statusCode()); assertEquals("INVALID_REQUEST", mapper.readTree(response.body()).get("code").asString());
        }
        var n = request(ref, 30); n.put("revision", "1"); error(n, 400, "INVALID_REQUEST");
        n = request(ref, 30); n.put("elapsedStaySeconds", 1.5); error(n, 400, "INVALID_REQUEST");
        n = request(ref, 30); n.putNull("phase"); error(n, 400, "INVALID_REQUEST");
    }
    @Test void oversizedUnsupportedCharsetAndMalformedUtf8AreRejected() throws Exception {
        assertEquals(413, post("/api/v1/trips/replan-preview", new byte[8193], "application/json", false).statusCode());
        assertEquals(415, post("/api/v1/trips/replan-preview", new byte[0], "application/json;charset=UTF-16", false).statusCode());
        assertEquals(400, post("/api/v1/trips/replan-preview", new byte[]{(byte) 0xc3, 0x28}, "application/json", false).statusCode());
    }
    @Test void demoAndMissingEtaValidationDoNotIssueUsableReferences() throws Exception {
        providers.demo = true; var response = validate(NOW.plusSeconds(10800), true);
        assertEquals(200, response.statusCode()); assertEquals("NOT_ELIGIBLE", response.headers().firstValue("X-Tripback-Plan-Registration").orElseThrow());
        assertTrue(response.headers().firstValue("X-Tripback-Plan-Ref").isEmpty());
        providers.demo = false; providers.missing = true; response = validate(NOW.plusSeconds(10800), true);
        assertEquals("UNKNOWN", mapper.readTree(response.body()).get("feasibility").asString());
        assertEquals("NOT_ELIGIBLE", response.headers().firstValue("X-Tripback-Plan-Registration").orElseThrow());
        assertTrue(response.headers().firstValue("X-Tripback-Plan-Ref").isEmpty());
    }
    @Test void registrationIsOptInAndExistingValidationJsonIsUnchanged() throws Exception {
        var old = validate(NOW.plusSeconds(10800), false); var registered = validate(NOW.plusSeconds(10800), true);
        assertEquals(mapper.readTree(old.body()), mapper.readTree(registered.body()));
        assertTrue(old.headers().firstValue("X-Tripback-Plan-Ref").isEmpty());
    }
    @Test void observationLatencyDoesNotAddElapsedTimeTwice() throws Exception {
        String ref = registered(NOW.plusSeconds(10800)); clock.at = NOW.plusSeconds(60);
        var body = request(ref, 50); body.put("observedAt", NOW.toString());
        var r = ok(body); assertEquals(39, r.get("after").get("currentRemainingStayMinutes").asLong());
        assertEquals(NOW.plusSeconds(4800), Instant.parse(r.get("after").get("predictedReturnAt").asString()));
    }
}
