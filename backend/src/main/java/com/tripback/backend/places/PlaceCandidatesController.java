package com.tripback.backend.places;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Private development endpoint. Authentication and rate limiting are required before public deployment. */
@RestController
@ConditionalOnProperty(name = "TRIPBACK_ENABLE_PLACE_SEARCH_API", havingValue = "true", matchIfMissing = false)
public class PlaceCandidatesController {
    private final PlaceCandidatesService service;
    private final ObjectMapper mapper;
    public PlaceCandidatesController(PlaceCandidatesService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping(value = "/api/v1/places/search", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> search(HttpServletRequest request) throws IOException {
        boolean unsupported;
        try {
            MediaType type = MediaType.parseMediaType(request.getContentType());
            unsupported = type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset());
        } catch (IllegalArgumentException invalidCharset) { unsupported = true; }
        if (unsupported) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(new ApiError("UNSUPPORTED_CHARSET"));
        }
        if (request.getContentLengthLong() > PlaceCandidatesRequest.MAX_BODY_BYTES) throw new PlaceCandidatesRequest.BodyTooLarge();
        // Enforce the actual byte bound for both declared-size and chunked requests before parsing.
        byte[] body = request.getInputStream().readNBytes(PlaceCandidatesRequest.MAX_BODY_BYTES + 1);
        return ResponseEntity.ok(service.search(PlaceCandidatesRequest.decode(body, mapper)));
    }

    @ExceptionHandler(PlaceCandidatesRequest.InvalidRequest.class)
    public ResponseEntity<ApiError> invalid() { return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST")); }
    @ExceptionHandler(PlaceCandidatesRequest.BodyTooLarge.class)
    public ResponseEntity<ApiError> tooLarge() { return ResponseEntity.status(413).body(new ApiError("REQUEST_TOO_LARGE")); }
    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiError> unreadableBody() { return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST")); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> internalFailure() {
        return ResponseEntity.internalServerError().body(new ApiError("INTERNAL_ERROR"));
    }
    public record ApiError(String code) {}
}
