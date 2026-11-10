package com.tripback.backend.trip.replan;

import com.tripback.backend.trip.integration.*;
import com.tripback.backend.trip.provider.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline-only trusted validation fixtures. No real place, location or provider invocation. */
final class ReplanApiFixtures {
    static final Instant NOW = Instant.parse("2026-11-07T01:00:00Z");
    static final Wgs84Point HOME = new Wgs84Point(37, 127);
    static ProviderPlaceId id(int index) { return new ProviderPlaceId("KAKAO_LOCAL", "replan-fixture-" + index); }
    static final class MutableClock extends Clock {
        volatile Instant at = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return at; }
    }
    static final class Providers implements PlaceSearchProvider, RoadRouteProvider {
        final AtomicInteger searches = new AtomicInteger(), routes = new AtomicInteger();
        volatile boolean demo, missing;
        volatile Instant retrievedAt = NOW;
        void reset() { searches.set(0); routes.set(0); demo = false; missing = false; retrievedAt = NOW; }
        ProviderData data(String provider) {
            return new ProviderData(provider, demo ? ProviderData.DataMode.DEMO_SIMULATED : ProviderData.DataMode.VERIFIED_CACHED,
                    demo ? ProviderData.Verification.UNVERIFIED : ProviderData.Verification.VALIDATED_PROVIDER_DATA, retrievedAt);
        }
        @Override public PlaceSearchResult search(PlaceSearchQuery q) {
            searches.incrementAndGet();
            int i = Integer.parseInt(q.keyword());
            return new PlaceSearchResult("KAKAO_LOCAL", PlaceSearchResult.Status.FOUND, List.of(new PlaceCandidate(
                    id(i), "FAKE place " + i, "", new Wgs84Point(37 + (i + 1) * .01, 127), data("KAKAO_LOCAL"))), null, retrievedAt);
        }
        @Override public RoadRouteResult route(RoadRouteQuery q) {
            routes.incrementAndGet();
            return missing ? new RoadRouteResult("KAKAO_MOBILITY", q, RoadRouteResult.Status.ETA_MISSING,
                    null, null, ProviderFailure.of(ProviderFailure.Code.MISSING_ETA), retrievedAt)
                    : new RoadRouteResult("KAKAO_MOBILITY", q, RoadRouteResult.Status.FOUND, 600L,
                            data("KAKAO_MOBILITY"), null, retrievedAt);
        }
    }
    static TripValidationResult validation(Providers providers, Instant deadline, long buffer) {
        return new TripValidationService(providers, providers).validate(new TripValidationInput(HOME,
                NOW.minusSeconds(7200).atOffset(ZoneOffset.UTC), deadline.atOffset(ZoneOffset.UTC), buffer,
                List.of(new TripValidationInput.Visit("0", id(0), 30), new TripValidationInput.Visit("1", id(1), 20))));
    }
}
