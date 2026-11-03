package com.tripback.backend.trip;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TripPlanApiTests {
    private static final String VALID = """
            {
              "region":"서해안",
              "departAt":"2026-11-07T08:30:00+09:00",
              "returnDeadline":"2026-11-07T18:30:00+09:00",
              "safetyBufferMin":30,
              "companions":{"type":"FAMILY","childAgeGroup":"ELEMENTARY"},
              "interests":["EXPERIENCE","FOOD","CAFE"],
              "vehicleType":"EV",
              "maximumOneWayMinutes":120
            }
            """;

    @Autowired private Environment environment;
    @Autowired private ObjectMapper mapper;

    private ObjectNode request() {
        return (ObjectNode) mapper.readTree(VALID);
    }

    private HttpResponse<String> post(String body) throws Exception {
        int port = environment.getRequiredProperty("local.server.port", Integer.class);
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/trips/plan"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private void rejects(ObjectNode request) throws Exception {
        assertEquals(400, post(request.toString()).statusCode(), request.toString());
    }

    @Test
    void returnsTwoDistinctDemoPlansWithoutOrigin() throws Exception {
        HttpResponse<String> response = post(VALID);
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        JsonNode result = mapper.readTree(response.body());
        assertTrue(result.path("tripId").asString().startsWith("demo-"));
        assertEquals("DEMO_SIMULATED", result.path("dataMode").asString());
        assertEquals("NOT_ROUTE_TIME_VALIDATED", result.path("validationStatus").asString());
        assertTrue(result.path("notice").asString().contains("AI 최적화 결과가 아닙니다"));
        JsonNode plans = result.path("alternatives");
        assertEquals(2, plans.size());
        assertEquals("demo-relaxed", plans.get(0).path("planId").asString());
        assertEquals("여유형", plans.get(0).path("title").asString());
        assertEquals("demo-full", plans.get(1).path("planId").asString());
        assertEquals("알찬형", plans.get(1).path("title").asString());
        assertEquals(3, plans.get(0).path("stops").size());
        assertEquals(5, plans.get(1).path("stops").size());
        assertNotEquals(plans.get(0).path("stops"), plans.get(1).path("stops"));
        assertEquals(60, plans.get(0).path("stops").get(0).path("stayMinutes").asInt());
        for (JsonNode plan : plans) {
            assertEquals("DEMO_SIMULATED", plan.path("dataMode").asString());
            for (String field : List.of("predictedReturnAt", "travelMinutes", "isFeasible", "remainingMarginMin")) {
                assertTrue(plan.has(field));
                assertTrue(plan.get(field).isNull(), field);
            }
            for (JsonNode stop : plan.path("stops")) {
                assertEquals("DEMO_SIMULATED", stop.path("dataMode").asString());
                assertTrue(stop.path("name").asString().startsWith("시연 "));
                assertTrue(stop.path("stayMinutes").asInt() > 0);
                assertFalse(stop.path("description").asString().isBlank());
                for (String field : List.of("latitude", "longitude", "placeId", "poiId")) assertFalse(stop.has(field));
            }
        }
        assertFalse(response.body().matches("(?s).*\"(?:latitude|longitude|placeId|poiId)\"\\s*:.*"));
    }

    @Test
    void requiredFieldsCannotBeMissingOrNull() throws Exception {
        for (String field : List.of("region", "departAt", "returnDeadline", "safetyBufferMin", "companions", "interests", "vehicleType", "maximumOneWayMinutes")) {
            ObjectNode missing = request();
            missing.remove(field);
            rejects(missing);
            ObjectNode nulled = request();
            nulled.putNull(field);
            rejects(nulled);
        }
    }

    @Test
    void rejectsMalformedJsonBlankRegionAndInvalidEnums() throws Exception {
        assertEquals(400, post("{").statusCode());
        assertEquals(400, post("[]").statusCode());
        assertEquals(400, post("null").statusCode());
        rejects(request().put("region", "  "));
        rejects(request().put("vehicleType", "UNKNOWN"));
        ObjectNode invalid = request();
        invalid.putObject("companions").put("type", "UNKNOWN");
        rejects(invalid);
        invalid = request();
        invalid.putArray("interests").add("UNKNOWN");
        rejects(invalid);
        invalid = request();
        invalid.putArray("interests").addNull();
        rejects(invalid);
    }

    @Test
    void rejectsInvalidDatesTimesAndMissingOffset() throws Exception {
        for (String field : List.of("departAt", "returnDeadline")) {
            for (String value : List.of("", "08:30", "2026-11-07T08:30:00", "2026-02-30T08:30:00+09:00", "2026-11-07T24:00:00+09:00", "not-a-date")) {
                rejects(request().put(field, value));
            }
        }
    }

    @Test
    void rejectsReturnAtOrBeforeTwoHourBoundaryUsingInstants() throws Exception {
        for (String deadline : List.of("2026-11-07T10:30:00+09:00", "2026-11-07T10:29:00+09:00", "2026-11-07T08:30:00+09:00", "2026-11-07T07:30:00+09:00", "2026-11-07T11:30:00+10:00")) {
            rejects(request().put("returnDeadline", deadline));
        }
        assertEquals(200, post(request().put("returnDeadline", "2026-11-07T11:31:00+10:00").toString()).statusCode());
    }

    @Test
    void explicitDatesPermitOvernightDemoInputWithoutClaimingFeasibility() throws Exception {
        ObjectNode input = request().put("departAt", "2026-11-07T23:00:00+09:00")
                .put("returnDeadline", "2026-11-08T02:01:00+09:00");
        assertEquals(200, post(input.toString()).statusCode());
    }

    @Test
    void rejectsInvalidSafetyAndTravelMinutesIncludingFractions() throws Exception {
        for (int value : List.of(-1, 0, 14, 16, 46)) rejects(request().put("safetyBufferMin", value));
        rejects(request().put("safetyBufferMin", 30.5));
        for (int value : List.of(-1, 0, 181)) rejects(request().put("maximumOneWayMinutes", value));
        rejects(request().put("maximumOneWayMinutes", 60.5));
        for (int buffer : List.of(15, 30, 45)) {
            for (int max : List.of(1, 180)) {
                assertEquals(200, post(request().put("safetyBufferMin", buffer).put("maximumOneWayMinutes", max).toString()).statusCode());
            }
        }
    }

    @Test
    void validatesCompanionsAndRequiresChildAgeForFamily() throws Exception {
        ObjectNode input = request();
        input.putObject("companions").put("type", "FAMILY");
        rejects(input);
        input.putObject("companions");
        rejects(input);
        input.putObject("companions").put("type", "FAMILY").put("childAgeGroup", "UNKNOWN");
        rejects(input);
        input.putObject("companions").put("type", "SOLO");
        input.putArray("interests");
        assertEquals(200, post(input.toString()).statusCode());
    }

    @Test
    void acceptsOnlyDemoModeAndDoesNotIgnoreUnsupportedRequiredStopsOrConstraints() throws Exception {
        rejects(request().put("dataMode", "VERIFIED_LIVE"));
        ObjectNode input = request();
        input.putArray("mustVisitPlaceIds").add("required-place");
        rejects(input);
        input = request();
        input.putObject("constraints").put("budgetKrw", 100000);
        rejects(input);
        input = request().put("dataMode", "DEMO_SIMULATED");
        input.putArray("mustVisitPlaceIds");
        input.putObject("constraints");
        assertEquals(200, post(input.toString()).statusCode());
    }

    @Test
    void optionalOriginIsRangeCheckedButNotEchoedAsVerifiedPlace() throws Exception {
        ObjectNode input = request();
        input.putObject("origin").put("name", "사용자 입력 출발지").put("latitude", 37.0).put("longitude", 127.0);
        HttpResponse<String> response = post(input.toString());
        assertEquals(200, response.statusCode());
        assertFalse(mapper.readTree(response.body()).has("origin"));
        ((ObjectNode) input.get("origin")).put("latitude", 91);
        rejects(input);
        ((ObjectNode) input.get("origin")).put("latitude", 37).put("longitude", -181);
        rejects(input);
        ((ObjectNode) input.get("origin")).remove("longitude");
        rejects(input);
    }

    @Test
    void fixedAlternativesDoNotPretendToOptimizeForRegionOrInterests() throws Exception {
        ObjectNode changed = request().put("region", "호수·산");
        changed.putArray("interests").add("PHOTO");
        JsonNode first = mapper.readTree(post(VALID).body());
        JsonNode second = mapper.readTree(post(changed.toString()).body());
        assertEquals(first.path("alternatives"), second.path("alternatives"));
        assertNotEquals(first.path("tripId"), second.path("tripId"));
    }
}