package com.tripback.backend.trip.provider;

import java.time.Duration;

/** Safe structured errors: deliberately no raw headers, URLs, API keys or upstream response bodies. */
public record ProviderFailure(Code code, Integer httpStatus, Duration retryAfter) {
    public ProviderFailure {
        if (code == null || (httpStatus != null && (httpStatus < 400 || httpStatus > 599))
                || (retryAfter != null && retryAfter.isNegative())) {
            throw new IllegalArgumentException("Invalid provider failure metadata");
        }
        if (httpStatus != null) {
            Code expected = httpStatus == 401 || httpStatus == 403 ? Code.AUTHENTICATION_FAILED
                    : httpStatus == 429 ? Code.RATE_LIMITED : Code.HTTP_ERROR;
            if (code != expected) throw new IllegalArgumentException("HTTP status and error code disagree");
        }
    }

    public static ProviderFailure of(Code code) { return new ProviderFailure(code, null, null); }

    public static ProviderFailure fromHttpStatus(int status) {
        Code code = status == 401 || status == 403 ? Code.AUTHENTICATION_FAILED
                : status == 429 ? Code.RATE_LIMITED : Code.HTTP_ERROR;
        return new ProviderFailure(code, status, null);
    }

    public boolean unavailable() { return code == Code.NOT_CONFIGURED || code == Code.ADAPTER_NOT_IMPLEMENTED; }

    public boolean retryable() {
        return code == Code.RATE_LIMITED || code == Code.TIMEOUT || code == Code.CONNECTION_FAILED
                || (code == Code.HTTP_ERROR && httpStatus != null && httpStatus >= 500);
    }

    public enum Code {
        NOT_CONFIGURED, ADAPTER_NOT_IMPLEMENTED, AUTHENTICATION_FAILED, RATE_LIMITED,
        TIMEOUT, CONNECTION_FAILED, HTTP_ERROR, INVALID_DATA, MISSING_ETA
    }
}
