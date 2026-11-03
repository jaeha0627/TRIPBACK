package com.tripback.backend.trip.feasibility;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/** Feasibility is mathematical only; source verification is an independent, caller-declared state. */
public record TripFeasibilityResult(
        Feasibility feasibility,
        RouteDataStatus routeDataStatus,
        Long totalTravelMinutes,
        long totalStayMinutes,
        OffsetDateTime predictedReturnAt,
        Duration remainingMargin,
        Long remainingMarginMin,
        Boolean returnDeadlineMet,
        Boolean safetyBufferSatisfied,
        List<Integer> missingEtaLegIndexes,
        Set<UnknownReason> unknownReasons,
        List<TravelLeg> evaluatedLegs
) {
    public TripFeasibilityResult {
        missingEtaLegIndexes = List.copyOf(missingEtaLegIndexes);
        unknownReasons = Set.copyOf(unknownReasons);
        evaluatedLegs = List.copyOf(evaluatedLegs);
    }

    public enum Feasibility { FEASIBLE, INFEASIBLE, UNKNOWN }
    public enum RouteDataStatus { UNVERIFIED, ALL_ETAS_DECLARED_VERIFIED, INCOMPLETE }
    public enum UnknownReason { LEG_COUNT_MISMATCH, MISSING_REQUIRED_LEG, MISSING_ETA }
}
