package com.tripback.backend.trip.replan;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.*;
import java.nio.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;

/** Only progress observations and a total stay change; never accepts ETA, source or visit order. */
public record ReplanPreviewRequest(String planRef, long revision, int currentVisitIndex, String phase,
        OffsetDateTime observedAt, long elapsedStaySeconds, long requestedTotalStayMinutes) {
    static final int MAX_BYTES = 8192;
    static ReplanPreviewRequest decode(byte[] body, ObjectMapper mapper) {
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            try (JsonParser parser = mapper.createParser(json)) {
                JsonNode root = mapper.reader(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(parser);
                if (parser.nextToken() != null || root == null || !root.isObject()) throw invalid();
                Set<String> fields = Set.of("planRef", "revision", "currentVisitIndex", "phase", "observedAt",
                        "elapsedStaySeconds", "requestedTotalStayMinutes");
                for (String key : root.propertyNames()) if (!fields.contains(key)) throw invalid();
                for (String key : fields) if (root.get(key) == null || root.get(key).isNull()) throw invalid();
                String reference = text(root, "planRef");
                if (!reference.matches("[A-Za-z0-9_-]{43}")) throw invalid();
                long revision = integer(root, "revision"), index = integer(root, "currentVisitIndex");
                String phase = text(root, "phase");
                if (revision < 1 || index > 4 || !phase.equals("STAYING")) throw invalid();
                long elapsed = integer(root, "elapsedStaySeconds"), total = integer(root, "requestedTotalStayMinutes");
                if (elapsed > 604800 || total > 1440) throw invalid();
                return new ReplanPreviewRequest(reference, revision, (int) index, phase,
                        OffsetDateTime.parse(text(root, "observedAt")), elapsed, total);
            }
        } catch (JacksonException | IllegalArgumentException | DateTimeException | CharacterCodingException invalid) { throw invalid(); }
    }
    private static String text(JsonNode root, String key) {
        var n = root.get(key);
        if (!n.isString() || n.asString().isBlank() || n.asString().length() > 100) throw invalid();
        return n.asString();
    }
    private static long integer(JsonNode root, String key) {
        var n = root.get(key);
        if (!n.isIntegralNumber() || !n.canConvertToLong() || n.longValue() < 0) throw invalid();
        return n.longValue();
    }
    static BadRequest invalid() { return new BadRequest(); }
    static final class BadRequest extends RuntimeException {
        BadRequest() { super("INVALID_REQUEST", null, false, false); }
    }
}
