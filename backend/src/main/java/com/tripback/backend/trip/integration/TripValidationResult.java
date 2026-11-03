package com.tripback.backend.trip.integration;

import com.tripback.backend.trip.feasibility.TripFeasibilityResult;
import com.tripback.backend.trip.provider.*;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Mathematical feasibility under supplied current-departure ETAs, never a real return guarantee.
 * Provider-data validation does not verify future traffic, origin identity, opening hours or visitability.
 */
public record TripValidationResult(TripValidationInput input, List<PlaceCheck> places, List<RouteCheck> routes,
        TripFeasibilityResult calculation, Reason calculationFailure) {
    public TripValidationResult {
        if (input == null || places == null || routes == null
                || places.size() != input.visits().size() || routes.size() != input.visits().size() + 1) {
            throw new IllegalArgumentException("All requested visits and round-trip legs are required");
        }
        places = List.copyOf(places);
        routes = List.copyOf(routes);
        if ((calculation == null && calculationFailure != Reason.CALCULATION_OVERFLOW)
                || (calculation != null && calculationFailure != null)) {
            throw new IllegalArgumentException("Calculation or explicit overflow outcome is required");
        }
    }

    public TripFeasibilityResult.Feasibility feasibility() {
        return calculation == null ? TripFeasibilityResult.Feasibility.UNKNOWN : calculation.feasibility();
    }

    /** Includes both place and route evidence; never relabels mixed sources as LIVE. */
    public DataCoverage dataCoverage() {
        boolean complete = places.stream().allMatch(p -> p.reason() == null)
                && routes.stream().allMatch(r -> r.reason() == null) && calculationFailure == null;
        return coverageOf(evidence().toList(), complete);
    }

    // Pure metadata aggregation: does not validate or create any place/ETA evidence.
    static DataCoverage coverageOf(List<ProviderData> evidence, boolean complete) {
        if (!complete || evidence.isEmpty()) return DataCoverage.INCOMPLETE;
        return evidence.stream().anyMatch(data -> data.dataMode() == ProviderData.DataMode.DEMO_SIMULATED)
                ? DataCoverage.CONTAINS_DEMO : DataCoverage.ALL_PROVIDER_DATA_DECLARED_VALIDATED;
    }

    public Set<ProviderData.DataMode> dataModes() {
        return evidence().map(ProviderData::dataMode).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Stream<ProviderData> evidence() {
        return Stream.concat(places.stream().filter(p -> p.candidate() != null).map(p -> p.candidate().data()),
                routes.stream().filter(r -> r.response() != null && r.response().data() != null).map(r -> r.response().data()));
    }

    public String notice() {
        return "MATHEMATICAL_RESULT_ONLY; CURRENT_DEPARTURE_ETAS_NOT_FUTURE_TRAFFIC; "
                + "RETURN_NOT_GUARANTEED; ORIGIN_COORDINATES_ONLY; VISITABILITY_NOT_VERIFIED";
    }

    public record PlaceCheck(int index, TripValidationInput.Visit requested, PlaceCandidate candidate,
            PlaceSearchResult.Status searchStatus, Instant attemptedAt, Reason reason, ProviderFailure failure) {}

    /** response retains original status, seconds and provenance; query is the expected leg.
     * query is null only when unresolved places prevented all routing. No coordinates are invented.
     */
    public record RouteCheck(int index, RoadRouteQuery query, RoadRouteResult response, Reason reason) {}

    public enum DataCoverage { INCOMPLETE, CONTAINS_DEMO, ALL_PROVIDER_DATA_DECLARED_VALIDATED }
    public enum Reason {
        PLACE_NO_RESULTS, SELECTED_PLACE_NOT_FOUND, PLACE_SEARCH_UNAVAILABLE, PLACE_SEARCH_FAILED,
        INVALID_PLACE_RESPONSE, PLACE_PROVIDER_EXCEPTION, PLACES_UNRESOLVED,
        NO_ROUTE, ETA_MISSING, ROUTE_UNAVAILABLE, ROUTE_FAILED, INVALID_ROUTE_RESPONSE,
        ROUTE_PROVIDER_EXCEPTION, DURATION_OVERFLOW, CALCULATION_OVERFLOW
    }
}
