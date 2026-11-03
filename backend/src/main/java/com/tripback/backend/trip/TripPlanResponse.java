package com.tripback.backend.trip;

import java.util.List;

public record TripPlanResponse(
        String tripId,
        String dataMode,
        String validationStatus,
        String notice,
        List<Alternative> alternatives
) {
    public record Alternative(
            String planId,
            String title,
            String description,
            String dataMode,
            List<Stop> stops,
            String predictedReturnAt,
            Integer travelMinutes,
            Boolean isFeasible,
            Integer remainingMarginMin
    ) {}

    // Local stopId is a UI identifier, not a real place/POI identity.
    // No coordinates or placeId exist in this fictional stop DTO.
    public record Stop(
            String stopId,
            String name,
            String category,
            int stayMinutes,
            String description,
            String dataMode
    ) {}
}