package com.tripback.backend.trip.provider.kakao;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
public final class KakaoRoadRouteAdapter implements RoadRouteProvider, AutoCloseable {
    public static final String KEY_ENVIRONMENT_VARIABLE = "TRIPBACK_KAKAO_MOBILITY_REST_API_KEY";
    static final URI OFFICIAL_ENDPOINT = URI.create("https://apis-navi.kakaomobility.com/v1/directions");
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final String PROVIDER = "KAKAO_MOBILITY";

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Supplier<String> keySource;
    private final Clock clock;
    private final Duration timeout;
    private final int maxBytes;

    public KakaoRoadRouteAdapter(ObjectMapper mapper) {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build(),
                mapper, OFFICIAL_ENDPOINT, () -> System.getenv(KEY_ENVIRONMENT_VARIABLE), Clock.systemUTC(),
                REQUEST_TIMEOUT, MAX_RESPONSE_BYTES);
    }

    // Package-private seam for loopback HTTP tests. Production endpoint/credential sources are not configurable.
    KakaoRoadRouteAdapter(HttpClient client, ObjectMapper mapper, URI endpoint, Supplier<String> keySource,
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
    public RoadRouteResult route(RoadRouteQuery query) {
        Objects.requireNonNull(query);
        Instant attemptedAt = clock.instant();
        String key;
        try {
            key = keySource.get();
        } catch (RuntimeException unavailable) {
            return unavailable(query, attemptedAt);
        }
        if (key == null || key.isBlank() || key.length() > 512 || key.chars().anyMatch(c -> c < 33 || c > 126)) {
            return unavailable(query, attemptedAt);
        }
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(requestUri(query)).timeout(timeout)
                    .header("Authorization", "KakaoAK " + key).header("Accept", "application/json")
                    .header("Content-Type", "application/json")
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
            return failed(query, ProviderFailure.of(ProviderFailure.Code.CONNECTION_FAILED), attemptedAt);
        } catch (TimeoutException timeoutFailure) {
            return failed(query, ProviderFailure.of(ProviderFailure.Code.TIMEOUT), attemptedAt);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            ProviderFailure error = cause instanceof FailureSignal signal ? signal.failure
                    : ProviderFailure.of(cause instanceof HttpTimeoutException
                        ? ProviderFailure.Code.TIMEOUT : ProviderFailure.Code.CONNECTION_FAILED);
            return failed(query, error, attemptedAt);
        } catch (RuntimeException invalid) {
            return failed(query, invalid instanceof FailureSignal signal ? signal.failure
                    : ProviderFailure.of(ProviderFailure.Code.INVALID_DATA), attemptedAt);
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    private URI requestUri(RoadRouteQuery query) {
        return URI.create(endpoint.toASCIIString() + "?origin=" + coordinates(query.origin())
                + "&destination=" + coordinates(query.destination()) + "&priority=RECOMMEND&summary=true");
    }

    private static String coordinates(Wgs84Point point) {
        return point.longitude() + "," + point.latitude();
    }

    private RoadRouteResult parse(byte[] body, RoadRouteQuery query, Instant attemptedAt) {
        try (JsonParser parser = mapper.createParser(body)) {
            JsonNode root = mapper.reader(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(parser);
            if (parser.nextToken() != null || root == null || !root.isObject()) throw invalidData();
            JsonNode routes = root.get("routes");
            // No alternatives were requested. Never silently choose among unexpected routes.
            if (routes == null || !routes.isArray() || routes.size() != 1) throw invalidData();
            JsonNode route = routes.get(0);
            if (!route.isObject()) throw invalidData();
            JsonNode code = route.get("result_code");
            if (code == null || !code.isIntegralNumber() || !code.canConvertToInt()) throw invalidData();
            int resultCode = code.intValue();
            // Official ordinary-directions codes; multi-origin/destination codes are not applicable here.
            if (resultCode == 1 || (resultCode >= 101 && resultCode <= 107)) {
                return new RoadRouteResult(PROVIDER, query, RoadRouteResult.Status.NO_ROUTE,
                        null, null, null, attemptedAt);
            }
            if (resultCode != 0) throw invalidData();
            JsonNode summary = route.get("summary");
            if (summary == null || !summary.isObject()) throw invalidData();
            JsonNode duration = summary.get("duration");
            if (duration == null || duration.isNull()) {
                return new RoadRouteResult(PROVIDER, query, RoadRouteResult.Status.ETA_MISSING,
                        null, null, ProviderFailure.of(ProviderFailure.Code.MISSING_ETA), attemptedAt);
            }
            if (!duration.isIntegralNumber() || !duration.canConvertToLong() || duration.longValue() < 0) throw invalidData();
            // Upstream snapped coordinates are deliberately not copied into the requested endpoints.
            boolean live = OFFICIAL_ENDPOINT.equals(endpoint);
            ProviderData data = new ProviderData(PROVIDER, live ? ProviderData.DataMode.VERIFIED_LIVE
                    : ProviderData.DataMode.DEMO_SIMULATED, live ? ProviderData.Verification.VALIDATED_PROVIDER_DATA
                    : ProviderData.Verification.UNVERIFIED, clock.instant());
            return new RoadRouteResult(PROVIDER, query, RoadRouteResult.Status.FOUND,
                    duration.longValue(), data, null, attemptedAt);
        }
    }
    private static long contentLength(HttpResponse.ResponseInfo info) {
        try {
            return info.headers().firstValueAsLong("Content-Length").orElse(0);
        } catch (NumberFormatException invalid) {
            throw invalidData();
        }
    }

    private static FailureSignal invalidData() { return new FailureSignal(ProviderFailure.of(ProviderFailure.Code.INVALID_DATA)); }

    private static RoadRouteResult failed(RoadRouteQuery query, ProviderFailure failure, Instant at) {
        return new RoadRouteResult(PROVIDER, query, RoadRouteResult.Status.FAILED, null, null, failure, at);
    }

    private static RoadRouteResult unavailable(RoadRouteQuery query, Instant at) {
        return new RoadRouteResult(PROVIDER, query, RoadRouteResult.Status.UNAVAILABLE, null, null,
                ProviderFailure.of(ProviderFailure.Code.NOT_CONFIGURED), at);
    }
    @Override public void close() { client.shutdownNow(); }
    @Override public String toString() { return "KakaoRoadRouteAdapter[credentials=redacted]"; }

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
