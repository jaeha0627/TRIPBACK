package com.tripback.backend.trip.replan;

import com.tripback.backend.trip.integration.TripValidationResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

/** Bounded process-local development cache, not an authenticated trip/session repository.
 * No HTTP deserialization entry point. Only server-owned validation results may be registered.
 */
@Component
@ConditionalOnProperty(name = "TRIPBACK_ENABLE_REPLAN_PREVIEW_API", havingValue = "true", matchIfMissing = false)
public final class ReplanPlanStore {
    public record Handle(String planRef, long revision, Instant expiresAt) {}
    record Entry(ValidatedReplanPlan plan, Instant originalDeparture, Handle handle) {}
    public record Registration(String status, Handle handle) {}
    private final Map<String, Entry> entries = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Duration ttl;
    private final int capacity;
    public ReplanPlanStore() { this(Clock.systemUTC(), Duration.ofMinutes(30), 256); }
    ReplanPlanStore(Clock clock, Duration ttl, int capacity) {
        this.clock = Objects.requireNonNull(clock);
        if (ttl == null || ttl.isNegative() || ttl.isZero() || capacity < 1) throw new IllegalArgumentException("INVALID_CACHE_POLICY");
        this.ttl = ttl; this.capacity = capacity;
    }
    Instant now() { return clock.instant(); }

    /** No client-constructed plan or evidence may be passed here by a future HTTP handler. */
    public synchronized Registration registerServerValidation(TripValidationResult result) {
        ValidatedReplanPlan plan;
        try {
            // /validate has no optional-stop contract: conservatively retain every requested stop.
            var required = new HashSet<>(result.input().visits().stream().map(v -> v.selectedId()).toList());
            plan = ValidatedReplanPlan.fromServerValidation(result, required);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return new Registration("NOT_ELIGIBLE", null);
        }
        Instant now = now();
        entries.values().removeIf(e -> !now.isBefore(e.handle.expiresAt()));
        if (entries.size() >= capacity) return new Registration("CAPACITY_EXCEEDED", null);
        String reference;
        do {
            byte[] token = new byte[32]; random.nextBytes(token);
            reference = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        } while (entries.containsKey(reference));
        Handle handle = new Handle(reference, 1, now.plus(ttl));
        entries.put(reference, new Entry(plan, result.input().departAt().toInstant(), handle));
        return new Registration("REGISTERED", handle);
    }
    synchronized Entry find(String reference) {
        var entry = entries.get(reference);
        if (entry == null) return null;
        if (!now().isBefore(entry.handle.expiresAt())) { entries.remove(reference); return null; }
        return entry;
    }
}
