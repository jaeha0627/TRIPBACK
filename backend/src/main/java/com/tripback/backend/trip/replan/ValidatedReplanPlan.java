package com.tripback.backend.trip.replan;

import com.tripback.backend.trip.feasibility.*;
import com.tripback.backend.trip.integration.TripValidationResult;
import com.tripback.backend.trip.provider.*;
import java.time.OffsetDateTime;
import java.util.*;

/** Internal server snapshot, never a request DTO or proof of authenticated vehicle position.
 * Future HTTP code must load this from server-owned validation, not deserialize client evidence.
 */
public final class ValidatedReplanPlan {
    final Wgs84Point home;
    final OffsetDateTime deadline;
    final long bufferMinutes;
    final List<ProviderPlaceId> visits;
    final Set<ProviderPlaceId> required;
    final List<Long> stays;
    final List<TravelLeg> legs;

    // Package-private for trusted internal fixtures; no public client-supplied ETA constructor.
    ValidatedReplanPlan(Wgs84Point home, OffsetDateTime deadline, long bufferMinutes,
            List<ProviderPlaceId> visits, Set<ProviderPlaceId> required, List<Long> stays, List<TravelLeg> legs) {
        this.home = Objects.requireNonNull(home);
        this.deadline = Objects.requireNonNull(deadline);
        this.visits = List.copyOf(visits);
        this.required = Set.copyOf(required);
        this.stays = List.copyOf(stays);
        this.legs = List.copyOf(legs);
        this.bufferMinutes = bufferMinutes;
        if (visits.isEmpty() || visits.size() != stays.size() || new HashSet<>(visits).size() != visits.size()
                || !visits.containsAll(required)) throw new IllegalArgumentException("INVALID_PLAN");
        new TripFeasibilityInput(deadline, deadline, bufferMinutes, stays, legs);
        Set<Integer> indexes = new HashSet<>();
        for (var leg : legs) if (leg.index() > visits.size() || !indexes.add(leg.index()))
            throw new IllegalArgumentException("INVALID_LEG_INDEX");
    }

    /** Accept only completed server validation. This method does not authenticate serialized input. */
    public static ValidatedReplanPlan fromServerValidation(TripValidationResult result, Set<ProviderPlaceId> required) {
        Objects.requireNonNull(result);
        if (result.feasibility() != TripFeasibilityResult.Feasibility.FEASIBLE
                || result.dataCoverage() != TripValidationResult.DataCoverage.ALL_PROVIDER_DATA_DECLARED_VALIDATED)
            throw new IllegalArgumentException("SERVER_VALIDATED_PLAN_REQUIRED");
        var input = result.input();
        for (int i = 0; i < input.visits().size(); i++) {
            var p = result.places().get(i);
            if (p.index() != i || p.candidate() == null || p.reason() != null || p.failure() != null
                    || p.searchStatus() != PlaceSearchResult.Status.FOUND
                    || p.candidate().data().verification() != ProviderData.Verification.VALIDATED_PROVIDER_DATA
                    || !p.requested().equals(input.visits().get(i))
                    || !p.candidate().id().equals(input.visits().get(i).selectedId()))
                throw new IllegalArgumentException("INVALID_PLACE_IDENTITY");
        }
        List<TravelLeg> legs = new ArrayList<>();
        for (int i = 0; i <= input.visits().size(); i++) {
            var r = result.routes().get(i);
            var origin = i == 0 ? input.origin() : result.places().get(i - 1).candidate().coordinates();
            var destination = i == input.visits().size() ? input.origin() : result.places().get(i).candidate().coordinates();
            if (r.index() != i || !new RoadRouteQuery(origin, destination).equals(r.query()) || r.reason() != null
                    || r.response() == null || r.response().status() != RoadRouteResult.Status.FOUND
                    || !"KAKAO_MOBILITY".equals(r.response().provider()) || r.response().failure() != null
                    || r.response().data() == null
                    || r.response().data().verification() != ProviderData.Verification.VALIDATED_PROVIDER_DATA)
                throw new IllegalArgumentException("INVALID_ROUTE_CHAIN");
            legs.add(RouteLegMapper.toTravelLeg(i, r.query(), r.response()));
        }
        var recalculated = new TripFeasibilityService().calculate(new TripFeasibilityInput(input.departAt(),
                input.returnDeadline(), input.safetyBufferMin(), input.visits().stream().map(v -> v.stayMinutes()).toList(), legs));
        if (!recalculated.equals(result.calculation())) throw new IllegalArgumentException("INCONSISTENT_SERVER_CALCULATION");
        return new ValidatedReplanPlan(input.origin(), input.returnDeadline(), input.safetyBufferMin(),
                input.visits().stream().map(v -> v.selectedId()).toList(), required,
                input.visits().stream().map(v -> v.stayMinutes()).toList(), legs);
    }

    public Wgs84Point home() { return home; }
    public List<ProviderPlaceId> visits() { return visits; }
    public Set<ProviderPlaceId> requiredVisits() { return required; }
    public List<Long> stayMinutes() { return stays; }
    public List<TravelLeg> originalLegs() { return legs; }
}
