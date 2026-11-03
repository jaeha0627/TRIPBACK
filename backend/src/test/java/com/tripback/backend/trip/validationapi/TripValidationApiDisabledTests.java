package com.tripback.backend.trip.validationapi;

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
@Import(TripValidationApiTests.Fakes.class)
class TripValidationApiDisabledTests {
    @Autowired ApplicationContext context;
    @Autowired Environment environment;
    @Autowired TripValidationApiTests.FakeProviders fake;

    @Test void endpointIsAbsentByDefaultAndStartupNeverCallsProviders() throws Exception {
        assertFalse(environment.getProperty("TRIPBACK_ENABLE_TRIP_VALIDATION_API", Boolean.class, false));
        assertTrue(context.getBeansOfType(TripValidationController.class).isEmpty());
        assertTrue(fake.searches.isEmpty()); assertTrue(fake.routes.isEmpty());
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:"
                    + environment.getRequiredProperty("local.server.port") + "/api/v1/trips/validate"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}" )).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, response.statusCode());
        }
        assertTrue(fake.searches.isEmpty()); assertTrue(fake.routes.isEmpty());
    }
}
