package com.tripback.backend.trip.provider.kakao;

import com.tripback.backend.trip.provider.PlaceCandidate;
import com.tripback.backend.trip.provider.PlaceSearchQuery;
import com.tripback.backend.trip.provider.PlaceSearchResult;
import com.tripback.backend.trip.provider.ProviderData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Optional live integration test: a single official search, with no retries or raw response logging. */
@Tag("live")
class KakaoPlaceSearchLiveTests {
    @Test
    void officialKeywordSearchReturnsValidatedLiveCandidates() {
        assumeTrue(Boolean.getBoolean("tripback.liveTests.allowed"),
                "status=SKIPPED, code=LIVE_TASK_NOT_AUTHORIZED");
        assumeTrue("1".equals(System.getenv("TRIPBACK_RUN_KAKAO_LOCAL_LIVE_TESTS")),
                "status=SKIPPED, code=LIVE_TEST_NOT_ENABLED");
        // Boolean only: never interpolate the environment value into a message or test diagnostic.
        assumeTrue(keyConfigured(), "SKIPPED: Kakao REST API key environment variable is not configured");
        String httpLogging = System.getProperty("jdk.httpclient.HttpClient.log", "");
        assumeTrue(httpLogging.isBlank() || "off".equalsIgnoreCase(httpLogging),
                "SKIPPED: disable JDK HttpClient diagnostics before using live credentials");

        ObjectMapper mapper = JsonMapper.builder().build();
        PlaceSearchResult result;
        try (KakaoPlaceSearchAdapter adapter = new KakaoPlaceSearchAdapter(mapper)) {
            // This is the only external-call site in this test. No fixed place ID or result ordering.
            result = adapter.search(new PlaceSearchQuery("천안 독립기념관", null, null, 1, 5));
        } catch (RuntimeException unexpected) {
            // Do not propagate an exception/cause which might contain request or credential details.
            fail("status=FAILED, code=UNEXPECTED_ADAPTER_ERROR");
            return;
        }

        assertTrue(result != null, "status=FAILED, code=MISSING_RESULT");
        assertTrue(result.status() == PlaceSearchResult.Status.FOUND, () ->
                "status=" + result.status() + ", code=" + (result.failure() == null ? "NONE" : result.failure().code())
                        + ", httpStatus=" + (result.failure() == null ? "NONE" : result.failure().httpStatus()));
        check(result.failure() == null, "UNEXPECTED_FAILURE");
        check("KAKAO_LOCAL".equals(result.provider()), "INVALID_PROVIDER");
        check(!result.candidates().isEmpty() && result.candidates().size() <= 5, "INVALID_CANDIDATE_COUNT");
        check(result.attemptedAt() != null, "MISSING_QUERY_TIME");

        boolean hasAddress = false;
        for (PlaceCandidate candidate : result.candidates()) {
            check(candidate != null, "MISSING_CANDIDATE");
            check(candidate.name() != null && !candidate.name().isBlank(), "INVALID_PLACE_NAME");
            check(candidate.id() != null && "KAKAO_LOCAL".equals(candidate.id().provider())
                    && candidate.id().value() != null && !candidate.id().value().isBlank(), "INVALID_PLACE_ID");
            check(candidate.address() != null, "MISSING_ADDRESS_FIELD");
            hasAddress |= !candidate.address().isBlank();
            check(candidate.coordinates() != null, "MISSING_COORDINATES");
            double latitude = candidate.coordinates().latitude();
            double longitude = candidate.coordinates().longitude();
            check(Double.isFinite(latitude) && latitude >= -90 && latitude <= 90
                    && Double.isFinite(longitude) && longitude >= -180 && longitude <= 180, "INVALID_WGS84_COORDINATES");
            check(candidate.data() != null && "KAKAO_LOCAL".equals(candidate.data().provider()), "INVALID_PROVENANCE");
            check(candidate.data().dataMode() == ProviderData.DataMode.VERIFIED_LIVE, "NOT_VERIFIED_LIVE");
            check(candidate.data().verification() == ProviderData.Verification.VALIDATED_PROVIDER_DATA,
                    "PROVIDER_DATA_NOT_VALIDATED");
            check(candidate.data().retrievedAt() != null, "MISSING_RETRIEVAL_TIME");
            check(candidate.visitStatus() == PlaceCandidate.VisitStatus.NOT_VERIFIED, "UNEXPECTED_VISIT_VERIFICATION");
        }
        // The model permits an absent upstream address. Require at least one usable address for this positive query.
        check(hasAddress, "NO_ADDRESS_AVAILABLE");
    }

    private static boolean keyConfigured() {
        String value = System.getenv(KakaoPlaceSearchAdapter.KEY_ENVIRONMENT_VARIABLE);
        return value != null && !value.isBlank();
    }

    private static void check(boolean condition, String safeCode) {
        assertTrue(condition, () -> "status=INVALID_DATA, code=" + safeCode);
    }
}
