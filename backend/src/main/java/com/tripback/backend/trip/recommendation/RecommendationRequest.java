package com.tripback.backend.trip.recommendation;

import com.tripback.backend.trip.provider.*;
import com.tripback.backend.trip.feasibility.TripFeasibilityInput;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import static com.tripback.backend.trip.recommendation.RecommendationJson.*;

public record RecommendationRequest(Wgs84Point origin, String targetAreaKeyword, Wgs84Point targetCenter,
        OffsetDateTime departAt, OffsetDateTime returnDeadline, long safetyBufferMin, String companionType,
        List<String> interests, String vehicleType, int maximumOneWayMinutes, Map<String, JsonNode> preferences,
        List<ProviderPlaceId> mustVisitPlaceIds) {
    static final int MAX_BYTES = 16_384;
    static final Set<String> REQUIRED = Set.of("origin", "targetAreaKeyword", "targetCenter", "departAt", "returnDeadline",
            "safetyBufferMin", "companionType", "interests", "vehicleType", "maximumOneWayMinutes");
    static final Set<String> OPTIONAL = Set.of("childAgeGroup", "budgetPerPersonKrw", "mealPreferences", "walkingPreference",
            "parkingPreferences", "amenities", "preferIndoorWhenRaining", "mustVisitPlaceIds");
    static final Set<String> INTERESTS = Set.of("EXPERIENCE", "FOOD", "NATURE", "CAFE", "CULTURE", "SHOPPING", "SPA", "PHOTO");

    public RecommendationRequest {
        interests = List.copyOf(interests); preferences = Map.copyOf(preferences); mustVisitPlaceIds = List.copyOf(mustVisitPlaceIds);
    }
    static RecommendationRequest decode(byte[] bytes, ObjectMapper mapper) {
        try {
            JsonNode root = parse(bytes, MAX_BYTES, mapper); object(root, REQUIRED, OPTIONAL);
            Wgs84Point origin = point(root.get("origin")), center = point(root.get("targetCenter"));
            String area = text(root.get("targetAreaKeyword"), 100);
            long buffer = integer(root.get("safetyBufferMin"), 15, 45); check(Set.of(15L, 30L, 45L).contains(buffer), "INVALID_BUFFER");
            var time = TripFeasibilityInput.fromIso8601(text(root.get("departAt"), 100), text(root.get("returnDeadline"), 100), buffer, List.of(), List.of());
            check(time.returnDeadline().toInstant().isAfter(time.departAt().toInstant()), "INVALID_TIME_RANGE");
            String companion = choice(root.get("companionType"), Set.of("FAMILY", "COUPLE", "FRIENDS", "PARENTS", "SOLO"));
            List<String> interests = choices(root.get("interests"), INTERESTS);
            String vehicle = choice(root.get("vehicleType"), Set.of("EV", "ICE", "HEV", "LPG", "FCEV"));
            int maximum = (int) integer(root.get("maximumOneWayMinutes"), 1, 180);
            Map<String, JsonNode> prefs = new LinkedHashMap<>();
            for (String key : OPTIONAL) if (!key.equals("mustVisitPlaceIds") && root.hasNonNull(key)) prefs.put(key, root.get(key).deepCopy());
            if (companion.equals("FAMILY")) check(prefs.containsKey("childAgeGroup"), "CHILD_AGE_REQUIRED");
            if (prefs.containsKey("childAgeGroup")) choice(prefs.get("childAgeGroup"), Set.of("INFANT", "ELEMENTARY", "TEEN"));
            if (prefs.containsKey("budgetPerPersonKrw")) integer(prefs.get("budgetPerPersonKrw"), 0, 100_000_000);
            if (prefs.containsKey("walkingPreference")) choice(prefs.get("walkingPreference"), Set.of("LOW", "NORMAL", "HIGH"));
            if (prefs.containsKey("mealPreferences")) choices(prefs.get("mealPreferences"), Set.of("KOREAN", "SEAFOOD", "WESTERN", "VEGETARIAN", "KIDS"));
            if (prefs.containsKey("parkingPreferences")) choices(prefs.get("parkingPreferences"), Set.of("FREE", "WIDE", "NEAR"));
            if (prefs.containsKey("amenities")) choices(prefs.get("amenities"), Set.of("ACCESSIBLE", "NURSING_PLAYROOM", "PET"));
            if (prefs.containsKey("preferIndoorWhenRaining")) check(prefs.get("preferIndoorWhenRaining").isBoolean(), "INVALID_BOOLEAN");
            List<ProviderPlaceId> requiredIds = new ArrayList<>();
            if (root.hasNonNull("mustVisitPlaceIds")) {
                JsonNode ids = root.get("mustVisitPlaceIds"); check(ids.isArray() && ids.size() <= 5, "INVALID_REQUIRED_PLACES");
                for (JsonNode id : ids) {
                    object(id, Set.of("provider", "value"), Set.of());
                    requiredIds.add(new ProviderPlaceId(choice(id.get("provider"), Set.of("KAKAO_LOCAL")), text(id.get("value"), 200)));
                }
                check(new HashSet<>(requiredIds).size() == requiredIds.size(), "DUPLICATE_REQUIRED_PLACES");
            }
            return new RecommendationRequest(origin, area, center, time.departAt(), time.returnDeadline(), buffer, companion, interests, vehicle, maximum, prefs, requiredIds);
        } catch (RuntimeException invalid) { throw new BadRequest(); }
    }
    private static Wgs84Point point(JsonNode node) {
        object(node, Set.of("latitude", "longitude"), Set.of());
        check(node.get("latitude").isNumber() && node.get("longitude").isNumber(), "INVALID_COORDINATES");
        return new Wgs84Point(node.get("latitude").doubleValue(), node.get("longitude").doubleValue());
    }
    ObjectNode modelPreferences(ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("targetAreaKeyword", targetAreaKeyword); node.put("companionType", companionType);
        node.set("interests", mapper.valueToTree(interests)); node.put("vehicleType", vehicleType);
        node.put("availableMinutes", Duration.between(departAt.toInstant(), returnDeadline.toInstant()).toMinutes());
        node.put("safetyBufferMin", safetyBufferMin); node.put("maximumOneWayMinutes", maximumOneWayMinutes);
        preferences.forEach((key, value) -> node.set(key, value.deepCopy()));
        node.set("requiredPlaceIds", mapper.valueToTree(mustVisitPlaceIds));
        return node;
    }
    static final class BadRequest extends RuntimeException {
        BadRequest() { super("INVALID_REQUEST", null, false, false); }
    }
}
