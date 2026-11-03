package com.tripback.backend.trip.validationapi;

import com.tripback.backend.trip.integration.TripValidationInput;
import com.tripback.backend.trip.provider.ProviderPlaceId;
import com.tripback.backend.trip.provider.Wgs84Point;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.*;
import java.util.*;

/** Wire DTO only. Local strict decoding avoids changing the existing DEMO API's Jackson settings. */
public record TripValidationRequest(Origin origin, String departAt, String returnDeadline,
        long safetyBufferMin, List<Visit> visits) {
    public record Origin(double latitude, double longitude) {}
    public record SelectedId(String provider, String value) {}
    public record Visit(String keyword, SelectedId selectedId, long stayMinutes) {}

    public static TripValidationRequest decode(byte[] body, ObjectMapper mapper) {
        if (body == null || body.length == 0 || body.length > 65_536) throw invalid();
        try (JsonParser parser = mapper.createParser(body)) {
            JsonNode root = mapper.reader(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(parser);
            if (parser.nextToken() != null) throw invalid();
            object(root, "origin", "departAt", "returnDeadline", "safetyBufferMin", "visits");
            JsonNode origin = root.get("origin");
            object(origin, "latitude", "longitude");
            JsonNode visits = root.get("visits");
            if (visits == null || !visits.isArray() || visits.size() < 1 || visits.size() > 5) throw invalid();
            List<Visit> converted = new ArrayList<>();
            for (JsonNode visit : visits) {
                object(visit, "keyword", "selectedId", "stayMinutes");
                JsonNode id = visit.get("selectedId");
                object(id, "provider", "value");
                converted.add(new Visit(text(visit.get("keyword"), 200),
                        new SelectedId(text(id.get("provider"), 32), text(id.get("value"), 200)),
                        integer(visit.get("stayMinutes"))));
            }
            return new TripValidationRequest(new Origin(number(origin.get("latitude")), number(origin.get("longitude"))),
                    text(root.get("departAt"), 100), text(root.get("returnDeadline"), 100),
                    integer(root.get("safetyBufferMin")), List.copyOf(converted));
        } catch (RuntimeException error) {
            // Never expose parser diagnostics, input fragments or nested exception causes.
            throw invalid();
        }
    }

    public TripValidationInput toInput() {
        try {
            if (!Set.of(15L, 30L, 45L).contains(safetyBufferMin)) throw invalid();
            TripValidationInput input = TripValidationInput.fromIso8601(new Wgs84Point(origin.latitude(), origin.longitude()),
                    departAt, returnDeadline, safetyBufferMin, visits.stream().map(v -> new TripValidationInput.Visit(v.keyword(),
                            new ProviderPlaceId(v.selectedId().provider(), v.selectedId().value()), v.stayMinutes())).toList());
            if (!input.returnDeadline().toInstant().isAfter(input.departAt().toInstant())) throw invalid();
            // Reject input-only aggregate duration overflow before any provider calls.
            long totalStay = 0;
            for (var visit : input.visits()) totalStay = Math.addExact(totalStay, visit.stayMinutes());
            long staySeconds = Math.multiplyExact(totalStay, 60L);
            input.departAt().toInstant().plusSeconds(staySeconds).atOffset(input.departAt().getOffset());
            return input;
        } catch (RuntimeException error) {
            throw invalid();
        }
    }

    private static void object(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) throw invalid();
        Set<String> allowed = Set.of(fields);
        for (String name : node.propertyNames()) if (!allowed.contains(name)) throw invalid();
        for (String name : fields) if (node.get(name) == null || node.get(name).isNull()) throw invalid();
    }
    private static String text(JsonNode node, int limit) {
        if (node == null || !node.isString() || node.asString().isBlank() || node.asString().length() > limit) throw invalid();
        return node.asString();
    }
    private static long integer(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0) throw invalid();
        return node.longValue();
    }
    private static double number(JsonNode node) {
        if (node == null || !node.isNumber() || !Double.isFinite(node.doubleValue())) throw invalid();
        return node.doubleValue();
    }
    static InvalidRequest invalid() { return new InvalidRequest(); }
    static final class InvalidRequest extends RuntimeException {
        InvalidRequest() { super("INVALID_REQUEST", null, false, false); }
    }
}
