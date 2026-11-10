package com.tripback.backend.trip.recommendation;

import org.junit.jupiter.api.Test;
import java.util.List;
import static com.tripback.backend.trip.recommendation.RecommendationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class RecommendationRejectionDiagnosticTests {
    private RecommendationRequest foodCulture() {
        return input(REQUEST.replace("[\"CULTURE\",\"FOOD\",\"CAFE\"]", "[\"FOOD\",\"CULTURE\"]"));
    }
    private void assertTwoParserRejections(String code, String badPlan) {
        try (var f = new RecommendationFixtures()) {
            f.model.handler = prompt -> generation(plans(badPlan, badPlan));
            var response = f.service.recommend(foodCulture());
            assertEquals("NO_FEASIBLE_ALTERNATIVES", response.recommendationStatus());
            assertTrue(response.alternatives().isEmpty());
            assertEquals(List.of(0, 1), response.rejectedAlternatives().stream().map(RecommendationResponse.RejectedAlternative::index).toList());
            assertEquals(List.of(code, code), response.rejectedAlternatives().stream().map(RecommendationResponse.RejectedAlternative::reason).toList());
            assertTrue(response.rejectedAlternatives().stream().allMatch(r -> r.proposal() == null));
            assertEquals(List.of("천안 독립기념관 맛집", "천안 독립기념관 관광"),
                f.places.calls.stream().map(q -> q.keyword()).toList());
            assertEquals(2, response.calls().placeSearches());
            assertEquals(0, response.calls().roadRoutes());
            assertEquals(1, response.calls().modelGenerations());
            assertEquals(1, f.model.calls.get());
            assertTrue(f.routes.calls.isEmpty());
            assertEquals("FAKE_MODEL", response.aiProviderStatus().invocation());
            assertTrue(response.candidates().stream().allMatch(c -> c.data().dataMode().equals("DEMO_SIMULATED")));
        }
    }
    @Test void invalidText() { assertTwoParserRejections("INVALID_TEXT", plan(" leading", 30, "001")); }
    @Test void unsafeGeneratedText() { assertTwoParserRejections("UNSAFE_GENERATED_TEXT", plan("synthetic@example.invalid", 30, "001")); }
    @Test void unlistedPlaceId() { assertTwoParserRejections("UNLISTED_PLACE_ID", plan("가상 제안", 30, "not-in-pool")); }
    @Test void duplicateVisit() { assertTwoParserRejections("DUPLICATE_VISIT", plan("가상 제안", 30, "001", "001")); }
    @Test void invalidVisitCount() { assertTwoParserRejections("INVALID_VISIT_COUNT", plan("가상 제안", 30)); }
    @Test void integerOutOfRange() { assertTwoParserRejections("INTEGER_OUT_OF_RANGE", plan("가상 제안", 241, "001")); }

    @Test void duplicateAlternativeRequiresFirstPlanValidationAndCannotExplainTwoSearchesOnly() {
        try (var f = new RecommendationFixtures()) {
            String first = plan("가상 첫 제안", 30, "001");
            f.model.handler = prompt -> generation(plans(first, plan("제목만 다른 가상 제안", 30, "001")));
            var response = f.service.recommend(foodCulture());
            assertEquals("PARTIAL", response.recommendationStatus());
            assertEquals(1, response.alternatives().size());
            assertEquals("FEASIBLE", response.alternatives().getFirst().feasibility());
            assertEquals(1, response.rejectedAlternatives().size());
            assertEquals(1, response.rejectedAlternatives().getFirst().index());
            assertEquals("DUPLICATE_ALTERNATIVE", response.rejectedAlternatives().getFirst().reason());
            assertNull(response.rejectedAlternatives().getFirst().proposal());
            assertEquals(3, response.calls().placeSearches());
            assertEquals(2, response.calls().roadRoutes());
            assertEquals(1, response.calls().modelGenerations());
        }
    }
    @Test void normalFoodCulturePlansStillReceiveFullFeasibleRoundTripValidation() {
        try (var f = new RecommendationFixtures()) {
            var response = f.service.recommend(foodCulture());
            assertEquals("COMPLETED", response.recommendationStatus());
            assertEquals(2, response.alternatives().size());
            assertTrue(response.rejectedAlternatives().isEmpty());
            assertEquals(6, response.calls().placeSearches());
            assertEquals(6, response.calls().roadRoutes());
            assertEquals(1, response.calls().modelGenerations());
            response.alternatives().forEach(a -> {
                assertEquals("FEASIBLE", a.feasibility());
                assertTrue(a.validation().returnDeadlineMet());
                assertTrue(a.validation().safetyBufferSatisfied());
                assertEquals(List.of("DEMO_SIMULATED"), a.dataModes());
            });
        }
    }
}