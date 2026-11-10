package com.tripback.backend.trip.recommendation;

import com.tripback.backend.trip.provider.*;
import java.time.Instant;
import java.util.*;
import static com.tripback.backend.trip.recommendation.RecommendationJson.*;

final class CandidateCollector {
    static final int MAX_CANDIDATES = 30;
    private static final Map<String, String> SUFFIXES = Map.of("EXPERIENCE", "체험", "FOOD", "맛집", "NATURE", "자연",
            "CAFE", "카페", "CULTURE", "관광", "SHOPPING", "쇼핑", "SPA", "온천", "PHOTO", "명소");
    record Candidate(String keyword, PlaceCandidate place, Instant searchAttemptedAt) {}
    record SearchReport(String keyword, String status, String reason, String failureCode, Integer httpStatus, Instant attemptedAt) {}
    record Excluded(String keyword, ProviderPlaceId id, String reason) {}
    record Pool(List<Candidate> candidates, List<SearchReport> searches, List<Excluded> excluded) {
        Pool { candidates = List.copyOf(candidates); searches = List.copyOf(searches); excluded = List.copyOf(excluded); }
    }
    static List<String> keywords(RecommendationRequest request) {
        return request.interests().isEmpty() ? List.of(request.targetAreaKeyword() + " 관광")
                : request.interests().stream().map(interest -> request.targetAreaKeyword() + " " + SUFFIXES.get(interest)).distinct().toList();
    }
    Pool collect(RecommendationRequest input, PlaceSearchProvider places, RecommendationBudget budget) {
        Map<ProviderPlaceId, Candidate> accepted = new LinkedHashMap<>();
        Set<ProviderPlaceId> conflicting = new HashSet<>();
        List<SearchReport> searches = new ArrayList<>(); List<Excluded> excluded = new ArrayList<>();
        for (String keyword : keywords(input)) {
            budget.checkTime();
            if (accepted.size() >= MAX_CANDIDATES) {
                searches.add(new SearchReport(keyword, "NOT_SEARCHED", "CANDIDATE_LIMIT", null, null, null)); continue;
            }
            PlaceSearchResult result;
            try { result = places.search(new PlaceSearchQuery(keyword, input.targetCenter(), 20_000, 1, 15)); }
            catch (Rejected bounded) { throw bounded; }
            catch (RuntimeException unavailable) {
                searches.add(new SearchReport(keyword, "FAILED", "PROVIDER_EXCEPTION", null, null, null)); continue;
            }
            if (result == null || !"KAKAO_LOCAL".equals(result.provider()) || result.candidates().size() > 15) {
                searches.add(new SearchReport(keyword, "FAILED", "INVALID_PROVIDER_RESPONSE", "INVALID_DATA", null, null)); continue;
            }
            searches.add(new SearchReport(keyword, result.status().name(), null,
                    result.failure() == null ? null : result.failure().code().name(),
                    result.failure() == null ? null : result.failure().httpStatus(), result.attemptedAt()));
            if (result.status() != PlaceSearchResult.Status.FOUND) continue;
            for (PlaceCandidate place : result.candidates()) {
                if (!valid(place)) { excluded.add(new Excluded(keyword, null, "INVALID_CANDIDATE")); continue; }
                ProviderPlaceId id = place.id();
                Candidate previous = accepted.get(id);
                if (conflicting.contains(id) || previous != null && !previous.place().coordinates().equals(place.coordinates())) {
                    accepted.remove(id); conflicting.add(id); excluded.add(new Excluded(keyword, id, "CONFLICTING_PLACE_ID")); continue;
                }
                if (previous != null) { excluded.add(new Excluded(keyword, id, "DUPLICATE_ID")); continue; }
                if (!RegionDistance.contains(input.targetCenter(), place.coordinates())) {
                    excluded.add(new Excluded(keyword, id, "OUTSIDE_TARGET_RADIUS")); continue;
                }
                if (accepted.size() >= MAX_CANDIDATES) { excluded.add(new Excluded(keyword, id, "CANDIDATE_LIMIT")); continue; }
                accepted.put(id, new Candidate(keyword, place, result.attemptedAt()));
            }
        }
        return new Pool(new ArrayList<>(accepted.values()), searches, excluded);
    }
    static boolean valid(PlaceCandidate place) {
        try {
            if (place == null || !"KAKAO_LOCAL".equals(place.id().provider()) || !"KAKAO_LOCAL".equals(place.data().provider())) return false;
            new Wgs84Point(place.coordinates().latitude(), place.coordinates().longitude());
            textValue(place.id().value(), 200); textValue(place.name(), 500);
            return place.address() != null && place.address().length() <= 2000;
        } catch (RuntimeException invalid) { return false; }
    }
    private static void textValue(String value, int max) {
        check(value != null && !value.isBlank() && value.length() <= max && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl), "INVALID_CANDIDATE");
    }
}
