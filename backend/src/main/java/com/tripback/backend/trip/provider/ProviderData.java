package com.tripback.backend.trip.provider;

import java.time.Instant;

/** Describes validated response data, never operating hours, visitability or actual arrival guarantees. */
public record ProviderData(String provider, DataMode dataMode, Verification verification, Instant retrievedAt) {
    public ProviderData {
        ProviderPlaceId.requireText(provider, "Provider");
        if (dataMode == null || verification == null || retrievedAt == null) {
            throw new IllegalArgumentException("Data mode, verification and retrieval time are required");
        }
        if ((dataMode == DataMode.DEMO_SIMULATED) != (verification == Verification.UNVERIFIED)) {
            throw new IllegalArgumentException("Demo must stay unverified; verified source labels require validated provider data");
        }
    }

    public enum DataMode { VERIFIED_LIVE, VERIFIED_CACHED, DEMO_SIMULATED }
    public enum Verification { UNVERIFIED, VALIDATED_PROVIDER_DATA }
}
