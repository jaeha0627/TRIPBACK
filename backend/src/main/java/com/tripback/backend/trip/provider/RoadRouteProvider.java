package com.tripback.backend.trip.provider;

@FunctionalInterface
public interface RoadRouteProvider {
    /** Query one required driving leg. Never synthesize an ETA or silently remove endpoints. */
    RoadRouteResult route(RoadRouteQuery query);
}
