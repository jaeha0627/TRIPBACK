package com.tripback.backend.places;

import com.tripback.backend.trip.provider.PlaceSearchQuery;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/** Keyword-only wire input. Strict decoding is local to this API. */
public record PlaceCandidatesRequest(String keyword) {
    static final int MAX_BODY_BYTES = 4096;

    public PlaceCandidatesRequest {
        if (keyword == null || keyword.isBlank() || keyword.length() > 200 || !keyword.equals(keyword.strip())
                || keyword.codePoints().anyMatch(Character::isISOControl)
                || !StandardCharsets.UTF_8.newEncoder().canEncode(keyword)) throw new InvalidRequest();
    }

    static PlaceCandidatesRequest decode(byte[] body, ObjectMapper mapper) {
        if (body.length == 0) throw new InvalidRequest();
        if (body.length > MAX_BODY_BYTES) throw new BodyTooLarge();
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            try (JsonParser parser = mapper.createParser(json)) {
                JsonNode root = mapper.reader(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(parser);
                if (parser.nextToken() != null || root == null || !root.isObject() || root.size() != 1
                        || root.get("keyword") == null || !root.get("keyword").isString()) throw new InvalidRequest();
                return new PlaceCandidatesRequest(root.get("keyword").asString());
            }
        } catch (Exception invalid) {
            // Do not include input fragments or parser exception causes in diagnostics.
            throw new InvalidRequest();
        }
    }

    PlaceSearchQuery toQuery() { return new PlaceSearchQuery(keyword, null, null, 1, 15); }
    static final class InvalidRequest extends RuntimeException {
        InvalidRequest() { super("INVALID_REQUEST", null, false, false); }
    }
    static final class BodyTooLarge extends RuntimeException {
        BodyTooLarge() { super("REQUEST_TOO_LARGE", null, false, false); }
    }
}
