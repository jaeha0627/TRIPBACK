package com.tripback.backend.trip.replan;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import java.net.*;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "TRIPBACK_ENABLE_REPLAN_PREVIEW_API=false")
class ReplanPreviewDisabledTests {
    @Autowired Environment environment;
    @Autowired ApplicationContext context;
    @Test void defaultDisabledApiHasNoStoreAndReturns404() throws Exception {
        assertTrue(context.getBeansOfType(ReplanPlanStore.class).isEmpty());
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:"
                    + environment.getRequiredProperty("local.server.port") + "/api/v1/trips/replan-preview"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, response.statusCode());
        }
    }
}
