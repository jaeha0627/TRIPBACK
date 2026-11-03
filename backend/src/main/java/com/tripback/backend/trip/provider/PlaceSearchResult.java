package com.tripback.backend.trip.provider;

import java.time.Instant;
import java.util.List;

public record PlaceSearchResult(String provider, Status status, List<PlaceCandidate> candidates,
        ProviderFailure failure, Instant attemptedAt) {
    public PlaceSearchResult {
        ProviderPlaceId.requireText(provider, "Provider");
        if (status == null || candidates == null || attemptedAt == null || candidates.stream().anyMatch(p -> p == null)) {
            throw new IllegalArgumentException("Search status, candidate list and attempt time are required");
        }
        candidates = List.copyOf(candidates);
        if (candidates.stream().anyMatch(p -> !provider.equals(p.id().provider()))) {
            throw new IllegalArgumentException("Search results cannot mix provider namespaces");
        }
        boolean valid = switch (status) {
            case FOUND -> !candidates.isEmpty() && failure == null;
            case NO_RESULTS -> candidates.isEmpty() && failure == null;
            case UNAVAILABLE -> candidates.isEmpty() && failure != null && failure.unavailable();
            case FAILED -> candidates.isEmpty() && failure != null && !failure.unavailable()
                    && failure.code() != ProviderFailure.Code.MISSING_ETA;
        };
        if (!valid) throw new IllegalArgumentException("Inconsistent search outcome");
    }

    public enum Status { FOUND, NO_RESULTS, UNAVAILABLE, FAILED }
}
