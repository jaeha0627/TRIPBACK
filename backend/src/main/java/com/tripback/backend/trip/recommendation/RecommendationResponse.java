package com.tripback.backend.trip.recommendation;

import com.tripback.backend.trip.provider.*;
import com.tripback.backend.trip.validationapi.TripValidationResponse;
import java.time.Instant;
import java.util.*;

public record RecommendationResponse(String recommendationStatus, AiProviderStatus aiProviderStatus,
        List<Alternative> alternatives, List<RejectedAlternative> rejectedAlternatives, List<Candidate> candidates,
        List<CandidateCollector.SearchReport> searches, List<CandidateCollector.Excluded> excludedCandidates,
        Calls calls, Map<String, String> conditionStatus, List<String> limitations) {
    public record AiProviderStatus(String provider, String status, String invocation) {}
    public record Calls(int placeSearches, int roadRoutes, int modelGenerations) {}
    public record OrderedVisit(int index, ProviderPlaceId id, String originalKeyword, long stayMinutes) {}
    public record Alternative(String planId, String title, String recommendationReason, List<OrderedVisit> orderedVisits,
            TripValidationResponse validation, String feasibility, List<String> dataModes, List<String> notices) {}
    public record RejectedAlternative(int index, String reason, Alternative proposal) {}
    public record Candidate(String originalKeyword, ProviderPlaceId id, String name, String address,
            TripValidationResponse.Point coordinates, TripValidationResponse.Source data, Instant searchAttemptedAt, String visitStatus) {
        static Candidate from(CandidateCollector.Candidate candidate) {
            PlaceCandidate place = candidate.place(); ProviderData data = place.data();
            return new Candidate(candidate.keyword(), place.id(), place.name(), place.address(),
                    new TripValidationResponse.Point(place.coordinates().latitude(), place.coordinates().longitude()),
                    new TripValidationResponse.Source(data.provider(), data.dataMode().name(), data.verification().name(), data.retrievedAt()),
                    candidate.searchAttemptedAt(), place.visitStatus().name());
        }
    }
}
