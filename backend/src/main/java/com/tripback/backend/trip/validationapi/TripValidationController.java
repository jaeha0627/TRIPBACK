package com.tripback.backend.trip.validationapi;

import com.tripback.backend.trip.integration.TripValidationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** Development API. Keep private until authentication and rate limits are supplied by deployment. */
@RestController
@ConditionalOnProperty(name = "TRIPBACK_ENABLE_TRIP_VALIDATION_API", havingValue = "true", matchIfMissing = false)
public class TripValidationController {
    private final TripValidationService service;
    private final ObjectMapper mapper;
    public TripValidationController(TripValidationService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping(value = "/api/v1/trips/validate", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public TripValidationResponse validate(@RequestBody(required = false) byte[] body) {
        var input = TripValidationRequest.decode(body, mapper).toInput();
        return TripValidationResponse.from(service.validate(input));
    }

    @ExceptionHandler(TripValidationRequest.InvalidRequest.class)
    public ResponseEntity<ApiError> invalidRequest() {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> internalFailure() {
        // Deliberately do not serialize or log exception text, request bodies or authentication material.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError("INTERNAL_ERROR"));
    }
    public record ApiError(String code) {}
}
