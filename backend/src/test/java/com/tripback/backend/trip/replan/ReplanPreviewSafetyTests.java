package com.tripback.backend.trip.replan;

import com.tripback.backend.trip.integration.TripValidationService;
import com.tripback.backend.trip.validationapi.TripValidationController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static com.tripback.backend.trip.replan.ReplanApiFixtures.*;

class ReplanPreviewSafetyTests {
    @Test void nonLoopbackPreviewIsRejectedBeforeParsingOrLookup() throws Exception {
        var store = new ReplanPlanStore(new MutableClock(), Duration.ofMinutes(30), 2);
        var mapper = mock(ObjectMapper.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ReplanPreviewController(store, mapper)).build();
        var response = mvc.perform(post("/api/v1/trips/replan-preview").contentType("application/json").content("{}")
                .with(r -> { r.setRemoteAddr("192.0.2.1"); return r; })).andReturn().getResponse();
        assertEquals(403, response.getStatus()); assertTrue(response.getContentAsString().contains("LOCAL_DEVELOPMENT_ONLY"));
        verifyNoInteractions(mapper);
    }
    @Test void nonLoopbackRegistrationIsRejectedBeforeProviderCalls() throws Exception {
        var service = mock(TripValidationService.class);
        var controller = new TripValidationController(service, JsonMapper.builder().build());
        controller.setReplanPlans(new ReplanPlanStore());
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        var body = "{\"origin\":{\"latitude\":37,\"longitude\":127},\"departAt\":\"2026-11-07T08:00:00+09:00\","
                + "\"returnDeadline\":\"2026-11-07T18:00:00+09:00\",\"safetyBufferMin\":30,\"visits\":["
                + "{\"keyword\":\"fixture\",\"selectedId\":{\"provider\":\"KAKAO_LOCAL\",\"value\":\"fake\"},\"stayMinutes\":30}]}";
        var response = mvc.perform(post("/api/v1/trips/validate").contentType("application/json")
                .header("X-Tripback-Register-Replan", "true").content(body)
                .with(r -> { r.setRemoteAddr("192.0.2.1"); return r; })).andReturn().getResponse();
        assertEquals(403, response.getStatus()); verifyNoInteractions(service);
    }
    @Test void unexpectedServerErrorIsSanitizedAndRetainsPlan() throws Exception {
        var store = new ReplanPlanStore(new MutableClock(), Duration.ofMinutes(30), 2);
        var registered = store.registerServerValidation(validation(new Providers(), NOW.plusSeconds(10800), 30));
        var original = store.find(registered.handle().planRef());
        var brokenMapper = mock(ObjectMapper.class);
        when(brokenMapper.createParser(anyString())).thenThrow(new IllegalStateException("sensitive-error-marker"));
        var mvc = MockMvcBuilders.standaloneSetup(new ReplanPreviewController(store, brokenMapper)).build();
        var response = mvc.perform(post("/api/v1/trips/replan-preview").contentType("application/json").content("{}")
                .with(r -> { r.setRemoteAddr("127.0.0.1"); return r; })).andReturn().getResponse();
        assertEquals(500, response.getStatus()); assertTrue(response.getContentAsString().contains("INTERNAL_ERROR"));
        assertFalse(response.getContentAsString().contains("sensitive-error-marker"));
        assertSame(original, store.find(registered.handle().planRef()));
    }
}
