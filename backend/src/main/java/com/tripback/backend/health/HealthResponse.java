package com.tripback.backend.health;

public record HealthResponse(String status, String apiVersion, String dataMode) {
}