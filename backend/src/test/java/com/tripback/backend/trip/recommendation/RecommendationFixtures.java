package com.tripback.backend.trip.recommendation;

import com.tripback.backend.trip.provider.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

final class RecommendationFixtures implements AutoCloseable {
    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
    static final String REQUEST = """
        {"origin":{"latitude":36.7,"longitude":127.2},"targetAreaKeyword":"천안 독립기념관",
         "targetCenter":{"latitude":36.75,"longitude":127.25},"departAt":"2026-11-07T08:00:00+09:00",
         "returnDeadline":"2026-11-07T18:00:00+09:00","safetyBufferMin":30,
         "companionType":"SOLO","interests":["CULTURE","FOOD","CAFE"],"vehicleType":"EV","maximumOneWayMinutes":120}
        """;
    static RecommendationRequest input(String json) { return RecommendationRequest.decode(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), MAPPER); }
    static RecommendationRequest input() { return input(REQUEST); }
    static ProviderData demo(String provider) { return new ProviderData(provider, ProviderData.DataMode.DEMO_SIMULATED, ProviderData.Verification.UNVERIFIED, NOW); }
    static PlaceCandidate place(String id, double lat, double lon) {
        return new PlaceCandidate(new ProviderPlaceId("KAKAO_LOCAL", id), "동일한 가상 이름", "가상 주소", new Wgs84Point(lat, lon), demo("KAKAO_LOCAL"));
    }
    static PlaceCandidate place(String id) { return place(id, 36.75, 127.25); }
    static PlaceSearchResult found(PlaceCandidate... places) { return new PlaceSearchResult("KAKAO_LOCAL", PlaceSearchResult.Status.FOUND, List.of(places), null, NOW); }
    static String plan(String title, long stay, String... ids) {
        var node = MAPPER.createObjectNode(); node.put("title", title); node.put("recommendationReason", "가상 모델의 검증되지 않은 설명");
        var visits = node.putArray("orderedVisits");
        for (String id : ids) { var v = visits.addObject(); v.put("provider", "KAKAO_LOCAL"); v.put("placeId", id); v.put("stayMinutes", stay); }
        return node.toString();
    }
    static String plans(String... plans) { return "{\"plans\":[" + String.join(",", plans) + "]}"; }
    static String twoPlans() { return plans(plan("여유형", 30, "001", "002"), plan("다른 순서", 30, "002", "001")); }
    static TripRecommendationModel.Generation generation(String json) {
        return new TripRecommendationModel.Generation(TripRecommendationModel.Status.COMPLETED, TripRecommendationModel.Invocation.FAKE_MODEL, json);
    }
    static final class FakePlaces implements PlaceSearchProvider {
        final List<PlaceSearchQuery> calls = Collections.synchronizedList(new ArrayList<>());
        volatile Function<PlaceSearchQuery, PlaceSearchResult> handler = query -> found(place("001"), place("002"), place("003"));
        public PlaceSearchResult search(PlaceSearchQuery query) { calls.add(query); return handler.apply(query); }
    }
    static final class FakeRoutes implements RoadRouteProvider {
        final List<RoadRouteQuery> calls = Collections.synchronizedList(new ArrayList<>());
        volatile Function<RoadRouteQuery, RoadRouteResult> handler = query -> new RoadRouteResult("KAKAO_MOBILITY", query,
                RoadRouteResult.Status.FOUND, 600L, demo("KAKAO_MOBILITY"), null, NOW);
        public RoadRouteResult route(RoadRouteQuery query) { calls.add(query); return handler.apply(query); }
    }
    static final class FakeModel implements TripRecommendationModel {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean ready = true;
        volatile Prompt lastPrompt;
        volatile Function<Prompt, Generation> handler = prompt -> generation(twoPlans());
        public String provider() { return "FAKE"; }
        public boolean configured() { return ready; }
        public Generation generate(Prompt prompt) { calls.incrementAndGet(); lastPrompt = prompt; return handler.apply(prompt); }
    }
    final FakePlaces places = new FakePlaces(); final FakeRoutes routes = new FakeRoutes(); final FakeModel model = new FakeModel();
    final TripRecommendationService service;
    RecommendationFixtures() { this(Duration.ofSeconds(10)); }
    RecommendationFixtures(Duration timeout) { service = new TripRecommendationService(places, routes, model, MAPPER, timeout); }
    public void close() { service.close(); }
}
