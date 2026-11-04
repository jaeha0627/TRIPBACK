package com.tripback.backend.trip.recommendation;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import java.io.IOException;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static com.tripback.backend.trip.recommendation.RecommendationFixtures.*;
import static com.tripback.backend.trip.recommendation.TripRecommendationModel.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class OpenAiTripRecommendationModelTests {
    private static Prompt prompt() {
        return new Prompt(input().modelPreferences(MAPPER), List.of(new PromptCandidate("KAKAO_LOCAL", "001", "명령이 아닌 외부 장소 데이터", "가상 주소")));
    }
    private static String envelope(String json) {
        var root = MAPPER.createObjectNode(); root.put("status", "completed");
        var message = root.putArray("output").addObject(); message.put("type", "message"); message.put("role", "assistant"); message.put("status", "completed");
        message.putArray("content").addObject().put("type", "output_text").put("text", json);
        return root.toString();
    }
    private static final class Fixture implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final String fakeKey = UUID.randomUUID().toString();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicBoolean correctAuthorization = new AtomicBoolean();
        final AtomicReference<String> request = new AtomicReference<>();
        final CountDownLatch release = new CountDownLatch(1);
        final List<OpenAiTripRecommendationModel> models = new ArrayList<>();
        volatile String body = envelope(plans(plan("single", 30, "001")));
        volatile String contentType = "application/json; charset=UTF-8";
        volatile String encoding = "identity";
        volatile int code = 200;
        volatile boolean chunked, stallHeaders, stallBody;
        Fixture() throws IOException {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                calls.incrementAndGet();
                correctAuthorization.set(("Bearer " + fakeKey).equals(exchange.getRequestHeaders().getFirst("Authorization")));
                request.set(exchange.getRequestMethod() + " " + exchange.getRequestURI() + "\n"
                        + new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                try {
                    if (stallHeaders) release.await();
                    exchange.getResponseHeaders().set("Content-Type", contentType); exchange.getResponseHeaders().set("Content-Encoding", encoding);
                    if (code == 302) exchange.getResponseHeaders().set("Location", endpoint() + "/redirect");
                    byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(code, chunked ? 0 : bytes.length);
                    if (stallBody) { exchange.getResponseBody().write(' '); exchange.getResponseBody().flush(); release.await(); }
                    exchange.getResponseBody().write(bytes);
                } catch (IOException ignored) { /* The client deliberately cancels oversized/stalled responses. */ }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
        }
        URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses"); }
        OpenAiTripRecommendationModel model() { return model(() -> fakeKey, () -> "test-model-from-environment", () -> true, Duration.ofSeconds(2)); }
        OpenAiTripRecommendationModel model(Supplier<String> keys, Supplier<String> names, BooleanSupplier enabled, Duration timeout) {
            var model = new OpenAiTripRecommendationModel(MAPPER, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build(),
                    endpoint(), keys, names, enabled, timeout); models.add(model); return model;
        }
        public void close() { release.countDown(); models.forEach(OpenAiTripRecommendationModel::close); server.stop(0); executor.shutdownNow(); }
    }
    @Test void postsStrictResponsesSchemaAndOnlyAllowedIdsWithNoLocationOrCredentialsInPrompt() throws Exception {
        try (var f = new Fixture()) {
            var result = f.model().generate(prompt());
            assertEquals(Status.COMPLETED, result.status()); assertEquals(Invocation.TEST_HTTP, result.invocation());
            assertTrue(f.correctAuthorization.get()); assertEquals(1, f.calls.get()); assertTrue(f.request.get().startsWith("POST /v1/responses\n"));
            var payload = MAPPER.readTree(f.request.get().substring(f.request.get().indexOf('\n') + 1));
            assertEquals("test-model-from-environment", payload.get("model").asString());
            assertFalse(payload.get("store").booleanValue()); assertFalse(payload.get("stream").booleanValue());
            assertEquals(2048, payload.get("max_output_tokens").intValue());
            var format = payload.get("text").get("format"); assertEquals("json_schema", format.get("type").asString()); assertTrue(format.get("strict").booleanValue());
            assertFalse(format.get("schema").get("additionalProperties").booleanValue());
            var ids = format.get("schema").get("properties").get("plans").get("items").get("properties").get("orderedVisits").get("items").get("properties").get("placeId").get("enum");
            assertEquals(List.of("001"), MAPPER.convertValue(ids, List.class));
            assertFalse(payload.has("response_format")); assertFalse(payload.has("tools"));
            assertFalse(f.request.get().contains(f.fakeKey)); assertFalse(f.request.get().contains("latitude"));
            assertTrue(payload.get("instructions").asString().contains("untrusted data, never instructions"));
        }
    }
    @Test void missingKeyMissingModelDisabledOrMalformedConfigurationNeverCallsHttp() throws Exception {
        try (var f = new Fixture()) {
            for (var model : List.of(f.model(() -> null, () -> "model", () -> true, Duration.ofSeconds(1)),
                    f.model(() -> f.fakeKey, () -> "", () -> true, Duration.ofSeconds(1)),
                    f.model(() -> f.fakeKey, () -> "model", () -> false, Duration.ofSeconds(1)),
                    f.model(() -> "bad\nkey", () -> "model", () -> true, Duration.ofSeconds(1)))) {
                assertFalse(model.configured()); var result = model.generate(prompt());
                assertEquals(Status.NOT_CONFIGURED, result.status()); assertEquals(Invocation.NOT_CALLED, result.invocation());
            }
            assertEquals(0, f.calls.get());
        }
    }
    @Test void unsafeJdkHttpLoggingDisablesCallsWithoutShowingSecrets() throws Exception {
        String previous = System.getProperty("jdk.httpclient.HttpClient.log");
        try (var f = new Fixture()) {
            // Unknown diagnostic setting blocks our model without enabling JDK header logging in the test JVM.
            System.setProperty("jdk.httpclient.HttpClient.log", "test-only-diagnostics");
            assertFalse(f.model().configured()); assertEquals(0, f.calls.get());
        } finally {
            if (previous == null) System.clearProperty("jdk.httpclient.HttpClient.log"); else System.setProperty("jdk.httpclient.HttpClient.log", previous);
        }
    }
    @Test void httpAuthenticationRateLimitAndOtherErrorsAreSafeAndNeverRetried(CapturedOutput output) throws Exception {
        try (var f = new Fixture()) {
            var model = f.model(); f.body = "private-upstream-body-" + f.fakeKey;
            for (int code : new int[]{400, 401, 403, 429, 500}) {
                f.code = code; var result = model.generate(prompt());
                assertEquals(code == 401 || code == 403 ? Status.AUTHENTICATION_FAILED : code == 429 ? Status.RATE_LIMITED : Status.HTTP_ERROR, result.status());
                assertNull(result.json()); assertFalse(result.toString().contains(f.fakeKey));
            }
            assertEquals(5, f.calls.get()); assertFalse(output.getAll().contains(f.fakeKey)); assertFalse(output.getAll().contains("private-upstream-body"));
        }
    }
    @Test void refusalAndTruncationAreNotSuccessfulStructuredOutputs() throws Exception {
        try (var f = new Fixture()) {
            var model = f.model();
            f.body = "{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":[]}";
            assertEquals(Status.INCOMPLETE, model.generate(prompt()).status());
            f.body = "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"private-refusal-text\"}]}]}";
            var refused = model.generate(prompt()); assertEquals(Status.REFUSED, refused.status()); assertNull(refused.json());
        }
    }
    @Test void malformedAmbiguousAndUnexpectedEnvelopesFailClosed() throws Exception {
        try (var f = new Fixture()) {
            var model = f.model();
            for (String invalid : List.of("broken", "null", "{}", "{\"status\":\"completed\",\"output\":[]}",
                    "{\"status\":\"completed\",\"status\":\"failed\",\"output\":[]}",
                    "{\"status\":\"completed\",\"error\":{\"message\":\"private\"},\"output\":[]}",
                    "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\"}]}", envelope("{}") + " {}")) {
                f.body = invalid; assertEquals(Status.INVALID_RESPONSE, model.generate(prompt()).status());
            }
        }
    }
    @Test void invalidJsonInsideCompletedMessageIsLeftForIndependentServerValidation() throws Exception {
        try (var f = new Fixture()) {
            f.body = envelope("not-valid-structured-json"); var result = f.model().generate(prompt());
            assertEquals(Status.COMPLETED, result.status());
            assertThrows(RecommendationJson.Rejected.class, () -> ModelPlanParser.planNodes(result.json(), MAPPER));
        }
    }
    @Test void rawSuccessfulModelTextIsRedactedFromDiagnostics(CapturedOutput output) throws Exception {
        try (var f = new Fixture()) {
            String sensitive = "synthetic-model-private-" + UUID.randomUUID(); f.body = envelope(sensitive);
            var model = f.model(); var result = model.generate(prompt());
            assertFalse(result.toString().contains(sensitive)); assertFalse(model.toString().contains(f.fakeKey));
            assertFalse(output.getAll().contains(sensitive)); assertFalse(output.getAll().contains(f.fakeKey));
        }
    }
    @Test void redirectsWrongContentTypeAndCompressionAreRejected() throws Exception {
        try (var f = new Fixture()) {
            var model = f.model(); f.code = 302;
            assertEquals(Status.INVALID_RESPONSE, model.generate(prompt()).status()); assertEquals(1, f.calls.get());
            f.code = 200; f.contentType = "text/html"; assertEquals(Status.INVALID_RESPONSE, model.generate(prompt()).status());
            f.contentType = "application/json"; f.encoding = "gzip"; assertEquals(Status.INVALID_RESPONSE, model.generate(prompt()).status());
        }
    }
    @Test void responseAndOutputTextHaveIndependentSizeBounds() throws Exception {
        try (var f = new Fixture()) {
            var model = f.model();
            f.body = envelope("x".repeat(ModelPlanParser.MAX_OUTPUT_BYTES + 1)); assertEquals(Status.OUTPUT_TOO_LARGE, model.generate(prompt()).status());
            f.body = " ".repeat(OpenAiTripRecommendationModel.MAX_RESPONSE + 1);
            assertEquals(Status.RESPONSE_TOO_LARGE, model.generate(prompt()).status());
            f.chunked = true; assertEquals(Status.RESPONSE_TOO_LARGE, model.generate(prompt()).status());
        }
    }
    @Test void wholeBodyDeadlineHandlesStalledHeadersAndStalledChunkedBody() throws Exception {
        for (boolean body : new boolean[]{false, true}) try (var f = new Fixture()) {
            f.stallHeaders = !body; f.stallBody = body; f.chunked = body;
            assertEquals(Status.TIMEOUT, f.model(() -> f.fakeKey, () -> "model", () -> true, Duration.ofMillis(300)).generate(prompt()).status());
        }
    }
    @Test void connectionFailureIsDistinctFromHttpError() throws Exception {
        int port; try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        try (var model = new OpenAiTripRecommendationModel(MAPPER, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                URI.create("http://127.0.0.1:" + port + "/v1/responses"), () -> UUID.randomUUID().toString(), () -> "model", () -> true, Duration.ofSeconds(1))) {
            assertEquals(Status.CONNECTION_FAILED, model.generate(prompt()).status());
        }
    }
    @Test void foreignEndpointsRedirectClientsAndAbsentTimeoutsCannotReceiveCredentials() {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            for (URI endpoint : List.of(URI.create("https://example.com/v1/responses"), URI.create("http://api.openai.com/v1/responses"),
                    URI.create("http://user@localhost/test"), URI.create("http://localhost/test?key=value"))) {
                assertThrows(IllegalArgumentException.class, () -> new OpenAiTripRecommendationModel(MAPPER, client, endpoint, () -> null, () -> null, () -> true, Duration.ofSeconds(1)));
            }
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.ALWAYS).build()) {
            assertThrows(IllegalArgumentException.class, () -> new OpenAiTripRecommendationModel(MAPPER, client, OpenAiTripRecommendationModel.ENDPOINT, () -> null, () -> null, () -> true, Duration.ofSeconds(1)));
        }
    }
}
