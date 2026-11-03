package com.tripback.backend.trip.provider.kakao;

import com.sun.net.httpserver.HttpServer;
import com.tripback.backend.trip.provider.*;
import com.tripback.backend.trip.feasibility.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class KakaoRoadRouteAdapterTests {
    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
    private static final RoadRouteQuery QUERY = new RoadRouteQuery(new Wgs84Point(37.5, 127.25), new Wgs84Point(36.75, 128.5));
    private static String success(String duration) {
        return "{\"routes\":[{\"result_code\":0,\"summary\":{\"duration\":" + duration
                + ",\"origin\":{\"x\":127.251,\"y\":37.501},\"destination\":{\"x\":128.501,\"y\":36.751}}}]}";
    }

    private final class Fixture implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final String key = UUID.randomUUID().toString(); // Ephemeral fake credential, never a real API key.
        final AtomicInteger calls = new AtomicInteger();
        final AtomicBoolean authorized = new AtomicBoolean();
        final AtomicReference<String> request = new AtomicReference<>();
        final List<KakaoRoadRouteAdapter> adapters = new ArrayList<>();
        final CountDownLatch release = new CountDownLatch(1);
        volatile int status = 200;
        volatile String body = success("61");
        volatile String type = "application/json;charset=UTF-8";
        volatile String encoding = "identity";
        volatile boolean chunked, stallHeaders, stallBody;

        Fixture() throws IOException {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                calls.incrementAndGet();
                authorized.set(("KakaoAK " + key).equals(exchange.getRequestHeaders().getFirst("Authorization")));
                request.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                try {
                    if (stallHeaders) release.await();
                    exchange.getResponseHeaders().set("Content-Type", type);
                    exchange.getResponseHeaders().set("Content-Encoding", encoding);
                    if (status == 302) exchange.getResponseHeaders().set("Location", endpoint().toString() + "/redirect");
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, chunked ? 0 : bytes.length);
                    if (stallBody) {
                        exchange.getResponseBody().write(' ');
                        exchange.getResponseBody().flush();
                        release.await();
                    }
                    exchange.getResponseBody().write(bytes);
                } catch (IOException cancelled) {
                    // Oversized bodies and timed-out requests are intentionally cancelled.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally { exchange.close(); }
            });
            server.start();
        }

        URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/directions"); }
        KakaoRoadRouteAdapter adapter() { return adapter(() -> key, Duration.ofSeconds(2), 1_048_576); }
        KakaoRoadRouteAdapter adapter(Supplier<String> keys, Duration timeout, int limit) {
            KakaoRoadRouteAdapter adapter = new KakaoRoadRouteAdapter(
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build(),
                    JsonMapper.builder().build(), endpoint(), keys, Clock.fixed(NOW, ZoneOffset.UTC), timeout, limit);
            adapters.add(adapter);
            return adapter;
        }
        public void close() {
            release.countDown();
            adapters.forEach(KakaoRoadRouteAdapter::close);
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private void failure(RoadRouteResult result, ProviderFailure.Code code) {
        assertEquals(RoadRouteResult.Status.FAILED, result.status());
        assertEquals(code, result.failure().code());
        assertNull(result.durationSeconds());
        assertNull(result.data());
        assertSame(QUERY, result.query());
    }

    @Test void parsesSecondsPreservesRequestedEndpointsAndMarksLoopbackAsDemo() throws Exception {
        try (Fixture f = new Fixture()) {
            RoadRouteResult result = f.adapter().route(QUERY);
            assertEquals(RoadRouteResult.Status.FOUND, result.status());
            assertEquals(61L, result.durationSeconds());
            assertSame(QUERY, result.query());
            assertEquals("KAKAO_MOBILITY", result.provider());
            assertEquals(NOW, result.attemptedAt());
            assertEquals(NOW, result.data().retrievedAt());
            assertEquals(ProviderData.DataMode.DEMO_SIMULATED, result.data().dataMode());
            assertEquals(ProviderData.Verification.UNVERIFIED, result.data().verification());
            assertTrue(f.authorized.get(), "Fake authentication header must be delivered");
            assertEquals("GET /v1/directions?origin=127.25,37.5&destination=128.5,36.75&priority=RECOMMEND&summary=true", f.request.get());
            assertEquals(1, f.calls.get());
        }
    }

    @Test void missingMalformedAndFailingKeySourcesNeverCallNetwork() throws Exception {
        try (Fixture f = new Fixture()) {
            for (String key : Arrays.asList(null, "", " ", "bad\nkey", "x".repeat(513))) {
                RoadRouteResult result = f.adapter(() -> key, Duration.ofSeconds(1), 1024).route(QUERY);
                assertEquals(RoadRouteResult.Status.UNAVAILABLE, result.status());
                assertEquals(ProviderFailure.Code.NOT_CONFIGURED, result.failure().code());
            }
            RoadRouteResult result = f.adapter(() -> { throw new IllegalStateException(f.key); }, Duration.ofSeconds(1), 1024).route(QUERY);
            assertEquals(RoadRouteResult.Status.UNAVAILABLE, result.status());
            assertEquals(0, f.calls.get());
        }
    }

    @Test void documentedOrdinaryNoRouteCodesHaveNoEtaOrEvidence() throws Exception {
        try (Fixture f = new Fixture()) {
            KakaoRoadRouteAdapter adapter = f.adapter();
            for (int code : new int[]{1, 101, 102, 103, 104, 105, 106, 107}) {
                f.body = "{\"routes\":[{\"result_code\":" + code + "}]}";
                RoadRouteResult result = adapter.route(QUERY);
                assertEquals(RoadRouteResult.Status.NO_ROUTE, result.status());
                assertNull(result.durationSeconds());
                assertNull(result.data());
                assertNull(result.failure());
            }
        }
    }

    @Test void missingOrNullDurationIsMissingEta() throws Exception {
        try (Fixture f = new Fixture()) {
            for (String summary : List.of("{}", "{\"duration\":null}")) {
                f.body = "{\"routes\":[{\"result_code\":0,\"summary\":" + summary + "}]}";
                RoadRouteResult result = f.adapter().route(QUERY);
                assertEquals(RoadRouteResult.Status.ETA_MISSING, result.status());
                assertEquals(ProviderFailure.Code.MISSING_ETA, result.failure().code());
                assertNull(result.durationSeconds());
                assertNull(result.data());
            }
        }
    }

    @Test void invalidDurationsAreNotCoercedOrTruncated() throws Exception {
        try (Fixture f = new Fixture()) {
            KakaoRoadRouteAdapter adapter = f.adapter();
            for (String value : List.of("-1", "\"61\"", "1.5", "1.0", "true", "{}", "[]", "9223372036854775808", "1e1000", "NaN", "Infinity")) {
                f.body = success(value);
                failure(adapter.route(QUERY), ProviderFailure.Code.INVALID_DATA);
            }
        }
    }

    @Test void malformedAmbiguousAndUnexpectedStructuresFailClosed() throws Exception {
        try (Fixture f = new Fixture()) {
            KakaoRoadRouteAdapter adapter = f.adapter();
            for (String body : List.of("broken", "null", "[]", "{}", "{\"routes\":[]}",
                    "{\"routes\":{}}", "{\"routes\":[null]}", "{\"routes\":[{}]}",
                    "{\"routes\":[{\"result_code\":0}]}", "{\"routes\":[{\"result_code\":0,\"summary\":null}]}",
                    "{\"routes\":[{\"result_code\":0,\"summary\":[]}]}",
                    "{\"routes\":[{\"result_code\":999}]}", "{\"routes\":[{\"result_code\":201}]}",
                    "{\"routes\":[{\"result_code\":\"0\"}]}", "{\"routes\":[{\"result_code\":4294967296}]}",
                    "{\"routes\":[{\"result_code\":0.0}]}", "{\"routes\":[{\"result_code\":0,\"result_code\":1}]}",
                    "{\"routes\":[{\"result_code\":1},{\"result_code\":1}]}", success("1") + " {}")) {
                f.body = body;
                failure(adapter.route(QUERY), ProviderFailure.Code.INVALID_DATA);
            }
        }
    }

    @Test void classifiesHttpErrorsAndDoesNotLeakBodiesOrCredentials(CapturedOutput output) throws Exception {
        try (Fixture f = new Fixture()) {
            KakaoRoadRouteAdapter adapter = f.adapter();
            f.body = "upstream-secret-" + f.key;
            for (int status : new int[]{401, 403, 429, 500}) {
                f.status = status;
                RoadRouteResult result = adapter.route(QUERY);
                failure(result, status == 401 || status == 403 ? ProviderFailure.Code.AUTHENTICATION_FAILED
                        : status == 429 ? ProviderFailure.Code.RATE_LIMITED : ProviderFailure.Code.HTTP_ERROR);
                assertEquals(status, result.failure().httpStatus());
                assertFalse(result.toString().contains(f.key));
                assertFalse(result.toString().contains("upstream-secret"));
            }
            assertEquals(4, f.calls.get());
            assertFalse(adapter.toString().contains(f.key));
            assertFalse(output.getAll().contains(f.key));
            assertFalse(output.getAll().contains("upstream-secret"));
        }
    }

    @Test void redirectAndUnexpectedSuccessAreRejectedWithoutFollowing() throws Exception {
        try (Fixture f = new Fixture()) {
            for (int status : new int[]{302, 201}) {
                f.status = status;
                failure(f.adapter().route(QUERY), ProviderFailure.Code.INVALID_DATA);
            }
            assertEquals(2, f.calls.get());
        }
    }

    @Test void rejectsLargeDeclaredAndChunkedBodies() throws Exception {
        try (Fixture f = new Fixture()) {
            f.body = " ".repeat(2048);
            for (boolean chunked : new boolean[]{false, true}) {
                f.chunked = chunked;
                failure(f.adapter(() -> f.key, Duration.ofSeconds(2), 1024).route(QUERY), ProviderFailure.Code.INVALID_DATA);
            }
        }
    }

    @Test void rejectsWrongContentTypeAndUnexpectedCompression() throws Exception {
        try (Fixture f = new Fixture()) {
            f.type = "text/html";
            failure(f.adapter().route(QUERY), ProviderFailure.Code.INVALID_DATA);
            f.type = "application/json";
            f.encoding = "gzip";
            failure(f.adapter().route(QUERY), ProviderFailure.Code.INVALID_DATA);
        }
    }

    @Test void timesOutWaitingForHeaders() throws Exception {
        try (Fixture f = new Fixture()) {
            f.stallHeaders = true;
            failure(f.adapter(() -> f.key, Duration.ofMillis(250), 1024).route(QUERY), ProviderFailure.Code.TIMEOUT);
        }
    }

    @Test void timesOutStalledChunkedBody() throws Exception {
        try (Fixture f = new Fixture()) {
            f.stallBody = true;
            f.chunked = true;
            failure(f.adapter(() -> f.key, Duration.ofMillis(500), 1024).route(QUERY), ProviderFailure.Code.TIMEOUT);
        }
    }

    @Test void connectionRefusalIsDistinctFromHttpError() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        try (KakaoRoadRouteAdapter adapter = new KakaoRoadRouteAdapter(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(), JsonMapper.builder().build(),
                URI.create("http://127.0.0.1:" + port + "/v1/directions"), () -> UUID.randomUUID().toString(),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(2), 1024)) {
            failure(adapter.route(QUERY), ProviderFailure.Code.CONNECTION_FAILED);
        }
    }

    @Test void adapterResultsRoundUpAndMissingReturnEtaKeepsEngineUnknown() throws Exception {
        try (Fixture f = new Fixture()) {
            KakaoRoadRouteAdapter adapter = f.adapter();
            for (long seconds : new long[]{0, 1, 60, 61}) {
                f.body = success(Long.toString(seconds));
                RoadRouteResult route = adapter.route(QUERY);
                TravelLeg leg = RouteLegMapper.toTravelLeg(0, QUERY, route);
                assertEquals(seconds / 60 + (seconds % 60 == 0 ? 0 : 1), leg.minutes());
                assertEquals(TravelLeg.DataMode.DEMO_SIMULATED, leg.evidence().dataMode());
                assertEquals(TravelLeg.Verification.UNVERIFIED, leg.evidence().verification());
            }
            f.body = success(Long.toString(Long.MAX_VALUE));
            RoadRouteResult oversizedForEngine = adapter.route(QUERY);
            assertEquals(Long.MAX_VALUE, oversizedForEngine.durationSeconds());
            assertThrows(IllegalArgumentException.class, () -> RouteLegMapper.toTravelLeg(0, QUERY, oversizedForEngine));
            f.body = success("61");
            TravelLeg out = RouteLegMapper.toTravelLeg(0, QUERY, adapter.route(QUERY));
            f.body = "{\"routes\":[{\"result_code\":0,\"summary\":{}}]}";
            RoadRouteQuery back = new RoadRouteQuery(QUERY.destination(), QUERY.origin());
            TravelLeg missing = RouteLegMapper.toTravelLeg(1, back, adapter.route(back));
            assertNull(missing.minutes());
            TripFeasibilityResult result = new TripFeasibilityService().calculate(TripFeasibilityInput.fromIso8601(
                    "2026-11-07T08:00:00+09:00", "2026-11-07T18:00:00+09:00", 30, List.of(60L), List.of(out, missing)));
            assertEquals(TripFeasibilityResult.Feasibility.UNKNOWN, result.feasibility());
            assertEquals(List.of(1), result.missingEtaLegIndexes());
            assertNull(result.predictedReturnAt());
        }
    }

    @Test void rejectsUnsafeEndpointsRedirectClientsAndTransportLimits() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            for (URI uri : List.of(URI.create("https://example.com/v1/directions"), URI.create("http://apis-navi.kakaomobility.com/v1/directions"),
                    URI.create("http://user@localhost/test"), URI.create("http://localhost/test?key=value"), URI.create("http://localhost/test#fragment"))) {
                assertThrows(IllegalArgumentException.class, () -> new KakaoRoadRouteAdapter(client, JsonMapper.builder().build(),
                        uri, () -> null, Clock.systemUTC(), Duration.ofSeconds(1), 1024));
            }
            // Official HTTPS transport is accepted without making a request. LIVE is never fabricated in tests.
            try (KakaoRoadRouteAdapter official = new KakaoRoadRouteAdapter(client, JsonMapper.builder().build(),
                    KakaoRoadRouteAdapter.OFFICIAL_ENDPOINT, () -> null, Clock.systemUTC(), Duration.ofSeconds(1), 1024)) {
                assertEquals(RoadRouteResult.Status.UNAVAILABLE, official.route(QUERY).status());
            }
        }
        try (Fixture f = new Fixture(); HttpClient redirects = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                .followRedirects(HttpClient.Redirect.ALWAYS).build()) {
            assertThrows(IllegalArgumentException.class, () -> new KakaoRoadRouteAdapter(redirects, JsonMapper.builder().build(),
                    f.endpoint(), () -> null, Clock.systemUTC(), Duration.ofSeconds(1), 1024));
        }
    }
}
