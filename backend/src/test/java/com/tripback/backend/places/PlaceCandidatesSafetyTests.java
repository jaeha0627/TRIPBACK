package com.tripback.backend.places;

import com.tripback.backend.trip.provider.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class PlaceCandidatesSafetyTests {
    @Test void metadataProjectionPreservesLiveCachedDemoWithoutInventingLiveCandidateFixtures() {
        Instant retrieved = Instant.parse("2026-11-04T00:00:00Z");
        for (var mode : ProviderData.DataMode.values()) {
            var verification = mode == ProviderData.DataMode.DEMO_SIMULATED ? ProviderData.Verification.UNVERIFIED
                    : ProviderData.Verification.VALIDATED_PROVIDER_DATA;
            // Metadata-only label projection, never attached to fake place data.
            var source = PlaceCandidatesResponse.Source.from(new ProviderData("KAKAO_LOCAL", mode, verification, retrieved));
            assertEquals(mode.name(), source.dataMode()); assertEquals(verification.name(), source.verification());
            assertEquals(retrieved, source.retrievedAt());
        }
    }
    @Test void invalidProviderCoordinatesCannotBePublished() {
        // Deliberately bypass the existing record invariant to exercise the public boundary's defense.
        Wgs84Point invalid = mock(Wgs84Point.class);
        when(invalid.latitude()).thenReturn(Double.NaN); when(invalid.longitude()).thenReturn(127.0);
        var valid = PlaceCandidatesApiTests.candidate("fixture-1");
        var bad = new PlaceCandidate(new ProviderPlaceId("KAKAO_LOCAL", "fixture-bad"), "Synthetic invalid place", "", invalid, valid.data());
        PlaceCandidatesService service = new PlaceCandidatesService(query ->
                PlaceCandidatesApiTests.result(PlaceSearchResult.Status.FOUND, null, valid, bad));
        var response = service.search(new PlaceCandidatesRequest("fixture"));
        assertEquals("FAILED", response.status()); assertEquals("INVALID_PROVIDER_RESPONSE", response.reason());
        assertTrue(response.candidates().isEmpty());
    }
    @Test void unexpectedInternalExceptionReturnsSafe500WithoutLoggingSecrets(CapturedOutput output) throws Exception {
        PlaceCandidatesService service = mock(PlaceCandidatesService.class);
        String secret = "synthetic-internal-private-" + UUID.randomUUID();
        when(service.search(any())).thenThrow(new IllegalStateException(secret));
        var mvc = MockMvcBuilders.standaloneSetup(new PlaceCandidatesController(service, JsonMapper.builder().build())).build();
        var response = mvc.perform(post("/api/v1/places/search").contentType(MediaType.APPLICATION_JSON)
                .content("{\"keyword\":\"fixture\"}")).andExpect(status().isInternalServerError()).andReturn().getResponse();
        assertEquals("{\"code\":\"INTERNAL_ERROR\"}", response.getContentAsString());
        assertFalse(output.getAll().contains(secret));
    }
}
