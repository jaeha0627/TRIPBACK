package com.tripback.backend.trip.recommendation;

import com.tripback.backend.trip.provider.*;
import com.tripback.backend.trip.integration.*;
import com.tripback.backend.trip.validationapi.TripValidationResponse;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.tripback.backend.trip.recommendation.RecommendationJson.*;
import static com.tripback.backend.trip.recommendation.RecommendationResponse.*;

@Service
public final class TripRecommendationService implements AutoCloseable {
    private final PlaceSearchProvider places;
    private final RoadRouteProvider routes;
    private final TripRecommendationModel model;
    private final ObjectMapper mapper;
    private final Duration timeout;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore concurrent = new Semaphore(2);
    @Autowired
    public TripRecommendationService(PlaceSearchProvider places, RoadRouteProvider routes, TripRecommendationModel model, ObjectMapper mapper) {
        this(places, routes, model, mapper, Duration.ofSeconds(180));
    }
    TripRecommendationService(PlaceSearchProvider places, RoadRouteProvider routes, TripRecommendationModel model, ObjectMapper mapper, Duration timeout) {
        this.places = places; this.routes = routes; this.model = model; this.mapper = mapper; this.timeout = timeout;
    }
    private final class Context {
        final RecommendationRequest request;
        final RecommendationBudget budget = new RecommendationBudget(timeout);
        final AtomicInteger generations = new AtomicInteger();
        final List<Alternative> accepted = new CopyOnWriteArrayList<>();
        final List<RejectedAlternative> rejected = new CopyOnWriteArrayList<>();
        final AtomicReference<AiProviderStatus> ai = new AtomicReference<>(new AiProviderStatus(model.provider(), "NOT_CALLED", "NOT_CALLED"));
        volatile CandidateCollector.Pool pool = new CandidateCollector.Pool(List.of(), List.of(), List.of());
        Context(RecommendationRequest request) { this.request = request; }
    }
    public RecommendationResponse recommend(RecommendationRequest request) {
        Objects.requireNonNull(request);
        Context context = new Context(request);
        if (!model.configured()) {
            context.ai.set(new AiProviderStatus(model.provider(), "NOT_CONFIGURED", "NOT_CALLED"));
            return response("AI_UNAVAILABLE", context);
        }
        if (!concurrent.tryAcquire()) return response("BUSY", context);
        Future<RecommendationResponse> pending;
        try {
            pending = workers.submit(() -> {
                try { return run(context); }
                catch (Rejected rejected) { context.rejected.add(new RejectedAlternative(-1, rejected.code, null)); return response("FAILED", context); }
                catch (RuntimeException unexpected) { context.rejected.add(new RejectedAlternative(-1, "INTERNAL_PROCESSING_FAILURE", null)); return response("FAILED", context); }
                finally { concurrent.release(); }
            });
        } catch (RejectedExecutionException closing) { concurrent.release(); return response("BUSY", context); }
        try { return pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); pending.cancel(true);
            context.rejected.add(new RejectedAlternative(-1, "PROCESSING_CANCELLED", null)); return response("CANCELLED", context);
        } catch (TimeoutException expired) {
            pending.cancel(true); context.rejected.add(new RejectedAlternative(-1, "PROCESSING_TIMEOUT", null));
            return response("PROCESSING_TIMEOUT", context);
        } catch (ExecutionException failed) {
            return response("FAILED", context);
        }
    }
    private RecommendationResponse run(Context context) {
        var request = context.request;
        Set<ProviderPlaceId> selected = new HashSet<>();
        PlaceSearchProvider boundedPlaces = query -> {
            // Only this recommendation's provider wrapper restores the original search scope.
            PlaceSearchQuery scoped = selected.isEmpty() ? query
                    : new PlaceSearchQuery(query.keyword(), request.targetCenter(), 20_000, query.page(), query.size());
            context.budget.search(); PlaceSearchResult result = places.search(scoped); context.budget.checkTime();
            if (!selected.isEmpty() && result != null && result.status() == PlaceSearchResult.Status.FOUND) {
                boolean invalid = !"KAKAO_LOCAL".equals(result.provider()) || result.candidates().size() > 15
                        || result.candidates().stream().filter(p -> selected.contains(p.id()))
                        .anyMatch(p -> !CandidateCollector.valid(p) || !RegionDistance.contains(request.targetCenter(), p.coordinates())
                                || context.pool.candidates().stream().noneMatch(original -> original.place().id().equals(p.id())
                                        && original.place().coordinates().equals(p.coordinates())));
                if (invalid) return new PlaceSearchResult("KAKAO_LOCAL", PlaceSearchResult.Status.FAILED, List.of(),
                        ProviderFailure.of(ProviderFailure.Code.INVALID_DATA), result.attemptedAt());
            }
            return result;
        };
        RoadRouteProvider boundedRoutes = query -> {
            context.budget.route(); RoadRouteResult result = routes.route(query); context.budget.checkTime(); return result;
        };
        context.pool = new CandidateCollector().collect(request, boundedPlaces, context.budget);
        Map<ProviderPlaceId, CandidateCollector.Candidate> candidates = new LinkedHashMap<>();
        context.pool.candidates().forEach(c -> candidates.put(c.place().id(), c));
        if (candidates.isEmpty()) return response("NO_CANDIDATES", context);
        if (!candidates.keySet().containsAll(request.mustVisitPlaceIds())) {
            context.rejected.add(new RejectedAlternative(-1, "REQUIRED_PLACE_UNAVAILABLE", null)); return response("NO_ELIGIBLE_CANDIDATES", context);
        }
        context.budget.checkTime(); context.generations.incrementAndGet();
        context.ai.set(new AiProviderStatus(model.provider(), "REQUESTED", "UNCONFIRMED"));
        TripRecommendationModel.Generation generation;
        try {
            generation = model.generate(new TripRecommendationModel.Prompt(request.modelPreferences(mapper), candidates.values().stream().map(c ->
                    new TripRecommendationModel.PromptCandidate(c.place().id().provider(), c.place().id().value(), c.place().name(), c.place().address())).toList()));
        } catch (RuntimeException failed) {
            context.ai.set(new AiProviderStatus(model.provider(), "PROVIDER_EXCEPTION", "UNCONFIRMED")); return response("AI_FAILED", context);
        }
        if (generation == null) { context.ai.set(new AiProviderStatus(model.provider(), "INVALID_RESPONSE", "UNCONFIRMED")); return response("AI_FAILED", context); }
        context.ai.set(new AiProviderStatus(model.provider(), generation.status().name(), generation.invocation().name()));
        context.budget.checkTime();
        if (generation.status() != TripRecommendationModel.Status.COMPLETED) return response("AI_FAILED", context);
        List<JsonNode> nodes;
        try { nodes = ModelPlanParser.planNodes(generation.json(), mapper); }
        catch (Rejected invalid) { context.rejected.add(new RejectedAlternative(-1, invalid.code, null)); return response("INVALID_MODEL_OUTPUT", context); }
        if (nodes.isEmpty()) context.rejected.add(new RejectedAlternative(-1, "MODEL_RETURNED_NO_ALTERNATIVES", null));
        Set<List<ModelPlanParser.Visit>> distinctPlans = new HashSet<>();
        TripValidationService validationService = new TripValidationService(boundedPlaces, boundedRoutes);
        for (int index = 0; index < nodes.size(); index++) {
            context.budget.checkTime();
            ModelPlanParser.Plan plan;
            try {
                plan = ModelPlanParser.plan(nodes.get(index), candidates.keySet(), request.mustVisitPlaceIds());
                check(distinctPlans.add(plan.visits()), "DUPLICATE_ALTERNATIVE");
            } catch (Rejected invalid) { context.rejected.add(new RejectedAlternative(index, invalid.code, null)); continue; }
            selected.clear(); plan.visits().forEach(v -> selected.add(v.id()));
            List<TripValidationInput.Visit> visits = plan.visits().stream().map(v ->
                    new TripValidationInput.Visit(candidates.get(v.id()).keyword(), v.id(), v.stayMinutes())).toList();
            TripValidationResult validated = validationService.validate(new TripValidationInput(request.origin(), request.departAt(),
                    request.returnDeadline(), request.safetyBufferMin(), visits));
            context.budget.checkTime();
            TripValidationResponse validation = TripValidationResponse.from(validated);
            List<OrderedVisit> order = new ArrayList<>();
            for (int i = 0; i < plan.visits().size(); i++) {
                var visit = plan.visits().get(i);
                order.add(new OrderedVisit(i, visit.id(), candidates.get(visit.id()).keyword(), visit.stayMinutes()));
            }
            Alternative alternative = new Alternative("ai-proposal-" + (index + 1), plan.title(), plan.reason(), List.copyOf(order), validation,
                    validation.feasibility(), validation.dataModes(), List.of("AI_REASON_UNVERIFIED", "CURRENT_ETAS_ONLY_NOT_RETURN_GUARANTEE", "VISITABILITY_NOT_VERIFIED"));
            String rejected = rejection(validated, request.maximumOneWayMinutes());
            if (rejected == null) context.accepted.add(alternative);
            else context.rejected.add(new RejectedAlternative(index, rejected, alternative));
        }
        return response(context.accepted.size() == 2 ? "COMPLETED" : context.accepted.size() == 1 ? "PARTIAL" : "NO_FEASIBLE_ALTERNATIVES", context);
    }
    private String rejection(TripValidationResult result, int maximumOneWayMinutes) {
        if (result.feasibility() == com.tripback.backend.trip.feasibility.TripFeasibilityResult.Feasibility.UNKNOWN) return "VALIDATION_UNKNOWN";
        if (result.feasibility() == com.tripback.backend.trip.feasibility.TripFeasibilityResult.Feasibility.INFEASIBLE) return "VALIDATION_INFEASIBLE";
        var legs = result.calculation().evaluatedLegs();
        if (legs.getFirst().minutes() > maximumOneWayMinutes || legs.getLast().minutes() > maximumOneWayMinutes) return "MAXIMUM_ONE_WAY_EXCEEDED";
        return null;
    }
    private RecommendationResponse response(String status, Context context) {
        Map<String, String> conditions = new LinkedHashMap<>();
        conditions.put("targetCenterRadius", context.pool.searches().isEmpty() ? "NOT_APPLIED" : "SERVER_FILTERED_CANDIDATES_WGS84_20KM");
        conditions.put("maximumOneWayMinutes", "FIRST_OUTBOUND_AND_FINAL_RETURN_LEGS_ONLY");
        conditions.put("vehicleType", "PREFERENCE_ONLY_DEFAULT_CAR_ROUTE_NOT_VEHICLE_SPECIFIC");
        conditions.put("interests", "SEARCH_QUERY_AND_MODEL_PREFERENCE_NOT_GUARANTEED");
        String hint = context.generations.get() == 0 ? "NOT_APPLIED" : "MODEL_HINT_ONLY_NOT_VERIFIED";
        conditions.put("companionType", hint);
        context.request.preferences().keySet().forEach(key -> conditions.put(key, hint));
        conditions.put("mustVisitPlaceIds", "CHECKED_AGAINST_EVERY_ACCEPTED_PLAN");
        return new RecommendationResponse(status, context.ai.get(), List.copyOf(context.accepted), List.copyOf(context.rejected),
                context.pool.candidates().stream().map(RecommendationResponse.Candidate::from).toList(), context.pool.searches(), context.pool.excluded(),
                new Calls(context.budget.searches.get(), context.budget.routes.get(), context.generations.get()), Collections.unmodifiableMap(conditions),
                List.of("USER_COORDINATES_NOT_LOCATION_AUTHENTICATION", "AI_REASON_UNVERIFIED_NOT_PROVIDER_FACTS", "CURRENT_DEPARTURE_ETAS_NOT_FUTURE_TRAFFIC",
                        "MATHEMATICAL_FEASIBILITY_NOT_RETURN_GUARANTEE", "OPENING_PARKING_PRICES_WALKING_AMENITIES_NOT_VERIFIED",
                        "FIRST_PAGE_15_PER_QUERY_MAX_30_CANDIDATES_NO_AUTO_RETRY", "ONE_WAY_LIMIT_ONLY_FIRST_AND_LAST_LEGS_NOT_ALL_ORIGIN_TO_STOP_DISTANCES",
                        "NO_NAVIGATION_DISPATCH_OR_PLAN_COMMIT", "PRIVATE_LOOPBACK_DEVELOPMENT_ONLY_AUTH_AND_RATE_LIMIT_REQUIRED"));
    }
    @Override @PreDestroy public void close() { workers.shutdownNow(); }
}
