package com.tripback.backend.trip.feasibility;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.tripback.backend.trip.feasibility.TripFeasibilityResult.Feasibility.*;
import static com.tripback.backend.trip.feasibility.TripFeasibilityResult.RouteDataStatus.*;
import static com.tripback.backend.trip.feasibility.TripFeasibilityResult.UnknownReason.*;

/** Pure Java calculation. No Spring bean, external service calls, itinerary mutation or REST wiring. */
public final class TripFeasibilityService {
    public TripFeasibilityResult calculate(TripFeasibilityInput input) {
        if (input == null) throw new IllegalArgumentException("Input is required");
        try {
            return calculateChecked(input);
        } catch (ArithmeticException | DateTimeException error) {
            throw new IllegalArgumentException("Total duration or return timestamp exceeds supported range", error);
        }
    }

    private TripFeasibilityResult calculateChecked(TripFeasibilityInput input) {
        long totalStay = 0;
        for (long minutes : input.stayMinutes()) totalStay = Math.addExact(totalStay, minutes);
        Math.multiplyExact(totalStay, 60L);

        int visitCount = input.stayMinutes().size();
        int requiredLegCount = Math.addExact(visitCount, 1);
        Map<Integer, TravelLeg> indexedLegs = new HashMap<>();
        long suppliedTravel = 0;
        for (TravelLeg leg : input.travelLegs()) {
            if (leg.index() > visitCount || indexedLegs.putIfAbsent(leg.index(), leg) != null) {
                throw new IllegalArgumentException("Duplicate or out-of-range leg index");
            }
            if (leg.minutes() != null) suppliedTravel = Math.addExact(suppliedTravel, leg.minutes());
        }
        // Even incomplete inputs must not hide invalid/overflowing supplied durations.
        long suppliedTotal = Math.addExact(totalStay, suppliedTravel);
        Math.multiplyExact(suppliedTotal, 60L);

        List<Integer> missing = new ArrayList<>();
        EnumSet<TripFeasibilityResult.UnknownReason> reasons = EnumSet.noneOf(TripFeasibilityResult.UnknownReason.class);
        if (input.travelLegs().size() != requiredLegCount) reasons.add(LEG_COUNT_MISMATCH);
        for (int index = 0; index < requiredLegCount; index++) {
            TravelLeg leg = indexedLegs.get(index);
            if (leg == null) {
                missing.add(index);
                reasons.add(MISSING_REQUIRED_LEG);
            } else if (leg.minutes() == null) {
                missing.add(index);
                reasons.add(MISSING_ETA);
            }
        }
        if (!missing.isEmpty()) {
            return new TripFeasibilityResult(UNKNOWN, INCOMPLETE, null, totalStay,
                    null, null, null, null, null, missing, reasons, input.travelLegs());
        }

        Instant returned = input.departAt().toInstant().plusSeconds(Math.multiplyExact(suppliedTotal, 60L));
        OffsetDateTime predicted = returned.atOffset(input.departAt().getOffset());
        Duration margin = Duration.between(returned, input.returnDeadline().toInstant());
        boolean deadlineMet = !margin.isNegative();
        boolean bufferMet = margin.compareTo(Duration.ofMinutes(input.safetyBufferMin())) >= 0;
        boolean allDeclaredVerified = input.travelLegs().stream()
                .allMatch(leg -> leg.evidence().verification() == TravelLeg.Verification.VERIFIED);
        // Duration seconds are already floored for negative fractions. No rounding enters the comparison.
        long marginMin = Math.floorDiv(margin.getSeconds(), 60L);
        return new TripFeasibilityResult(bufferMet ? FEASIBLE : INFEASIBLE,
                allDeclaredVerified ? ALL_ETAS_DECLARED_VERIFIED : UNVERIFIED,
                suppliedTravel, totalStay, predicted, margin, marginMin,
                deadlineMet, bufferMet, List.of(), Set.of(), input.travelLegs());
    }
}
