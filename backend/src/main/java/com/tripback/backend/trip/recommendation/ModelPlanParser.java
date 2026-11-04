package com.tripback.backend.trip.recommendation;

import com.tripback.backend.trip.provider.ProviderPlaceId;
import tools.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.tripback.backend.trip.recommendation.RecommendationJson.*;

final class ModelPlanParser {
    static final int MAX_OUTPUT_BYTES = 32_768;
    record Visit(ProviderPlaceId id, long stayMinutes) {}
    record Plan(String title, String reason, List<Visit> visits) { Plan { visits = List.copyOf(visits); } }
    static List<JsonNode> planNodes(String json, ObjectMapper mapper) {
        JsonNode root = parse(json.getBytes(StandardCharsets.UTF_8), MAX_OUTPUT_BYTES, mapper);
        object(root, Set.of("plans"), Set.of());
        check(root.get("plans").isArray() && root.get("plans").size() <= 2, "INVALID_ALTERNATIVE_COUNT");
        List<JsonNode> plans = new ArrayList<>(); root.get("plans").forEach(plans::add); return plans;
    }
    static Plan plan(JsonNode node, Set<ProviderPlaceId> allowed, List<ProviderPlaceId> required) {
        object(node, Set.of("title", "recommendationReason", "orderedVisits"), Set.of());
        String title = text(node.get("title"), 80), reason = text(node.get("recommendationReason"), 1000);
        check(!sensitive(title) && !sensitive(reason), "UNSAFE_GENERATED_TEXT");
        JsonNode visits = node.get("orderedVisits");
        check(visits.isArray() && visits.size() >= 1 && visits.size() <= 5, "INVALID_VISIT_COUNT");
        List<Visit> ordered = new ArrayList<>(); Set<ProviderPlaceId> selected = new HashSet<>();
        for (JsonNode visit : visits) {
            object(visit, Set.of("provider", "placeId", "stayMinutes"), Set.of());
            ProviderPlaceId id = new ProviderPlaceId(choice(visit.get("provider"), Set.of("KAKAO_LOCAL")), text(visit.get("placeId"), 200));
            check(allowed.contains(id), "UNLISTED_PLACE_ID"); check(selected.add(id), "DUPLICATE_VISIT");
            ordered.add(new Visit(id, integer(visit.get("stayMinutes"), 0, 240)));
        }
        check(selected.containsAll(required), "REQUIRED_VISIT_MISSING");
        return new Plan(title, reason, ordered);
    }
    private static boolean sensitive(String value) {
        return value.matches("(?s).*[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}.*")
                || value.matches("(?s).*0[0-9]{1,2}[- ]?[0-9]{3,4}[- ]?[0-9]{4}.*")
                || value.matches("(?s).*sk-[A-Za-z0-9_-]{12,}.*");
    }
}
