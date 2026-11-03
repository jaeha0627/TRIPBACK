package com.tripback.backend.trip;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record TripPlanRequest(
        @NotBlank @Size(max = 100) String region,
        @NotBlank String departAt,
        @NotBlank String returnDeadline,
        @NotNull @Digits(integer = 2, fraction = 0) @Min(15) @Max(45) BigDecimal safetyBufferMin,
        @NotNull @Valid Companions companions,
        @NotNull @Size(max = 8) Set<@NotNull Interest> interests,
        @NotNull VehicleType vehicleType,
        @NotNull @Digits(integer = 3, fraction = 0) @Min(1) @Max(180) BigDecimal maximumOneWayMinutes,
        @Valid Origin origin,
        @Pattern(regexp = "DEMO_SIMULATED") String dataMode,
        // Reject unsupported required stops/constraints instead of silently dropping them.
        @Size(max = 0) List<@NotBlank String> mustVisitPlaceIds,
        @Size(max = 0) Map<String, Object> constraints
) {
    public record Companions(@NotNull CompanionType type, ChildAgeGroup childAgeGroup) {}

    public record Origin(
            @NotBlank String name,
            @Size(max = 200) String placeId,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude
    ) {}

    public enum CompanionType { FAMILY, COUPLE, FRIENDS, PARENTS, SOLO }
    public enum ChildAgeGroup { INFANT, ELEMENTARY, TEEN }
    public enum Interest { EXPERIENCE, FOOD, NATURE, CAFE, CULTURE, SHOPPING, SPA, PHOTO }
    public enum VehicleType { EV, ICE, HEV, LPG, FCEV }
}