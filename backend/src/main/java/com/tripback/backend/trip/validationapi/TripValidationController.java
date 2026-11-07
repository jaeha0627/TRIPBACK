package com.tripback.backend.trip.validationapi;

import com.tripback.backend.trip.integration.TripValidationService;
import com.tripback.backend.trip.replan.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Autowired;
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
    private ReplanPlanStore replanPlans;
    @Autowired(required = false)
    public void setReplanPlans(ReplanPlanStore replanPlans) { this.replanPlans = replanPlans; }
    public TripValidationController(TripValidationService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping(value = "/api/v1/trips/validate", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public TripValidationResponse validate(@RequestBody(required = false) byte[] body, HttpServletRequest request, HttpServletResponse response) {
        var input = TripValidationRequest.decode(body, mapper).toInput();
        boolean register = "true".equals(request.getHeader("X-Tripback-Register-Replan"));
        if (register && replanPlans != null && !ReplanPreviewController.isLoopback(request.getRemoteAddr())) {
            response.setStatus(403);
            throw new LocalRegistrationOnly();
        }
        var result = service.validate(input);
        if (register) {
            response.setHeader("Cache-Control", "no-store");
            if (replanPlans == null) response.setHeader("X-Tripback-Plan-Registration", "DISABLED");
            else {
                var registration = replanPlans.registerServerValidation(result);
                response.setHeader("X-Tripback-Plan-Registration", registration.status());
                if (registration.handle() != null) {
                    response.setHeader("X-Tripback-Plan-Ref", registration.handle().planRef());
                    response.setHeader("X-Tripback-Plan-Revision", Long.toString(registration.handle().revision()));
                    response.setHeader("X-Tripback-Plan-Expires-At", registration.handle().expiresAt().toString());
                }
            }
        }
        return TripValidationResponse.from(result);
    }

    private static final class LocalRegistrationOnly extends RuntimeException {
        LocalRegistrationOnly() { super(null, null, false, false); }
    }
    @ExceptionHandler(LocalRegistrationOnly.class)
    public ResponseEntity<ApiError> localOnly() {
        return ResponseEntity.status(403).body(new ApiError("LOCAL_DEVELOPMENT_ONLY"));
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
