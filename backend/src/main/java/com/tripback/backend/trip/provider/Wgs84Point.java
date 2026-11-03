package com.tripback.backend.trip.provider;

/** Named latitude/longitude avoid the provider-specific x/y ordering. */
public record Wgs84Point(double latitude, double longitude) {
    public Wgs84Point {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Finite WGS84 latitude/longitude within range are required");
        }
    }
}
