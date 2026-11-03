package com.tripback.backend.trip.provider;

/** One driving leg, with current conditions. Future-departure ETA is a separate future adapter capability. */
public record RoadRouteQuery(Wgs84Point origin, Wgs84Point destination) {
    public RoadRouteQuery {
        if (origin == null || destination == null) throw new IllegalArgumentException("Both route endpoints are required");
    }
}
