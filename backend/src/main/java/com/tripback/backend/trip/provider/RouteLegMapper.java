package com.tripback.backend.trip.provider;

import com.tripback.backend.trip.feasibility.TravelLeg;

/** Explicit bridge only; not wired into TripPlanService. Preserves missing legs and declared source modes. */
public final class RouteLegMapper {
    private RouteLegMapper() {}

    public static TravelLeg toTravelLeg(int index, RoadRouteQuery expected, RoadRouteResult result) {
        if (expected == null) throw new IllegalArgumentException("Expected endpoints are required");
        if (result != null && !expected.equals(result.query())) {
            throw new IllegalArgumentException("Returned route endpoints do not match the requested leg");
        }
        if (result == null || result.status() != RoadRouteResult.Status.FOUND) {
            return new TravelLeg(index, null, null);
        }
        long seconds = result.durationSeconds();
        // Conservative per-leg rounding for the existing engine's whole-minute input; no addition overflow.
        long minutes = seconds / 60 + (seconds % 60 == 0 ? 0 : 1);
        ProviderData data = result.data();
        TravelLeg.EtaEvidence evidence = new TravelLeg.EtaEvidence(data.provider(),
                TravelLeg.DataMode.valueOf(data.dataMode().name()),
                data.verification() == ProviderData.Verification.VALIDATED_PROVIDER_DATA
                        ? TravelLeg.Verification.VERIFIED : TravelLeg.Verification.UNVERIFIED,
                data.retrievedAt());
        return new TravelLeg(index, minutes, evidence);
    }
}
