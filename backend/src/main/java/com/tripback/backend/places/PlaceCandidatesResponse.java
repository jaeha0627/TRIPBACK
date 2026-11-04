package com.tripback.backend.places;

import com.tripback.backend.trip.provider.*;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

/** Explicit public projection, without upstream headers, raw bodies, exceptions or automatic selection. */
public record PlaceCandidatesResponse(String provider, String status, int page, int size,
        List<Candidate> candidates, List<String> dataModes, Instant attemptedAt, String reason, Failure failure, String notice) {
    static final String PROVIDER = "KAKAO_LOCAL";
    static final String NOTICE = "CANDIDATES_ONLY; NO_AUTOMATIC_SELECTION; VISITABILITY_NOT_VERIFIED; "
            + "KAKAO_IDS_ARE_NOT_PLEOS_POI_IDS; NOT_AI_ITINERARY_RECOMMENDATIONS";

    public record Id(String provider, String value) {}
    public record Coordinates(double latitude, double longitude) {}
    public record Source(String provider, String dataMode, String verification, Instant retrievedAt) {
        static Source from(ProviderData data) {
            return new Source(data.provider(), data.dataMode().name(), data.verification().name(), data.retrievedAt());
        }
    }
    public record Candidate(int index, Id id, String name, String address, Coordinates coordinates, Source data, String visitStatus) {}
    public record Failure(String code, Integer httpStatus) {
        static Failure from(ProviderFailure failure) {
            return failure == null ? null : new Failure(failure.code().name(), failure.httpStatus());
        }
    }

    static PlaceCandidatesResponse from(PlaceSearchResult result) {
        List<Candidate> candidates = IntStream.range(0, result.candidates().size()).mapToObj(index -> {
            PlaceCandidate c = result.candidates().get(index);
            return new Candidate(index, new Id(c.id().provider(), c.id().value()), c.name(), c.address(),
                    new Coordinates(c.coordinates().latitude(), c.coordinates().longitude()), Source.from(c.data()), c.visitStatus().name());
        }).toList();
        String reason = switch (result.status()) {
            case FOUND, NO_RESULTS -> null;
            case UNAVAILABLE -> "PROVIDER_UNAVAILABLE";
            case FAILED -> "PROVIDER_FAILED";
        };
        return new PlaceCandidatesResponse(PROVIDER, result.status().name(), 1, 15, candidates,
                result.candidates().stream().map(c -> c.data().dataMode().name()).distinct().sorted().toList(),
                result.attemptedAt(), reason, Failure.from(result.failure()), NOTICE);
    }

    static PlaceCandidatesResponse rejected(Instant attemptedAt, String reason) {
        return new PlaceCandidatesResponse(PROVIDER, "FAILED", 1, 15, List.of(), List.of(), attemptedAt, reason,
                "INVALID_PROVIDER_RESPONSE".equals(reason) ? new Failure("INVALID_DATA", null) : null, NOTICE);
    }
}
