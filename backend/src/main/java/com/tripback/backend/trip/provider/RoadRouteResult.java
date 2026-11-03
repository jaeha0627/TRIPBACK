package com.tripback.backend.trip.provider;

import java.time.Instant;

public record RoadRouteResult(String provider, RoadRouteQuery query, Status status, Long durationSeconds,
        ProviderData data, ProviderFailure failure, Instant attemptedAt) {
    public RoadRouteResult {
        ProviderPlaceId.requireText(provider, "Provider");
        if (query == null || status == null || attemptedAt == null) {
            throw new IllegalArgumentException("Route endpoints, status and attempt time are required");
        }
        if (data != null && !provider.equals(data.provider())) throw new IllegalArgumentException("Route and source provider disagree");
        boolean valid = switch (status) {
            case FOUND -> durationSeconds != null && durationSeconds >= 0 && data != null && failure == null;
            case NO_ROUTE -> durationSeconds == null && data == null && failure == null;
            case ETA_MISSING -> durationSeconds == null && data == null && failure != null
                    && failure.code() == ProviderFailure.Code.MISSING_ETA;
            case UNAVAILABLE -> durationSeconds == null && data == null && failure != null && failure.unavailable();
            case FAILED -> durationSeconds == null && data == null && failure != null && !failure.unavailable()
                    && failure.code() != ProviderFailure.Code.MISSING_ETA;
        };
        if (!valid) throw new IllegalArgumentException("Inconsistent route outcome; absent ETA must stay null");
    }

    public enum Status { FOUND, NO_ROUTE, ETA_MISSING, UNAVAILABLE, FAILED }
}
