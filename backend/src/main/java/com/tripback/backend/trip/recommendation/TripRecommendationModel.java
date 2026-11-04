package com.tripback.backend.trip.recommendation;

import tools.jackson.databind.JsonNode;
import java.util.List;

/** Provider-independent model boundary. Generated JSON is always untrusted and separately validated. */
public interface TripRecommendationModel {
    default String provider() { return "CUSTOM"; }
    boolean configured();
    Generation generate(Prompt prompt);
    record Prompt(JsonNode preferences, List<PromptCandidate> candidates) {
        public Prompt { candidates = List.copyOf(candidates); }
    }
    record PromptCandidate(String provider, String placeId, String name, String address) {}
    enum Invocation { NOT_CALLED, OPENAI_HTTP, TEST_HTTP, FAKE_MODEL }
    enum Status { COMPLETED, NOT_CONFIGURED, AUTHENTICATION_FAILED, RATE_LIMITED, HTTP_ERROR,
        TIMEOUT, CONNECTION_FAILED, REFUSED, INCOMPLETE, INVALID_RESPONSE, RESPONSE_TOO_LARGE, OUTPUT_TOO_LARGE, PROVIDER_EXCEPTION }
    record Generation(Status status, Invocation invocation, String json) {
        public Generation {
            if (status == null || invocation == null || (status == Status.COMPLETED) != (json != null)) {
                throw new IllegalArgumentException("Inconsistent model outcome");
            }
        }
        // A generation's raw content must never leak through ordinary diagnostic logging.
        @Override public String toString() { return "Generation[status=" + status + ", invocation=" + invocation + ", json=redacted]"; }
    }
}
