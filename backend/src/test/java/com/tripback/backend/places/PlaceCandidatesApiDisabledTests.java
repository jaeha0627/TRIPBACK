package com.tripback.backend.places;

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
@Import(PlaceCandidatesApiTests.Fakes.class)
class PlaceCandidatesApiDisabledTests {
    @Autowired ApplicationContext context;
    @Autowired Environment environment;
    @Autowired PlaceCandidatesApiTests.FakeProvider fake;
    @Test void defaultEndpointIsAbsentAndStartupDoesNotSearch() throws Exception {
        assertFalse(environment.getProperty("TRIPBACK_ENABLE_PLACE_SEARCH_API", Boolean.class, false));
        assertTrue(context.getBeansOfType(PlaceCandidatesController.class).isEmpty()); assertTrue(fake.calls.isEmpty());
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port")
                    + "/api/v1/places/search")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"keyword\":\"fixture\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, response.statusCode());
        }
        assertTrue(fake.calls.isEmpty()); assertEquals(0, fake.routeCalls);
    }
}
