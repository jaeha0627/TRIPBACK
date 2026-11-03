package com.tripback.backend.trip.provider;

@FunctionalInterface
public interface PlaceSearchProvider {
    /** No result is different from an unavailable/failed provider. Adapters must validate every candidate. */
    PlaceSearchResult search(PlaceSearchQuery query);
}
