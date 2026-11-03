package com.tripback.backend.trip.integration;

import com.tripback.backend.trip.feasibility.*;
import com.tripback.backend.trip.provider.*;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.tripback.backend.trip.integration.TripValidationResult.*;
import static com.tripback.backend.trip.integration.TripValidationResult.Reason.*;

/** Internal orchestration only. Construction never reads keys or calls providers. */
@Service
public final class TripValidationService {
    private final PlaceSearchProvider places;
    private final RoadRouteProvider routes;
    private final TripFeasibilityService engine = new TripFeasibilityService();

    public TripValidationService(PlaceSearchProvider places, RoadRouteProvider routes) {
        this.places = Objects.requireNonNull(places);
        this.routes = Objects.requireNonNull(routes);
    }

    public TripValidationResult validate(TripValidationInput input) {
        if (input == null) throw new IllegalArgumentException("INVALID_INPUT");
        List<PlaceCheck> checked = new ArrayList<>();
        for (int i = 0; i < input.visits().size(); i++) checked.add(checkPlace(i, input.visits().get(i)));
        List<RouteCheck> routeChecks = new ArrayList<>();
        List<TravelLeg> legs = new ArrayList<>();
        boolean resolved = checked.stream().allMatch(p -> p.reason() == null);
        for (int i = 0; i <= input.visits().size(); i++) {
            if (!resolved) {
                routeChecks.add(new RouteCheck(i, null, null, PLACES_UNRESOLVED));
                legs.add(new TravelLeg(i, null, null));
                continue;
            }
            Wgs84Point origin = i == 0 ? input.origin() : checked.get(i - 1).candidate().coordinates();
            Wgs84Point destination = i == checked.size() ? input.origin() : checked.get(i).candidate().coordinates();
            RouteCheck check = checkRoute(i, new RoadRouteQuery(origin, destination));
            TravelLeg leg;
            try {
                leg = RouteLegMapper.toTravelLeg(i, check.query(), check.reason() == null ? check.response() : null);
            } catch (IllegalArgumentException overflow) {
                check = new RouteCheck(i, check.query(), check.response(), DURATION_OVERFLOW);
                leg = RouteLegMapper.toTravelLeg(i, check.query(), null);
            }
            routeChecks.add(check);
            legs.add(leg);
        }
        try {
            TripFeasibilityResult calculated = engine.calculate(new TripFeasibilityInput(input.departAt(), input.returnDeadline(),
                    input.safetyBufferMin(), input.visits().stream().map(TripValidationInput.Visit::stayMinutes).toList(), legs));
            return new TripValidationResult(input, checked, routeChecks, calculated, null);
        } catch (IllegalArgumentException overflow) {
            // Input and indexes were already validated. Remaining engine failure is aggregate/time overflow.
            return new TripValidationResult(input, checked, routeChecks, null, CALCULATION_OVERFLOW);
        }
    }

    private PlaceCheck checkPlace(int index, TripValidationInput.Visit visit) {
        PlaceSearchResult response;
        try {
            response = places.search(new PlaceSearchQuery(visit.keyword(), null, null, 1, 15));
        } catch (RuntimeException failure) {
            // Provider exception text/causes may contain credentials. Never forward or log them.
            return new PlaceCheck(index, visit, null, null, null, PLACE_PROVIDER_EXCEPTION, null);
        }
        if (response == null || !"KAKAO_LOCAL".equals(response.provider())) {
            return new PlaceCheck(index, visit, null, null, null, INVALID_PLACE_RESPONSE, null);
        }
        Reason reason = switch (response.status()) {
            case NO_RESULTS -> PLACE_NO_RESULTS;
            case UNAVAILABLE -> PLACE_SEARCH_UNAVAILABLE;
            case FAILED -> PLACE_SEARCH_FAILED;
            case FOUND -> null;
        };
        PlaceCandidate candidate = null;
        if (reason == null) {
            HashSet<ProviderPlaceId> ids = new HashSet<>();
            boolean duplicate = response.candidates().stream().anyMatch(p -> !ids.add(p.id()));
            List<PlaceCandidate> matches = response.candidates().stream().filter(p -> visit.selectedId().equals(p.id())).toList();
            if (duplicate) reason = INVALID_PLACE_RESPONSE;
            else if (matches.size() != 1) reason = SELECTED_PLACE_NOT_FOUND;
            else candidate = matches.getFirst(); // Exact ID match, never the first search hit.
        }
        return new PlaceCheck(index, visit, candidate, response.status(), response.attemptedAt(), reason, response.failure());
    }

    private RouteCheck checkRoute(int index, RoadRouteQuery query) {
        RoadRouteResult response;
        try {
            response = routes.route(query);
        } catch (RuntimeException failure) {
            return new RouteCheck(index, query, null, ROUTE_PROVIDER_EXCEPTION);
        }
        if (response == null || !"KAKAO_MOBILITY".equals(response.provider()) || !query.equals(response.query())) {
            return new RouteCheck(index, query, response, INVALID_ROUTE_RESPONSE);
        }
        Reason reason = switch (response.status()) {
            case FOUND -> null;
            case NO_ROUTE -> NO_ROUTE;
            case ETA_MISSING -> ETA_MISSING;
            case UNAVAILABLE -> ROUTE_UNAVAILABLE;
            case FAILED -> ROUTE_FAILED;
        };
        return new RouteCheck(index, query, response, reason);
    }
}
