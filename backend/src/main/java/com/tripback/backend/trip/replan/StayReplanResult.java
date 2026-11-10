package com.tripback.backend.trip.replan;

import com.tripback.backend.trip.feasibility.*;
import java.time.*;
import java.util.*;

/** Immutable preview only; arithmetic feasibility never guarantees actual arrival. */
public record StayReplanResult(ChangeStatus changeStatus, Projection before, Projection after) {
    public enum ChangeStatus { PREVIEW_ONLY, TOTAL_STAY_BELOW_ELAPSED, CALCULATION_OUT_OF_RANGE }
    public enum Reason { CACHED_ETA_ASSUMPTION, MISSING_ETA, UNTRUSTED_ETA, STALE_ETA,
        FUTURE_ETA_TIMESTAMP, DEADLINE_PASSED, DEADLINE_EXCEEDED, SAFETY_BUFFER_SHORTFALL,
        TOTAL_STAY_BELOW_ELAPSED, CALCULATION_OUT_OF_RANGE }
    public record Projection(TripFeasibilityResult.Feasibility feasibility,
            TripFeasibilityResult.Feasibility arithmeticFeasibility, OffsetDateTime predictedReturnAt,
            Duration remainingMargin, Long remainingMarginMinutes, Boolean returnDeadlineMet,
            Boolean safetyBufferSatisfied, Long currentRemainingStayMinutes,
            List<TravelLeg> remainingLegs, Set<Reason> reasons) {
        public Projection {
            remainingLegs = List.copyOf(remainingLegs);
            reasons = Set.copyOf(reasons);
        }
    }
}
