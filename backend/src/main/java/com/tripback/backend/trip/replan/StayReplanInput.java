package com.tripback.backend.trip.replan;

import java.time.OffsetDateTime;
import java.util.Objects;

/** STAYING only. Total planned stay is minutes, observed elapsed stay is seconds.
 * observedAt is the calculation time; no implicit wall clock or elapsed travel is added.
 */
public record StayReplanInput(ValidatedReplanPlan plan, int currentVisitIndex,
        OffsetDateTime observedAt, long elapsedStaySeconds, long requestedTotalStayMinutes) {
    public StayReplanInput {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(observedAt);
        if (currentVisitIndex < 0 || currentVisitIndex >= plan.visits.size()
                || elapsedStaySeconds < 0 || requestedTotalStayMinutes < 0)
            throw new IllegalArgumentException("INVALID_STAY_OBSERVATION");
    }
}
