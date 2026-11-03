package com.tripback.backend.trip.provider;

public record PlaceCandidate(ProviderPlaceId id, String name, String address, Wgs84Point coordinates, ProviderData data) {
    public PlaceCandidate {
        if (id == null || address == null || coordinates == null || data == null) {
            throw new IllegalArgumentException("Place identity, address, coordinates and provenance are required");
        }
        ProviderPlaceId.requireText(name, "Place name");
        if (!id.provider().equals(data.provider())) throw new IllegalArgumentException("Place and source provider disagree");
    }

    // Search data cannot establish opening hours or visitability. A future independent check is required.
    public VisitStatus visitStatus() { return VisitStatus.NOT_VERIFIED; }
    public enum VisitStatus { NOT_VERIFIED }
}
