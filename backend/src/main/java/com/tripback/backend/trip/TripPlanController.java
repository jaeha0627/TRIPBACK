package com.tripback.backend.trip;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TripPlanController {
    private final TripPlanService service;

    public TripPlanController(TripPlanService service) {
        this.service = service;
    }

    @PostMapping(value = "/api/v1/trips/plan", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public TripPlanResponse plan(@Valid @RequestBody TripPlanRequest request) {
        return service.plan(request);
    }
}