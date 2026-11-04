package com.tripback.backend.places;

import com.tripback.backend.trip.provider.*;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;

/** One bounded provider query per request. No startup calls, retry, place selection or itinerary generation. */
@Service
public final class PlaceCandidatesService {
    private final PlaceSearchProvider provider;
    public PlaceCandidatesService(PlaceSearchProvider provider) { this.provider = Objects.requireNonNull(provider); }

    public PlaceCandidatesResponse search(PlaceCandidatesRequest request) {
        PlaceSearchQuery query = request.toQuery();
        Instant attemptedAt = Instant.now();
        PlaceSearchResult result;
        try {
            result = provider.search(query);
        } catch (RuntimeException providerFailure) {
            // An exception alone does not establish authentication/network cause. Never forward its message.
            return PlaceCandidatesResponse.rejected(attemptedAt, "PROVIDER_EXCEPTION");
        }
        if (!valid(result, query.size())) return PlaceCandidatesResponse.rejected(attemptedAt, "INVALID_PROVIDER_RESPONSE");
        return PlaceCandidatesResponse.from(result);
    }

    private boolean valid(PlaceSearchResult result, int maxCandidates) {
        if (result == null || !PlaceCandidatesResponse.PROVIDER.equals(result.provider()) || result.candidates().size() > maxCandidates) return false;
        HashSet<ProviderPlaceId> ids = new HashSet<>();
        for (PlaceCandidate candidate : result.candidates()) {
            if (!ids.add(candidate.id()) || !PlaceCandidatesResponse.PROVIDER.equals(candidate.id().provider())
                    || !PlaceCandidatesResponse.PROVIDER.equals(candidate.data().provider())) return false;
            // Recheck boundary coordinates before public projection, without repairing or dropping a candidate.
            try { new Wgs84Point(candidate.coordinates().latitude(), candidate.coordinates().longitude()); }
            catch (IllegalArgumentException invalid) { return false; }
        }
        return true;
    }
}
