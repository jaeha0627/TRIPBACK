package com.tripback.backend.trip.integration;

import com.tripback.backend.trip.feasibility.TripFeasibilityInput;
import com.tripback.backend.trip.provider.*;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;

/** Internal input, independent of the DEMO REST contract. Required visit order is immutable. */
public record TripValidationInput(Wgs84Point origin, OffsetDateTime departAt, OffsetDateTime returnDeadline,
        long safetyBufferMin, List<Visit> visits) {
    public TripValidationInput {
        if (origin == null || visits == null || visits.isEmpty() || visits.size() > 5
                || visits.stream().anyMatch(v -> v == null)) throw invalid("INVALID_INPUT");
        visits = List.copyOf(visits);
        HashSet<ProviderPlaceId> ids = new HashSet<>();
        for (Visit visit : visits) if (!ids.add(visit.selectedId())) throw invalid("DUPLICATE_PLACE_ID");
        try {
            // Reuse engine rules without importing DEMO API-specific constraints.
            new TripFeasibilityInput(departAt, returnDeadline, safetyBufferMin,
                    visits.stream().map(Visit::stayMinutes).toList(), List.of());
        } catch (IllegalArgumentException error) {
            throw invalid("INVALID_TIME_OR_DURATION");
        }
    }

    public static TripValidationInput fromIso8601(Wgs84Point origin, String departAt, String deadline,
            long buffer, List<Visit> visits) {
        TripFeasibilityInput parsed;
        try {
            parsed = TripFeasibilityInput.fromIso8601(departAt, deadline, buffer, List.of(), List.of());
        } catch (IllegalArgumentException error) {
            throw invalid("INVALID_TIME_OR_DURATION");
        }
        return new TripValidationInput(origin, parsed.departAt(), parsed.returnDeadline(), buffer, visits);
    }

    public record Visit(String keyword, ProviderPlaceId selectedId, long stayMinutes) {
        public Visit {
            if (selectedId == null || !"KAKAO_LOCAL".equals(selectedId.provider())) throw invalid("INVALID_PLACE_NAMESPACE");
            try {
                new PlaceSearchQuery(keyword, null, null, 1, 15);
                new TripFeasibilityInput(OffsetDateTime.MIN, OffsetDateTime.MIN, 0, List.of(stayMinutes), List.of());
            } catch (IllegalArgumentException error) {
                throw invalid("INVALID_VISIT_INPUT");
            }
        }
    }

    private static IllegalArgumentException invalid(String code) { return new IllegalArgumentException(code); }
}
