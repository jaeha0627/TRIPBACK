package com.tripback.backend.trip.feasibility;

import java.time.Instant;

/** A numbered route leg, including the final return leg; null minutes means ETA is missing. */
public record TravelLeg(int index, Long minutes, EtaEvidence evidence) {
    public TravelLeg {
        if (index < 0) throw new IllegalArgumentException("Leg index must be nonnegative");
        if (minutes != null) {
            TripFeasibilityInput.checkMinutes(minutes, "Travel minutes");
            if (evidence == null) throw new IllegalArgumentException("Known ETA requires source evidence");
        }
        if (minutes == null && evidence != null && evidence.verification() == Verification.VERIFIED) {
            throw new IllegalArgumentException("A missing ETA cannot be marked verified");
        }
    }

    public record EtaEvidence(String source, DataMode dataMode, Verification verification, Instant recordedAt) {
        public EtaEvidence {
            if (source == null || source.isBlank() || dataMode == null || verification == null) {
                throw new IllegalArgumentException("ETA source, data mode and verification are required");
            }
            if (dataMode == DataMode.DEMO_SIMULATED && verification != Verification.UNVERIFIED) {
                throw new IllegalArgumentException("Demo ETAs must remain unverified");
            }
            if (dataMode != DataMode.DEMO_SIMULATED && (verification != Verification.VERIFIED || recordedAt == null)) {
                throw new IllegalArgumentException("Verified source labels require verification and a recorded time");
            }
        }
    }

    public enum DataMode { VERIFIED_LIVE, VERIFIED_CACHED, DEMO_SIMULATED }
    public enum Verification { UNVERIFIED, VERIFIED }
}
