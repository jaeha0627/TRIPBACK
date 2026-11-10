package com.tripback.backend.trip.recommendation;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Local strict JSON utilities. No diagnostics retain untrusted input or exception causes. */
final class RecommendationJson {
    static JsonNode parse(byte[] bytes, int limit, ObjectMapper mapper) {
        check(bytes != null && bytes.length > 0 && bytes.length <= limit, "JSON_SIZE_LIMIT");
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try (JsonParser parser = mapper.createParser(json)) {
                JsonNode root = mapper.reader(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(parser);
                check(root != null && parser.nextToken() == null, "INVALID_JSON");
                return root;
            }
        } catch (Exception error) { throw new Rejected("INVALID_JSON"); }
    }
    static void object(JsonNode node, Set<String> required, Set<String> optional) {
        check(node != null && node.isObject(), "INVALID_STRUCTURE");
        for (String field : node.propertyNames()) check(required.contains(field) || optional.contains(field), "UNSUPPORTED_FIELD");
        for (String field : required) check(node.get(field) != null && !node.get(field).isNull(), "MISSING_FIELD");
    }
    static String text(JsonNode node, int max) {
        check(node != null && node.isString(), "INVALID_TEXT");
        String value = node.asString();
        check(!value.isBlank() && value.length() <= max && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl) && StandardCharsets.UTF_8.newEncoder().canEncode(value), "INVALID_TEXT");
        return value;
    }
    static long integer(JsonNode node, long min, long max) {
        check(node != null && node.isIntegralNumber() && node.canConvertToLong(), "INVALID_INTEGER");
        long value = node.longValue(); check(value >= min && value <= max, "INTEGER_OUT_OF_RANGE"); return value;
    }
    static String choice(JsonNode node, Set<String> allowed) {
        String value = text(node, 100); check(allowed.contains(value), "UNSUPPORTED_VALUE"); return value;
    }
    static List<String> choices(JsonNode node, Set<String> allowed) {
        check(node != null && node.isArray() && node.size() <= allowed.size(), "INVALID_ARRAY");
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(choice(item, allowed)));
        check(new HashSet<>(values).size() == values.size(), "DUPLICATE_VALUE"); return List.copyOf(values);
    }
    static void check(boolean valid, String code) { if (!valid) throw new Rejected(code); }
    static final class Rejected extends RuntimeException {
        final String code;
        Rejected(String code) { super(code, null, false, false); this.code = code; }
    }
}
