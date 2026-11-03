package com.tripback.backend.trip.validationapi;

import com.tripback.backend.trip.integration.*;
import com.tripback.backend.trip.provider.ProviderData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class TripValidationControllerSafetyTests {
    private static final String REQUEST = """
            {"origin":{"latitude":37,"longitude":127},"departAt":"2026-11-07T08:00:00+09:00",
             "returnDeadline":"2026-11-07T18:00:00+09:00","safetyBufferMin":30,
             "visits":[{"keyword":"fixture","selectedId":{"provider":"KAKAO_LOCAL","value":"fixture"},"stayMinutes":60}]}
            """;

    @Test void unexpectedServerFailureReturnsOnlySafe500Code(CapturedOutput output) throws Exception {
        TripValidationService service = mock(TripValidationService.class);
        String secret = "synthetic-server-sensitive-" + UUID.randomUUID();
        when(service.validate(any())).thenThrow(new IllegalStateException(secret));
        var mvc = MockMvcBuilders.standaloneSetup(new TripValidationController(service, JsonMapper.builder().build())).build();
        String body = mvc.perform(post("/api/v1/trips/validate").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isInternalServerError()).andReturn().getResponse().getContentAsString();
        assertEquals("{\"code\":\"INTERNAL_ERROR\"}", body);
        assertFalse(output.getAll().contains(secret));
        verify(service, times(1)).validate(any());
    }

    @Test void mixedSourceMetadataSurvivesHttpProjectionWithoutInventingLivePlacesOrEtas() throws Exception {
        // Metadata-only projection fixture: no place, coordinate or ETA is labeled as real LIVE data.
        TripValidationResult metadata = mock(TripValidationResult.class);
        when(metadata.feasibility()).thenReturn(com.tripback.backend.trip.feasibility.TripFeasibilityResult.Feasibility.UNKNOWN);
        when(metadata.dataCoverage()).thenReturn(TripValidationResult.DataCoverage.CONTAINS_DEMO);
        when(metadata.dataModes()).thenReturn(Set.of(ProviderData.DataMode.VERIFIED_LIVE,
                ProviderData.DataMode.VERIFIED_CACHED, ProviderData.DataMode.DEMO_SIMULATED));
        when(metadata.places()).thenReturn(List.of()); when(metadata.routes()).thenReturn(List.of());
        when(metadata.notice()).thenReturn("METADATA_PROJECTION_TEST_ONLY");
        TripValidationService service = mock(TripValidationService.class);
        when(service.validate(any())).thenReturn(metadata);
        var mvc = MockMvcBuilders.standaloneSetup(new TripValidationController(service, JsonMapper.builder().build())).build();
        String body = mvc.perform(post("/api/v1/trips/validate").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var json = JsonMapper.builder().build().readTree(body);
        assertEquals("CONTAINS_DEMO", json.get("dataCoverage").asString());
        assertEquals(3, json.get("dataModes").size());
        assertEquals("DEMO_SIMULATED", json.get("dataModes").get(0).asString());
        assertEquals("VERIFIED_CACHED", json.get("dataModes").get(1).asString());
        assertEquals("VERIFIED_LIVE", json.get("dataModes").get(2).asString());
        assertTrue(json.get("predictedReturnAt").isNull());
    }

    @Test void sourceProjectionPreservesDeclaredModesVerificationAndRetrievalTime() {
        Instant timestamp = Instant.parse("2026-11-03T00:00:00Z");
        for (var mode : ProviderData.DataMode.values()) {
            var verification = mode == ProviderData.DataMode.DEMO_SIMULATED ? ProviderData.Verification.UNVERIFIED
                    : ProviderData.Verification.VALIDATED_PROVIDER_DATA;
            // Label mapping only; never attached to a fictitious place or ETA.
            var mapped = TripValidationResponse.source(new ProviderData("KAKAO_LOCAL", mode, verification, timestamp));
            assertEquals(mode.name(), mapped.dataMode()); assertEquals(verification.name(), mapped.verification());
            assertEquals(timestamp, mapped.retrievedAt());
        }
    }
}
