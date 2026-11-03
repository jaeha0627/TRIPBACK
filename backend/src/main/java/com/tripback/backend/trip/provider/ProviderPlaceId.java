package com.tripback.backend.trip.provider;

/** Opaque provider-scoped ID; not a Pleos NaviHelper POI ID. */
public record ProviderPlaceId(String provider, String value) {
    public ProviderPlaceId {
        requireText(provider, "Provider");
        requireText(value, "Provider place ID");
    }

    static void requireText(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " must be nonblank without surrounding whitespace");
        }
    }
}
