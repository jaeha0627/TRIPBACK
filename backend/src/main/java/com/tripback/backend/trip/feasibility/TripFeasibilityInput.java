package com.tripback.backend.trip.feasibility;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/** Independent engine input, not a REST DTO. Visits and legs are never removed or invented. */
public record TripFeasibilityInput(
        OffsetDateTime departAt,
        OffsetDateTime returnDeadline,
        long safetyBufferMin,
        List<Long> stayMinutes,
        List<TravelLeg> travelLegs
) {
    public TripFeasibilityInput {
        if (departAt == null || returnDeadline == null || stayMinutes == null || travelLegs == null) {
            throw new IllegalArgumentException("Dates and both input lists are required");
        }
        if (returnDeadline.toInstant().isBefore(departAt.toInstant())) {
            throw new IllegalArgumentException("Deadline must not precede departure");
        }
        checkMinutes(safetyBufferMin, "Safety buffer");
        for (Long minutes : stayMinutes) {
            if (minutes == null) throw new IllegalArgumentException("Stay time must not be missing");
            checkMinutes(minutes, "Stay minutes");
        }
        if (travelLegs.stream().anyMatch(leg -> leg == null)) {
            throw new IllegalArgumentException("Use an omitted index or null ETA, not a null leg object");
        }
        stayMinutes = List.copyOf(stayMinutes);
        travelLegs = List.copyOf(travelLegs);
    }

    public static TripFeasibilityInput fromIso8601(String departAt, String returnDeadline,
            long safetyBufferMin, List<Long> stayMinutes, List<TravelLeg> travelLegs) {
        if (departAt == null || returnDeadline == null) throw new IllegalArgumentException("Dates are required");
        try {
            return new TripFeasibilityInput(
                    OffsetDateTime.parse(departAt, DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    OffsetDateTime.parse(returnDeadline, DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    safetyBufferMin, stayMinutes, travelLegs);
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("Valid ISO 8601 dates with explicit UTC offsets are required", error);
        }
    }

    static void checkMinutes(long value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must be nonnegative");
        try {
            Math.multiplyExact(value, 60L);
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(field + " exceeds supported duration range", error);
        }
    }
}
