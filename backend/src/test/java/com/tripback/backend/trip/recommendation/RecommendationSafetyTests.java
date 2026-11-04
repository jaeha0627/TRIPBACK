package com.tripback.backend.trip.recommendation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import java.util.concurrent.*;
import static com.tripback.backend.trip.recommendation.RecommendationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class RecommendationSafetyTests {
    @Test void remoteClientsAreRejectedBeforePaidServiceCalls() throws Exception {
        var service = mock(TripRecommendationService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new TripRecommendationController(service, MAPPER)).build();
        var response = mvc.perform(post("/api/v1/trips/recommend").contentType(MediaType.APPLICATION_JSON).content(REQUEST)
                .with(request -> { request.setRemoteAddr("203.0.113.1"); return request; }))
                .andExpect(status().isForbidden()).andReturn().getResponse();
        assertEquals("{\"code\":\"LOCAL_DEVELOPMENT_ONLY\"}", response.getContentAsString());
        verifyNoInteractions(service);
    }
    @Test void unexpectedControllerFailureNeverLeaksSecrets(CapturedOutput output) throws Exception {
        String secret = "synthetic-internal-" + UUID.randomUUID();
        var service = mock(TripRecommendationService.class); when(service.recommend(any())).thenThrow(new IllegalStateException(secret));
        var mvc = MockMvcBuilders.standaloneSetup(new TripRecommendationController(service, MAPPER)).build();
        var response = mvc.perform(post("/api/v1/trips/recommend").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isInternalServerError()).andReturn().getResponse();
        assertEquals("{\"code\":\"INTERNAL_ERROR\"}", response.getContentAsString()); assertFalse(output.getAll().contains(secret));
    }
    @Test void atMostTwoRecommendationWorkersCanConsumeProvidersConcurrently() throws Exception {
        try (var f = new RecommendationFixtures(); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
            f.model.handler = prompt -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return generation(twoPlans());
            };
            Future<?> first = callers.submit(() -> f.service.recommend(input()));
            Future<?> second = callers.submit(() -> f.service.recommend(input()));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                int searches = f.places.calls.size();
                var blocked = f.service.recommend(input()); assertEquals("BUSY", blocked.recommendationStatus());
                assertEquals(2, f.model.calls.get()); assertEquals(searches, f.places.calls.size());
            } finally { release.countDown(); }
            first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
        }
    }
    @Test void budgetGuardsDoNotCountOrStartMoreThanTheirActualCallCaps() {
        var budget = new RecommendationBudget(java.time.Duration.ofSeconds(1));
        for (int i = 0; i < 18; i++) budget.search();
        assertThrows(RecommendationJson.Rejected.class, budget::search); assertEquals(18, budget.searches.get());
        for (int i = 0; i < 12; i++) budget.route();
        assertThrows(RecommendationJson.Rejected.class, budget::route); assertEquals(12, budget.routes.get());
    }
}
