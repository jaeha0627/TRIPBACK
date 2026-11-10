package com.tripback.backend.trip.recommendation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TripRecommendationApiTests.Fakes.class)
class TripRecommendationApiDisabledTests {
    @Autowired ApplicationContext context;
    @Autowired Environment environment;
    @Autowired RecommendationFixtures.FakePlaces places;
    @Autowired RecommendationFixtures.FakeModel model;
    @Test void endpointIsAbsentByDefaultAndStartupMakesNoExternalCalls() throws Exception {
        assertFalse(environment.getProperty("TRIPBACK_ENABLE_AI_RECOMMENDATION_API", Boolean.class, false));
        assertTrue(context.getBeansOfType(TripRecommendationController.class).isEmpty());
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port")
                    + "/api/v1/trips/recommend")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(RecommendationFixtures.REQUEST)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, response.statusCode());
        }
        assertTrue(places.calls.isEmpty()); assertEquals(0, model.calls.get());
    }
}
