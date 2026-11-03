package com.tripback.backend.trip.validationapi;

import com.tripback.backend.trip.integration.TripValidationResult;
import com.tripback.backend.trip.provider.*;
import java.time.Instant;
import java.util.*;

/** Explicit allowlist projection. No raw provider response, request headers or credentials. */
public record TripValidationResponse(String feasibility, String dataCoverage, List<String> dataModes,
        List<Place> visits, List<Leg> legs, Long totalTravelMinutes, Long totalStayMinutes,
        String predictedReturnAt, Long remainingMarginMin, Boolean returnDeadlineMet, Boolean safetyBufferSatisfied,
        List<Integer> missingEtaLegIndexes, String routeDataStatus, List<String> unknownReasons,
        String calculationFailure, String notice) {
    public record Id(String provider, String value) {}
    public record Point(double latitude, double longitude) {}
    public record Source(String provider, String dataMode, String verification, Instant retrievedAt) {}
    public record Failure(String code, Integer httpStatus) {}
    public record ConfirmedPlace(Id id, String name, String address, Point coordinates, Source data) {}
    public record Place(int index, Id requestedId, long stayMinutes, ConfirmedPlace confirmedPlace,
            String identityStatus, String visitStatus, String searchStatus, Instant attemptedAt, String reason, Failure failure) {}
    public record Leg(int index, Point origin, Point destination, Long durationSeconds, String status,
            Source data, Instant attemptedAt, String reason, Failure failure) {}

    public static TripValidationResponse from(TripValidationResult result) {
        var calculation = result.calculation();
        List<Place> places = result.places().stream().map(p -> {
            var c = p.candidate();
            return new Place(p.index(), id(p.requested().selectedId()), p.requested().stayMinutes(),
                    c == null ? null : new ConfirmedPlace(id(c.id()), c.name(), c.address(), point(c.coordinates()), source(c.data())),
                    c == null ? "UNCONFIRMED" : "MATCHED_SELECTED_ID", "NOT_VERIFIED", name(p.searchStatus()),
                    p.attemptedAt(), name(p.reason()), failure(p.failure()));
        }).toList();
        List<Leg> legs = result.routes().stream().map(r -> {
            var response = r.response();
            // Rejected provider data must not be presented as a usable ETA or validated source.
            boolean usable = r.reason() == null;
            return new Leg(r.index(), r.query() == null ? null : point(r.query().origin()),
                    r.query() == null ? null : point(r.query().destination()),
                    usable && response != null ? response.durationSeconds() : null,
                    response == null ? "NOT_VALIDATED" : name(response.status()),
                    usable && response != null ? source(response.data()) : null,
                    response == null ? null : response.attemptedAt(), name(r.reason()),
                    response == null ? null : failure(response.failure()));
        }).toList();
        return new TripValidationResponse(result.feasibility().name(), result.dataCoverage().name(),
                result.dataModes().stream().map(Enum::name).sorted().toList(), places, legs,
                calculation == null ? null : calculation.totalTravelMinutes(), calculation == null ? null : calculation.totalStayMinutes(),
                calculation == null || calculation.predictedReturnAt() == null ? null : calculation.predictedReturnAt().toString(),
                calculation == null ? null : calculation.remainingMarginMin(), calculation == null ? null : calculation.returnDeadlineMet(),
                calculation == null ? null : calculation.safetyBufferSatisfied(),
                calculation == null ? null : calculation.missingEtaLegIndexes(), calculation == null ? null : calculation.routeDataStatus().name(),
                calculation == null ? List.of() : calculation.unknownReasons().stream().map(Enum::name).sorted().toList(),
                name(result.calculationFailure()), result.notice());
    }
    private static Id id(ProviderPlaceId id) { return new Id(id.provider(), id.value()); }
    private static Point point(Wgs84Point point) { return new Point(point.latitude(), point.longitude()); }
    static Source source(ProviderData data) {
        return data == null ? null : new Source(data.provider(), data.dataMode().name(), data.verification().name(), data.retrievedAt());
    }
    private static Failure failure(ProviderFailure failure) {
        return failure == null ? null : new Failure(failure.code().name(), failure.httpStatus());
    }
    private static String name(Enum<?> value) { return value == null ? null : value.name(); }
}
