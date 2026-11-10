package com.tripback.backend.trip.provider.kakao;

import com.tripback.backend.trip.provider.ProviderData;
import com.tripback.backend.trip.provider.RoadRouteQuery;
import com.tripback.backend.trip.provider.RoadRouteResult;
import com.tripback.backend.trip.provider.Wgs84Point;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Explicit opt-in only: one official request, without retries or raw request/response diagnostics. */
@Tag("live")
class KakaoRoadRouteLiveTests {
    @Test
    void officialDirectionsReturnsValidatedLiveEtaAndPreservesRequestedEndpoints() {
        assumeTrue(Boolean.getBoolean("tripback.liveTests.allowed"),
                "status=SKIPPED, code=LIVE_TASK_NOT_AUTHORIZED");
        assumeTrue("1".equals(System.getenv("TRIPBACK_RUN_KAKAO_MOBILITY_LIVE_TESTS")),
                "status=SKIPPED, code=LIVE_TEST_NOT_ENABLED");
        assumeTrue(keyConfigured(), "status=SKIPPED, code=KEY_NOT_CONFIGURED");
        String httpLogging = System.getProperty("jdk.httpclient.HttpClient.log", "");
        assumeTrue(httpLogging.isBlank() || "off".equalsIgnoreCase(httpLogging),
                "status=SKIPPED, code=HTTP_DIAGNOSTICS_ENABLED");

        // Wgs84Point takes latitude, longitude. The adapter handles the API's x,y ordering.
        Wgs84Point origin = new Wgs84Point(37.402464820205246, 127.10764191124568);
        Wgs84Point destination = new Wgs84Point(37.39419693653072, 127.11056336672839);
        RoadRouteQuery query = new RoadRouteQuery(origin, destination);
        RoadRouteResult result;
        try (KakaoRoadRouteAdapter adapter = new KakaoRoadRouteAdapter(JsonMapper.builder().build())) {
            // The only external-call site: no retries, alternative queries or fixed ETA expectations.
            result = adapter.route(query);
        } catch (RuntimeException unexpected) {
            // Never attach the original exception, which could contain credential or transport details.
            fail("status=FAILED, code=UNEXPECTED_ADAPTER_ERROR");
            return;
        }

        check(result != null, "MISSING_RESULT");
        assertTrue(result.status() == RoadRouteResult.Status.FOUND, () ->
                "status=" + result.status() + ", code="
                        + (result.failure() == null ? "NONE" : result.failure().code()));
        check(result.failure() == null, "UNEXPECTED_FAILURE");
        // Long is an integer number of seconds; traffic-dependent ETA must not be fixed to a fixture.
        check(result.durationSeconds() != null && result.durationSeconds() > 0, "INVALID_DURATION");
        check("KAKAO_MOBILITY".equals(result.provider()), "INVALID_PROVIDER");
        check(result.data() != null, "MISSING_PROVENANCE");
        check("KAKAO_MOBILITY".equals(result.data().provider()), "INVALID_DATA_PROVIDER");
        check(result.data().dataMode() == ProviderData.DataMode.VERIFIED_LIVE, "NOT_VERIFIED_LIVE");
        check(result.data().verification() == ProviderData.Verification.VALIDATED_PROVIDER_DATA,
                "PROVIDER_DATA_NOT_VALIDATED");
        check(result.attemptedAt() != null && result.data().retrievedAt() != null, "MISSING_QUERY_TIME");
        check(result.query() != null, "MISSING_QUERY");
        check(origin.equals(result.query().origin()), "ORIGIN_CHANGED");
        check(destination.equals(result.query().destination()), "DESTINATION_CHANGED");
    }

    private static boolean keyConfigured() {
        String value = System.getenv(KakaoRoadRouteAdapter.KEY_ENVIRONMENT_VARIABLE);
        return value != null && !value.isBlank();
    }

    private static void check(boolean condition, String safeCode) {
        assertTrue(condition, () -> "status=INVALID_DATA, code=" + safeCode);
    }
}
