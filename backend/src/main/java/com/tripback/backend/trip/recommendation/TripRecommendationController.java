package com.tripback.backend.trip.recommendation;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@RestController
@ConditionalOnProperty(name = "TRIPBACK_ENABLE_AI_RECOMMENDATION_API", havingValue = "true", matchIfMissing = false)
public class TripRecommendationController {
    private final TripRecommendationService service;
    private final ObjectMapper mapper;
    public TripRecommendationController(TripRecommendationService service, ObjectMapper mapper) { this.service = service; this.mapper = mapper; }
    @PostMapping(value = "/api/v1/trips/recommend", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> recommend(HttpServletRequest request) throws IOException {
        if (!Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(request.getRemoteAddr())) {
            return ResponseEntity.status(403).body(new ApiError("LOCAL_DEVELOPMENT_ONLY"));
        }
        try {
            var charset = MediaType.parseMediaType(request.getContentType()).getCharset();
            if (charset != null && !charset.equals(StandardCharsets.UTF_8)) return ResponseEntity.status(415).body(new ApiError("UNSUPPORTED_CHARSET"));
        } catch (IllegalArgumentException invalid) { return ResponseEntity.status(415).body(new ApiError("UNSUPPORTED_CHARSET")); }
        if (request.getContentLengthLong() > RecommendationRequest.MAX_BYTES) return ResponseEntity.status(413).body(new ApiError("REQUEST_TOO_LARGE"));
        byte[] body = request.getInputStream().readNBytes(RecommendationRequest.MAX_BYTES + 1);
        if (body.length > RecommendationRequest.MAX_BYTES) return ResponseEntity.status(413).body(new ApiError("REQUEST_TOO_LARGE"));
        var input = RecommendationRequest.decode(body, mapper);
        byte[] response = mapper.writeValueAsBytes(service.recommend(input));
        if (response.length > 262_144) return ResponseEntity.status(502).body(new ApiError("RESPONSE_SIZE_LIMIT"));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
    }
    @ExceptionHandler({RecommendationRequest.BadRequest.class, IOException.class})
    public ResponseEntity<ApiError> invalid() { return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST")); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> failed() { return ResponseEntity.internalServerError().body(new ApiError("INTERNAL_ERROR")); }
    public record ApiError(String code) {}
}
