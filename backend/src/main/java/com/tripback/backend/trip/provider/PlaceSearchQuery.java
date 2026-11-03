package com.tripback.backend.trip.provider;

/** Initial keyword search contract; bounds follow the reviewed Kakao Local candidate API. */
public record PlaceSearchQuery(String keyword, Wgs84Point center, Integer radiusMeters, int page, int size) {
    public PlaceSearchQuery {
        ProviderPlaceId.requireText(keyword, "Search keyword");
        if (page < 1 || page > 45 || size < 1 || size > 15
                || (radiusMeters != null && (center == null || radiusMeters < 0 || radiusMeters > 20000))) {
            throw new IllegalArgumentException("Invalid search page, size or centered radius");
        }
    }
}
