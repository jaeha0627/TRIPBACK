package com.tripback.backend.trip.integration;

import com.tripback.backend.trip.feasibility.TripFeasibilityResult;
import com.tripback.backend.trip.provider.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TripValidationSpringTests {
    @Test void injectsProvidersWithoutAnyStartupCallsAndRunsOnlyWhenInvoked() {
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger routes = new AtomicInteger();
        new ApplicationContextRunner().withUserConfiguration(TripValidationService.class)
                .withBean(PlaceSearchProvider.class, () -> query -> {
                    searches.incrementAndGet();
                    return new PlaceSearchResult("KAKAO_LOCAL", PlaceSearchResult.Status.NO_RESULTS, List.of(), null, Instant.now());
                }).withBean(RoadRouteProvider.class, () -> query -> {
                    routes.incrementAndGet();
                    throw new IllegalStateException("Routing must stay blocked");
                }).run(context -> {
                    assertNull(context.getStartupFailure());
                    TripValidationService service = context.getBean(TripValidationService.class);
                    assertEquals(0, searches.get());
                    assertEquals(0, routes.get());
                    TripValidationInput input = TripValidationInput.fromIso8601(new Wgs84Point(37, 127),
                            "2026-11-07T08:00:00+09:00", "2026-11-07T18:00:00+09:00", 30,
                            List.of(new TripValidationInput.Visit("synthetic", new ProviderPlaceId("KAKAO_LOCAL", "fixture"), 60)));
                    assertEquals(TripFeasibilityResult.Feasibility.UNKNOWN, service.validate(input).feasibility());
                    assertEquals(1, searches.get());
                    assertEquals(0, routes.get());
                });
    }
}
