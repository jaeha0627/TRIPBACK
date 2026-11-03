package com.tripback.backend.trip.provider;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/** Safe production defaults. Even configured credentials cannot enable a not-yet-implemented adapter. */
public final class UnavailableProviders {
    private UnavailableProviders() {}

    public static PlaceSearchProvider placeSearch() { return placeSearch(Clock.systemUTC()); }
    public static RoadRouteProvider roadRoute() { return roadRoute(Clock.systemUTC()); }

    public static PlaceSearchProvider placeSearch(Clock clock) {
        Objects.requireNonNull(clock);
        return query -> {
            Objects.requireNonNull(query);
            return new PlaceSearchResult("KAKAO_LOCAL", PlaceSearchResult.Status.UNAVAILABLE, List.of(),
                    ProviderFailure.of(ProviderFailure.Code.ADAPTER_NOT_IMPLEMENTED), clock.instant());
        };
    }

    public static RoadRouteProvider roadRoute(Clock clock) {
        Objects.requireNonNull(clock);
        return query -> {
            Objects.requireNonNull(query);
            return new RoadRouteResult("KAKAO_MOBILITY", query, RoadRouteResult.Status.UNAVAILABLE, null, null,
                    ProviderFailure.of(ProviderFailure.Code.ADAPTER_NOT_IMPLEMENTED), clock.instant());
        };
    }
}
