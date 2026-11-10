package com.tripback.backend.trip.replan;

import com.tripback.backend.trip.feasibility.*;
import java.time.*;
import java.util.*;
import static com.tripback.backend.trip.feasibility.TripFeasibilityResult.Feasibility.*;
import static com.tripback.backend.trip.replan.StayReplanResult.*;
import static com.tripback.backend.trip.replan.StayReplanResult.Reason.*;

/** Pure calculation: no Spring, providers, persistence, approval or navigation dependencies. */
public final class StayReplanService {
    private final TripFeasibilityService engine = new TripFeasibilityService();
    private final Duration maximumEtaAge;
    public StayReplanService() { this(Duration.ofMinutes(15)); }
    public StayReplanService(Duration maximumEtaAge) {
        if (maximumEtaAge == null || maximumEtaAge.isNegative()) throw new IllegalArgumentException("INVALID_ETA_AGE");
        this.maximumEtaAge = maximumEtaAge;
    }

    public StayReplanResult preview(StayReplanInput input) {
        Objects.requireNonNull(input);
        var before = project(input, input.plan().stays.get(input.currentVisitIndex()), false);
        try {
            boolean below = Math.multiplyExact(input.requestedTotalStayMinutes(), 60) < input.elapsedStaySeconds();
            var after = project(input, input.requestedTotalStayMinutes(), below);
            return new StayReplanResult(below ? ChangeStatus.TOTAL_STAY_BELOW_ELAPSED
                    : after.reasons().contains(CALCULATION_OUT_OF_RANGE) ? ChangeStatus.CALCULATION_OUT_OF_RANGE
                    : ChangeStatus.PREVIEW_ONLY, before, after);
        } catch (ArithmeticException error) {
            return new StayReplanResult(ChangeStatus.CALCULATION_OUT_OF_RANGE, before, unknown(CALCULATION_OUT_OF_RANGE));
        }
    }

    private Projection project(StayReplanInput input, long totalMinutes, boolean invalidChange) {
        try {
            var plan = input.plan();
            long remainingSeconds = Math.max(0, Math.subtractExact(Math.multiplyExact(totalMinutes, 60), input.elapsedStaySeconds()));
            long remainingMinutes = remainingSeconds / 60 + (remainingSeconds % 60 == 0 ? 0 : 1);
            List<Long> stays = new ArrayList<>(plan.stays.subList(input.currentVisitIndex(), plan.stays.size()));
            stays.set(0, remainingMinutes);
            // Current visit is already reached. Its incoming leg is not counted again.
            // Engine counts current stay plus future visits: a zero incoming leg represents being here.
            var synthetic = new TravelLeg.EtaEvidence("AT_CURRENT_VISIT", TravelLeg.DataMode.DEMO_SIMULATED,
                    TravelLeg.Verification.UNVERIFIED, input.observedAt().toInstant());
            List<TravelLeg> engineLegs = new ArrayList<>();
            engineLegs.add(new TravelLeg(0, 0L, synthetic));
            List<TravelLeg> remainingLegs = new ArrayList<>();
            Set<Reason> reasons = EnumSet.of(CACHED_ETA_ASSUMPTION);
            boolean trusted = true;
            for (int original = input.currentVisitIndex() + 1; original <= plan.visits.size(); original++) {
                final int index = original;
                var leg = plan.legs.stream().filter(l -> l.index() == index).findFirst().orElse(new TravelLeg(index, null, null));
                var evidence = leg.evidence();
                if (leg.minutes() == null) { reasons.add(MISSING_ETA); trusted = false; }
                if (evidence == null || evidence.verification() != TravelLeg.Verification.VERIFIED
                        || evidence.dataMode() == TravelLeg.DataMode.DEMO_SIMULATED) {
                    reasons.add(UNTRUSTED_ETA); trusted = false;
                } else {
                    Duration age = Duration.between(evidence.recordedAt(), input.observedAt().toInstant());
                    if (age.isNegative()) { reasons.add(FUTURE_ETA_TIMESTAMP); trusted = false; }
                    if (age.compareTo(maximumEtaAge) > 0) { reasons.add(STALE_ETA); trusted = false; }
                    // Reuse is cached, never a fresh road query. Preserve source and original time.
                    evidence = new TravelLeg.EtaEvidence(evidence.source(), TravelLeg.DataMode.VERIFIED_CACHED,
                            evidence.verification(), evidence.recordedAt());
                }
                var reused = new TravelLeg(original, leg.minutes(), evidence);
                remainingLegs.add(reused);
                engineLegs.add(new TravelLeg(original - input.currentVisitIndex(), leg.minutes(), evidence));
            }
            boolean overdue = plan.deadline.toInstant().isBefore(input.observedAt().toInstant());
            // Preserve the existing engine's public input rules. Only overdue margins need adaptation.
            var engineDeadline = overdue ? input.observedAt() : plan.deadline;
            var calculation = engine.calculate(new TripFeasibilityInput(input.observedAt(), engineDeadline,
                    plan.bufferMinutes, stays, engineLegs));
            var predicted = calculation.predictedReturnAt();
            Duration margin = predicted == null ? null : Duration.between(predicted.toInstant(), plan.deadline.toInstant());
            Boolean deadlineMet = margin == null ? null : !margin.isNegative();
            Boolean bufferMet = margin == null ? null : margin.compareTo(Duration.ofMinutes(plan.bufferMinutes)) >= 0;
            var arithmetic = margin == null ? UNKNOWN : Boolean.TRUE.equals(bufferMet) ? FEASIBLE : INFEASIBLE;
            if (overdue) reasons.add(DEADLINE_PASSED);
            if (Boolean.FALSE.equals(deadlineMet)) reasons.add(DEADLINE_EXCEEDED);
            else if (Boolean.FALSE.equals(bufferMet)) reasons.add(SAFETY_BUFFER_SHORTFALL);
            if (invalidChange) reasons.add(TOTAL_STAY_BELOW_ELAPSED);
            return new Projection(trusted && !invalidChange ? arithmetic : UNKNOWN, arithmetic, predicted, margin,
                    margin == null ? null : Math.floorDiv(margin.getSeconds(), 60), deadlineMet, bufferMet,
                    remainingMinutes, remainingLegs, reasons);
        } catch (IllegalArgumentException | ArithmeticException | DateTimeException error) {
            return unknown(CALCULATION_OUT_OF_RANGE);
        }
    }
    private Projection unknown(Reason reason) {
        return new Projection(UNKNOWN, UNKNOWN, null, null, null, null, null, null, List.of(), Set.of(reason));
    }
}
