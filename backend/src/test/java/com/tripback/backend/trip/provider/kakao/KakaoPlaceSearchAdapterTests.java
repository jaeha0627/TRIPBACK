package com.tripback.backend.trip.provider.kakao;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.tripback.backend.trip.provider.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class KakaoPlaceSearchAdapterTests {
    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
    private static final String PATH = "/v2/local/search/keyword.json";
    private final JsonMapper mapper = JsonMapper.builder().build();

    private PlaceSearchQuery query() { return new PlaceSearchQuery("테스트 장소", null, null, 1, 15); }

    private ObjectNode response() {
        return (ObjectNode) mapper.readTree("""
                {"meta":{"total_count":1,"pageable_count":1,"is_end":true},
                 "documents":[{"id":"fixture-001","place_name":"테스트 전용 가상 장소",
                   "address_name":"테스트 지번 주소","road_address_name":"테스트 도로명 주소",
                   "x":"127.25","y":"37.5"}]}
                """);
    }

    private void assertFailure(PlaceSearchResult result, ProviderFailure.Code code) {
        assertEquals(PlaceSearchResult.Status.FAILED, result.status());
        assertEquals(code, result.failure().code());
        assertTrue(result.candidates().isEmpty());
    }

    private final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        // Ephemeral test-only authentication material, never a real API key or a checked-in credential.
        private final String authentication = UUID.randomUUID().toString().replace("-", "");
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicReference<URI> requestUri = new AtomicReference<>();
        private final AtomicBoolean correctAuthorization = new AtomicBoolean();
        private final AtomicReference<String> method = new AtomicReference<>();
        private final AtomicReference<HttpHandler> handler = new AtomicReference<>();
        private final List<KakaoPlaceSearchAdapter> adapters = new ArrayList<>();
        private final CountDownLatch release = new CountDownLatch(1);

        private Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            respond(200, response().toString(), "application/json;charset=UTF-8", false);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                requestUri.set(exchange.getRequestURI());
                method.set(exchange.getRequestMethod());
                correctAuthorization.set(("KakaoAK " + authentication).equals(exchange.getRequestHeaders().getFirst("Authorization")));
                try { handler.get().handle(exchange); }
                catch (IOException clientCancelled) { /* Tests deliberately cancel oversized/slow bodies. */ }
                finally { exchange.close(); }
            });
            server.start();
        }

        private URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + PATH); }

        private void respond(int status, String body, String type, boolean chunked) {
            handler.set(exchange -> {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", type);
                exchange.sendResponseHeaders(status, chunked ? 0 : bytes.length);
                exchange.getResponseBody().write(bytes);
            });
        }

        private KakaoPlaceSearchAdapter adapter() { return adapter(authentication, Duration.ofSeconds(2), 1_048_576); }

        private KakaoPlaceSearchAdapter adapter(String key, Duration timeout, int maxBytes) {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            KakaoPlaceSearchAdapter adapter = new KakaoPlaceSearchAdapter(client, mapper, endpoint(), () -> key,
                    Clock.fixed(NOW, ZoneOffset.UTC), timeout, maxBytes);
            adapters.add(adapter);
            return adapter;
        }

        @Override public void close() {
            release.countDown();
            adapters.forEach(KakaoPlaceSearchAdapter::close);
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void mapsUtf8KeywordAndAllOfficialQueryFieldsWithoutInjection() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String keyword = "아이와 카페 & 빵/+?=#";
            PlaceSearchResult result = fixture.adapter().search(new PlaceSearchQuery(keyword, new Wgs84Point(37.5, 127.25), 20000, 2, 10));
            assertEquals(PlaceSearchResult.Status.FOUND, result.status());
            assertEquals("GET", fixture.method.get());
            assertTrue(fixture.correctAuthorization.get());
            assertEquals(PATH, fixture.requestUri.get().getPath());
            String raw = fixture.requestUri.get().getRawQuery();
            assertTrue(raw.contains("query=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8)));
            Map<String, String> fields = new LinkedHashMap<>();
            for (String field : raw.split("&")) {
                String[] pair = field.split("=", 2);
                fields.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            assertEquals(Map.of("query", keyword, "page", "2", "size", "10", "x", "127.25", "y", "37.5", "radius", "20000"), fields);
            assertFalse(raw.contains(fixture.authentication));
        }
    }

    @Test
    void omitsOptionalCenterAndRadiusWhenAbsent() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.adapter().search(query());
            String raw = fixture.requestUri.get().getRawQuery();
            assertFalse(raw.contains("&x="));
            assertFalse(raw.contains("&y="));
            assertFalse(raw.contains("&radius="));
        }
    }

    @Test
    void mapsCandidateNamespaceCoordinatesAddressAndRetrievalTime() throws Exception {
        try (Fixture fixture = new Fixture()) {
            PlaceSearchResult result = fixture.adapter().search(query());
            assertEquals(PlaceSearchResult.Status.FOUND, result.status());
            PlaceCandidate place = result.candidates().getFirst();
            assertEquals("KAKAO_LOCAL", place.id().provider());
            assertEquals("fixture-001", place.id().value());
            assertEquals("테스트 전용 가상 장소", place.name());
            assertEquals("테스트 도로명 주소", place.address());
            assertEquals(37.5, place.coordinates().latitude());
            assertEquals(127.25, place.coordinates().longitude());
            assertEquals(NOW, place.data().retrievedAt());
            assertEquals(NOW, result.attemptedAt());
            assertEquals(PlaceCandidate.VisitStatus.NOT_VERIFIED, place.visitStatus());
            assertNotEquals(new ProviderPlaceId("PLEOS", place.id().value()), place.id());
        }
    }

    @Test
    void loopbackResponsesCannotBePromotedToVerifiedLive() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ProviderData data = fixture.adapter().search(query()).candidates().getFirst().data();
            assertEquals(ProviderData.DataMode.DEMO_SIMULATED, data.dataMode());
            assertEquals(ProviderData.Verification.UNVERIFIED, data.verification());
            assertEquals(URI.create("https://dapi.kakao.com/v2/local/search/keyword.json"), KakaoPlaceSearchAdapter.OFFICIAL_ENDPOINT);
        }
    }

    @Test
    void prefersRoadAddressAndFallsBackWithoutInventingAnAddress() throws Exception {
        try (Fixture fixture = new Fixture()) {
            KakaoPlaceSearchAdapter adapter = fixture.adapter();
            ObjectNode body = response();
            ObjectNode place = (ObjectNode) body.get("documents").get(0);
            place.put("road_address_name", "");
            fixture.respond(200, body.toString(), "application/json", false);
            assertEquals("테스트 지번 주소", adapter.search(query()).candidates().getFirst().address());
            place.remove("road_address_name");
            place.remove("address_name");
            fixture.respond(200, body.toString(), "application/json", false);
            assertEquals("", adapter.search(query()).candidates().getFirst().address());
        }
    }

    @Test
    void zeroResultsAreASuccessfulEmptyPageNotAProviderFailure() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.respond(200, "{\"meta\":{\"total_count\":0,\"pageable_count\":0,\"is_end\":true},\"documents\":[]}", "application/json", false);
            PlaceSearchResult result = fixture.adapter().search(query());
            assertEquals(PlaceSearchResult.Status.NO_RESULTS, result.status());
            assertNull(result.failure());
            assertTrue(result.candidates().isEmpty());
        }
    }

    @Test
    void missingOrBlankOrUnsafeKeyReturnsUnavailableWithoutMakingAnyRequest() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (String key : new String[] { null, "", " ", fixture.authentication + "\r\n" }) {
                PlaceSearchResult result = fixture.adapter(key, Duration.ofSeconds(1), 1024).search(query());
                assertEquals(PlaceSearchResult.Status.UNAVAILABLE, result.status());
                assertEquals(ProviderFailure.Code.NOT_CONFIGURED, result.failure().code());
            }
            assertEquals(0, fixture.requests.get());
        }
    }

    @Test
    void authenticationRateLimitAndUpstreamServerErrorsAreDistinct() throws Exception {
        try (Fixture fixture = new Fixture()) {
            KakaoPlaceSearchAdapter adapter = fixture.adapter();
            for (int status : new int[] { 401, 403, 429, 500, 503 }) {
                fixture.respond(status, fixture.authentication, "text/plain", true);
                PlaceSearchResult result = adapter.search(query());
                ProviderFailure.Code expected = status == 401 || status == 403 ? ProviderFailure.Code.AUTHENTICATION_FAILED
                        : status == 429 ? ProviderFailure.Code.RATE_LIMITED : ProviderFailure.Code.HTTP_ERROR;
                assertFailure(result, expected);
                assertEquals(status, result.failure().httpStatus());
                assertFalse(result.toString().contains(fixture.authentication));
            }
        }
    }

    @Test
    void refusedConnectionIsReportedWithoutThrowingOrLeakingCredentials() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        String material = UUID.randomUUID().toString().replace("-", "");
        try (KakaoPlaceSearchAdapter adapter = new KakaoPlaceSearchAdapter(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).followRedirects(HttpClient.Redirect.NEVER).build(),
                mapper, URI.create("http://127.0.0.1:" + port + PATH), () -> material,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(1), 1024)) {
            PlaceSearchResult result = adapter.search(query());
            assertFailure(result, ProviderFailure.Code.CONNECTION_FAILED);
            assertFalse(result.toString().contains(material));
        }
    }

    @Test
    void timeoutBeforeResponseHeadersIsBounded() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.handler.set(exchange -> {
                try { fixture.release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertFailure(fixture.adapter(fixture.authentication, Duration.ofMillis(300), 1024).search(query()), ProviderFailure.Code.TIMEOUT);
        }
    }

    @Test
    void wholeBodyDeadlineAlsoStopsAStalledChunkedBody() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch headersSent = new CountDownLatch(1);
            fixture.handler.set(exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                headersSent.countDown();
                try { fixture.release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertFailure(fixture.adapter(fixture.authentication, Duration.ofMillis(700), 1024).search(query()), ProviderFailure.Code.TIMEOUT);
            assertTrue(headersSent.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void malformedJsonOrMissingRequiredStructureIsNotNoResults() throws Exception {
        try (Fixture fixture = new Fixture()) {
            KakaoPlaceSearchAdapter adapter = fixture.adapter();
            for (String body : List.of("", "not-json", "[]", "{}", "{\"documents\":[]}",
                    response() + " {}", response().toString().replace("\"documents\":", "\"documents\":null,\"documents\":"))) {
                fixture.respond(200, body, "application/json", false);
                assertFailure(adapter.search(query()), ProviderFailure.Code.INVALID_DATA);
            }
        }
    }

    @Test
    void missingIdNameOrCoordinatesRejectsWholeResultInsteadOfDroppingACandidate() throws Exception {
        try (Fixture fixture = new Fixture()) {
            KakaoPlaceSearchAdapter adapter = fixture.adapter();
            for (String field : List.of("id", "place_name", "x", "y")) {
                ObjectNode body = response();
                ((ObjectNode) body.get("documents").get(0)).remove(field);
                fixture.respond(200, body.toString(), "application/json", false);
                assertFailure(adapter.search(query()), ProviderFailure.Code.INVALID_DATA);
            }
        }
    }

    @Test
    void blankIdsNamesAndWrongFieldTypesAreInvalid() throws Exception {
        try (Fixture fixture = new Fixture()) {
            KakaoPlaceSearchAdapter adapter = fixture.adapter();
            for (String field : List.of("id", "place_name", "x", "y")) {
                ObjectNode body = response();
                ((ObjectNode) body.get("documents").get(0)).put(field, " ");
                fixture.respond(200, body.toString(), "application/json", false);
                assertFailure(adapter.search(query()), ProviderFailure.Code.INVALID_DATA);
                ((ObjectNode) body.get("documents").get(0)).put(field, 123);
                fixture.respond(200, body.toString(), "application/json", false);
                assertFailure(adapter.search(query()), ProviderFailure.Code.INVALID_DATA);
            }
            ObjectNode body = response();
            ((ObjectNode) body.get("documents").get(0)).put("address_name", 123);
            fixture.respond(200, body.toString(), "application/json", false);
            assertFailure(adapter.search(query()), ProviderFailure.Code.INVALID_DATA);
        }
    }

    @Test
    void invalidCoordinateNumbersAndRangesAreRejected() throws Exception {
        try (Fixture fixture = new Fixture()) {
            KakaoPlaceSearchAdapter adapter = fixture.adapter();
            for (String field : List.of("x", "y")) {
                for (String value : List.of("NaN", "Infinity", "-Infinity", "1e309", "not-a-number", "181", "-181", "0x1p1", "1d", " 37.5 ")) {
                    ObjectNode body = response();
                    ((ObjectNode) body.get("documents").get(0)).put(field, value);
                    fixture.respond(200, body.toString(), "application/json", false);
                    assertFailure(adapter.search(query()), ProviderFailure.Code.INVALID_DATA);
                }
            }
        }
    }

    @Test
    void duplicatePlaceIdsAndOversizedDocumentListsAreRejected() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ObjectNode body = response();
            body.withArray("documents").add(body.get("documents").get(0).deepCopy());
            body.withObject("meta").put("total_count", 2).put("pageable_count", 2);
            fixture.respond(200, body.toString(), "application/json", false);
            assertFailure(fixture.adapter().search(query()), ProviderFailure.Code.INVALID_DATA);
            ((ObjectNode) body.get("documents").get(1)).put("id", "fixture-002");
            fixture.respond(200, body.toString(), "application/json", false);
            assertFailure(fixture.adapter().search(new PlaceSearchQuery("test", null, null, 1, 1)), ProviderFailure.Code.INVALID_DATA);
        }
    }

    @Test
    void invalidMetaCannotBeAcceptedAsSuccessfulEmptyResults() throws Exception {
        try (Fixture fixture = new Fixture()) {
            KakaoPlaceSearchAdapter adapter = fixture.adapter();
            for (String field : List.of("total_count", "pageable_count", "is_end")) {
                ObjectNode body = response();
                ((ObjectNode) body.get("meta")).remove(field);
                fixture.respond(200, body.toString(), "application/json", false);
                assertFailure(adapter.search(query()), ProviderFailure.Code.INVALID_DATA);
            }
            ObjectNode body = response();
            ((ObjectNode) body.get("meta")).put("total_count", -1);
            fixture.respond(200, body.toString(), "application/json", false);
            assertFailure(adapter.search(query()), ProviderFailure.Code.INVALID_DATA);
        }
    }

    @Test
    void wrongContentTypeAndUnexpectedCompressionAreRejected() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.respond(200, response().toString(), "text/html", false);
            assertFailure(fixture.adapter().search(query()), ProviderFailure.Code.INVALID_DATA);
            fixture.handler.set(exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                byte[] bytes = response().toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            });
            assertFailure(fixture.adapter().search(query()), ProviderFailure.Code.INVALID_DATA);
        }
    }

    @Test
    void redirectsNeverForwardAuthorizationToAnotherPath() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.handler.set(exchange -> {
                exchange.getResponseHeaders().set("Location", "/sink");
                exchange.sendResponseHeaders(302, -1);
            });
            assertFailure(fixture.adapter().search(query()), ProviderFailure.Code.INVALID_DATA);
            assertEquals(1, fixture.requests.get());
            assertEquals(PATH, fixture.requestUri.get().getPath());
        }
    }

    @Test
    void oversizedDeclaredAndChunkedBodiesAreBounded() throws Exception {
        try (Fixture fixture = new Fixture()) {
            KakaoPlaceSearchAdapter adapter = fixture.adapter(fixture.authentication, Duration.ofSeconds(1), 128);
            fixture.respond(200, response().toString(), "application/json", false);
            assertFailure(adapter.search(query()), ProviderFailure.Code.INVALID_DATA);
            fixture.respond(200, response().toString(), "application/json", true);
            assertFailure(adapter.search(query()), ProviderFailure.Code.INVALID_DATA);
        }
    }

    @Test
    void rejectedResponsesAndAdapterDiagnosticsNeverExposeAuthentication(CapturedOutput output) throws Exception {
        try (Fixture fixture = new Fixture()) {
            KakaoPlaceSearchAdapter adapter = fixture.adapter();
            fixture.respond(401, "upstream echoed authentication: " + fixture.authentication, "application/json", false);
            PlaceSearchResult authError = adapter.search(query());
            fixture.respond(200, "{\"message\":\"" + fixture.authentication + "\"}", "application/json", false);
            PlaceSearchResult jsonError = adapter.search(query());
            assertFailure(authError, ProviderFailure.Code.AUTHENTICATION_FAILED);
            assertFailure(jsonError, ProviderFailure.Code.INVALID_DATA);
            assertFalse(authError.toString().contains(fixture.authentication));
            assertFalse(jsonError.toString().contains(fixture.authentication));
            assertFalse(adapter.toString().contains(fixture.authentication));
            assertFalse(output.getAll().contains(fixture.authentication));
            assertFalse(output.getAll().contains("Authorization"));
        }
    }
}
