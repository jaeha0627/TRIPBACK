package com.tripback.backend.trip.provider.kakao;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import com.tripback.backend.trip.provider.*;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Blocking server-side adapter. No request, headers, credentials or upstream bodies are logged. */
public final class KakaoPlaceSearchAdapter implements PlaceSearchProvider, AutoCloseable {
    public static final String KEY_ENVIRONMENT_VARIABLE = "TRIPBACK_KAKAO_LOCAL_REST_API_KEY";
    static final URI OFFICIAL_ENDPOINT = URI.create("https://dapi.kakao.com/v2/local/search/keyword.json");
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final String PROVIDER = "KAKAO_LOCAL";

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Supplier<String> keySource;
    private final Clock clock;
    private final Duration timeout;
    private final int maxBytes;

    public KakaoPlaceSearchAdapter(ObjectMapper mapper) {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build(),
                mapper, OFFICIAL_ENDPOINT, () -> System.getenv(KEY_ENVIRONMENT_VARIABLE), Clock.systemUTC(),
                REQUEST_TIMEOUT, MAX_RESPONSE_BYTES);
    }

    // Package-private seam for loopback HTTP tests. Production endpoint/credential sources are not configurable.
    KakaoPlaceSearchAdapter(HttpClient client, ObjectMapper mapper, URI endpoint, Supplier<String> keySource,
            Clock clock, Duration timeout, int maxBytes) {
        this.client = Objects.requireNonNull(client);
        this.mapper = Objects.requireNonNull(mapper);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.keySource = Objects.requireNonNull(keySource);
        this.clock = Objects.requireNonNull(clock);
        this.timeout = Objects.requireNonNull(timeout);
        this.maxBytes = maxBytes;
        boolean loopbackTest = "http".equals(endpoint.getScheme())
                && List.of("127.0.0.1", "localhost", "[::1]").contains(endpoint.getHost());
        if ((!OFFICIAL_ENDPOINT.equals(endpoint) && !loopbackTest) || endpoint.getUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getFragment() != null
                || client.followRedirects() != HttpClient.Redirect.NEVER || client.connectTimeout().isEmpty()
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(60)) > 0
                || maxBytes < 1 || maxBytes > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("Unsafe provider transport configuration");
        }
    }

    @Override
    public PlaceSearchResult search(PlaceSearchQuery query) {
        Objects.requireNonNull(query);
        Instant attemptedAt = clock.instant();
        String key;
        try {
            key = keySource.get();
        } catch (RuntimeException unavailable) {
            return unavailable(attemptedAt);
        }
        if (key == null || key.isBlank() || key.length() > 512 || key.chars().anyMatch(c -> c < 33 || c > 126)) {
            return unavailable(attemptedAt);
        }
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(requestUri(query)).timeout(timeout)
                    .header("Authorization", "KakaoAK " + key).header("Accept", "application/json")
                    .header("Accept-Encoding", "identity").GET().build();
            pending = client.sendAsync(request, info -> {
                int status = info.statusCode();
                if (status >= 400 && status <= 599) throw new FailureSignal(ProviderFailure.fromHttpStatus(status));
                if (status != 200) throw invalidData(); // Redirects and unexpected success codes are never followed.
                String type = info.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip();
                String encoding = info.headers().firstValue("Content-Encoding").orElse("identity");
                long length = contentLength(info);
                if (!"application/json".equals(type.toLowerCase(Locale.ROOT)) || !"identity".equalsIgnoreCase(encoding)
                        || length < 0 || length > maxBytes) throw invalidData();
                return new BoundedBody(maxBytes);
            });
            // Whole-body deadline, including a slow/chunked body after headers (not just connect/header timeout).
            byte[] body = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS).body();
            return parse(body, query, attemptedAt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed(ProviderFailure.of(ProviderFailure.Code.CONNECTION_FAILED), attemptedAt);
        } catch (TimeoutException timeoutFailure) {
            return failed(ProviderFailure.of(ProviderFailure.Code.TIMEOUT), attemptedAt);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            ProviderFailure error = cause instanceof FailureSignal signal ? signal.failure
                    : ProviderFailure.of(cause instanceof HttpTimeoutException
                        ? ProviderFailure.Code.TIMEOUT : ProviderFailure.Code.CONNECTION_FAILED);
            return failed(error, attemptedAt);
        } catch (RuntimeException invalid) {
            return failed(invalid instanceof FailureSignal signal ? signal.failure
                    : ProviderFailure.of(ProviderFailure.Code.INVALID_DATA), attemptedAt);
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    private URI requestUri(PlaceSearchQuery query) {
        String parameters = "query=" + URLEncoder.encode(query.keyword(), StandardCharsets.UTF_8)
                + "&page=" + query.page() + "&size=" + query.size();
        if (query.center() != null) parameters += "&x=" + query.center().longitude() + "&y=" + query.center().latitude();
        if (query.radiusMeters() != null) parameters += "&radius=" + query.radiusMeters();
        return URI.create(endpoint.toASCIIString() + "?" + parameters);
    }

    private PlaceSearchResult parse(byte[] body, PlaceSearchQuery query, Instant attemptedAt) {
        try (JsonParser parser = mapper.createParser(body)) {
            JsonNode root = mapper.reader(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(parser);
            if (parser.nextToken() != null || root == null || !root.isObject()) throw invalidData();
            JsonNode documents = root.get("documents");
            JsonNode meta = root.get("meta");
            if (documents == null || !documents.isArray() || documents.size() > query.size()
                    || meta == null || !meta.isObject()) throw invalidData();
            int total = count(meta.get("total_count"));
            int pageable = count(meta.get("pageable_count"));
            if (total < documents.size() || pageable < documents.size() || pageable > total
                    || meta.get("is_end") == null || !meta.get("is_end").isBoolean()) throw invalidData();
            Instant retrievedAt = clock.instant();
            boolean live = OFFICIAL_ENDPOINT.equals(endpoint);
            ProviderData data = new ProviderData(PROVIDER, live ? ProviderData.DataMode.VERIFIED_LIVE
                    : ProviderData.DataMode.DEMO_SIMULATED, live ? ProviderData.Verification.VALIDATED_PROVIDER_DATA
                    : ProviderData.Verification.UNVERIFIED, retrievedAt);
            List<PlaceCandidate> candidates = new ArrayList<>();
            HashSet<String> ids = new HashSet<>();
            for (JsonNode document : documents) {
                if (!document.isObject()) throw invalidData();
                String id = text(document, "id", true);
                if (!ids.add(id)) throw invalidData();
                String name = text(document, "place_name", true);
                double longitude = coordinate(document, "x");
                double latitude = coordinate(document, "y");
                String road = text(document, "road_address_name", false);
                String lot = text(document, "address_name", false);
                String address = road.isBlank() ? lot : road;
                candidates.add(new PlaceCandidate(new ProviderPlaceId(PROVIDER, id), name, address,
                        new Wgs84Point(latitude, longitude), data));
            }
            return new PlaceSearchResult(PROVIDER, candidates.isEmpty() ? PlaceSearchResult.Status.NO_RESULTS
                    : PlaceSearchResult.Status.FOUND, candidates, null, attemptedAt);
        }
    }

    private static String text(JsonNode object, String field, boolean required) {
        JsonNode value = object.get(field);
        if (!required && (value == null || value.isNull())) return "";
        if (value == null || !value.isString() || (required && value.asString().isBlank())) throw invalidData();
        return value.asString();
    }

    private static double coordinate(JsonNode document, String field) {
        String value = text(document, field, true);
        if (!value.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) throw invalidData();
        return Double.parseDouble(value);
    }

    private static long contentLength(HttpResponse.ResponseInfo info) {
        try {
            return info.headers().firstValueAsLong("Content-Length").orElse(0);
        } catch (NumberFormatException invalid) {
            throw invalidData();
        }
    }

    private static int count(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || value.intValue() < 0) throw invalidData();
        return value.intValue();
    }

    private static FailureSignal invalidData() { return new FailureSignal(ProviderFailure.of(ProviderFailure.Code.INVALID_DATA)); }

    private static PlaceSearchResult failed(ProviderFailure failure, Instant at) {
        return new PlaceSearchResult(PROVIDER, PlaceSearchResult.Status.FAILED, List.of(), failure, at);
    }

    private static PlaceSearchResult unavailable(Instant at) {
        return new PlaceSearchResult(PROVIDER, PlaceSearchResult.Status.UNAVAILABLE, List.of(),
                ProviderFailure.of(ProviderFailure.Code.NOT_CONFIGURED), at);
    }

    @Override public void close() { client.shutdownNow(); }
    @Override public String toString() { return "KakaoPlaceSearchAdapter[credentials=redacted]"; }

    private static final class FailureSignal extends RuntimeException {
        private final ProviderFailure failure;
        private FailureSignal(ProviderFailure failure) {
            super("Provider request rejected", null, false, false);
            this.failure = failure;
        }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;
        private BoundedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - output.size()) {
                    body.completeExceptionally(invalidData());
                    subscription.cancel();
                    return;
                }
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                output.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { body.completeExceptionally(new IOException("Provider connection failed")); }
        @Override public void onComplete() { body.complete(output.toByteArray()); }
    }
}
