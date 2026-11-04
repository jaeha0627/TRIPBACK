package com.tripback.backend.trip.recommendation;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static com.tripback.backend.trip.recommendation.RecommendationJson.*;

/** Request-local deadline and hard provider invocation bounds, including revalidation. */
final class RecommendationBudget {
    private final long started = System.nanoTime();
    private final long durationNanos;
    final AtomicInteger searches = new AtomicInteger();
    final AtomicInteger routes = new AtomicInteger();
    RecommendationBudget(Duration duration) { durationNanos = duration.toNanos(); }
    void checkTime() { check(!Thread.currentThread().isInterrupted() && System.nanoTime() - started < durationNanos, "PROCESSING_TIMEOUT"); }
    void search() { checkTime(); check(searches.getAndUpdate(n -> n < 18 ? n + 1 : n) < 18, "SEARCH_CALL_LIMIT"); }
    void route() { checkTime(); check(routes.getAndUpdate(n -> n < 12 ? n + 1 : n) < 12, "ROUTE_CALL_LIMIT"); }
}
