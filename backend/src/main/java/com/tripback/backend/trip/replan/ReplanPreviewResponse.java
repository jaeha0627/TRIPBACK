package com.tripback.backend.trip.replan;

import com.tripback.backend.trip.feasibility.TravelLeg;
import com.tripback.backend.trip.provider.*;
import java.time.*;
import java.util.*;

/** Explicit wire projection. No token echo, raw provider payload or internal synthetic zero leg. */
public record ReplanPreviewResponse(String status, long baseRevision, Instant planExpiresAt, Instant calculatedAt,
        String progressVerification, int currentVisitIndex, long safetyBufferMinutes, OffsetDateTime returnDeadline,
        Wgs84Point originalHome, List<ProviderPlaceId> orderedVisits, Set<ProviderPlaceId> requiredVisits,
        View before, View after, List<String> notices) {
    public record Eta(int originalLegIndex, Long minutes, String source, String dataMode, String verification,
            Instant retrievedAt, boolean cached) {}
    public record View(String feasibility, String arithmeticFeasibility, OffsetDateTime predictedReturnAt,
            Long remainingMarginSeconds, Long remainingMarginMinutes, Boolean returnDeadlineMet,
            Boolean safetyBufferSatisfied, Long currentRemainingStayMinutes, List<Eta> remainingLegs, List<String> reasons) {
        static View from(StayReplanResult.Projection p) {
            return new View(p.feasibility().name(), p.arithmeticFeasibility().name(), p.predictedReturnAt(),
                    p.remainingMargin() == null ? null : p.remainingMargin().getSeconds(), p.remainingMarginMinutes(),
                    p.returnDeadlineMet(), p.safetyBufferSatisfied(), p.currentRemainingStayMinutes(),
                    p.remainingLegs().stream().map(View::eta).toList(), p.reasons().stream().map(Enum::name).sorted().toList());
        }
        private static Eta eta(TravelLeg leg) {
            var e = leg.evidence();
            return new Eta(leg.index(), leg.minutes(), e == null ? null : e.source(), e == null ? null : e.dataMode().name(),
                    e == null ? null : e.verification().name(), e == null ? null : e.recordedAt(),
                    e != null && e.dataMode() == TravelLeg.DataMode.VERIFIED_CACHED);
        }
    }
    static ReplanPreviewResponse from(ReplanPlanStore.Entry entry, ReplanPreviewRequest request,
            StayReplanResult result, Instant calculatedAt) {
        var plan = entry.plan();
        return new ReplanPreviewResponse("PREVIEW_ONLY", entry.handle().revision(), entry.handle().expiresAt(), calculatedAt,
                "CLIENT_REPORTED_NOT_VERIFIED", request.currentVisitIndex(), plan.bufferMinutes, plan.deadline,
                plan.home(), plan.visits(), plan.requiredVisits(), View.from(result.before()), View.from(result.after()),
                List.of("NO_PLAN_COMMIT_OR_APPROVAL", "NO_PROVIDER_CALLS", "CACHED_ETAS_NOT_CURRENT_TRAFFIC",
                        "MATHEMATICAL_RESULT_NOT_RETURN_GUARANTEE", "PROGRESS_NOT_VEHICLE_VERIFIED",
                        "PRIVATE_LOOPBACK_DEVELOPMENT_ONLY_NOT_AUTHENTICATED"));
    }
}
