package com.tripback.backend.trip.replan;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Set;

@RestController
@ConditionalOnProperty(name = "TRIPBACK_ENABLE_REPLAN_PREVIEW_API", havingValue = "true", matchIfMissing = false)
public final class ReplanPreviewController {
    private final ReplanPlanStore store;
    private final ObjectMapper mapper;
    private final StayReplanService engine = new StayReplanService();
    public ReplanPreviewController(ReplanPlanStore store, ObjectMapper mapper) { this.store = store; this.mapper = mapper; }
    public static boolean isLoopback(String address) {
        return Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(address);
    }
    @PostMapping(value = "/api/v1/trips/replan-preview", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> preview(HttpServletRequest request) throws IOException {
        if (!isLoopback(request.getRemoteAddr())) return error(403, "LOCAL_DEVELOPMENT_ONLY");
        var charset = MediaType.parseMediaType(request.getContentType()).getCharset();
        if (charset != null && !charset.equals(StandardCharsets.UTF_8)) return error(415, "UNSUPPORTED_CHARSET");
        if (request.getContentLengthLong() > ReplanPreviewRequest.MAX_BYTES) return error(413, "REQUEST_TOO_LARGE");
        byte[] body = request.getInputStream().readNBytes(ReplanPreviewRequest.MAX_BYTES + 1);
        if (body.length > ReplanPreviewRequest.MAX_BYTES) return error(413, "REQUEST_TOO_LARGE");
        var input = ReplanPreviewRequest.decode(body, mapper);
        var entry = store.find(input.planRef());
        if (entry == null) return error(404, "PLAN_NOT_FOUND_OR_EXPIRED");
        if (input.revision() != entry.handle().revision()) return error(409, "PLAN_REVISION_MISMATCH");
        if (input.currentVisitIndex() >= entry.plan().visits.size()) return error(400, "INVALID_CURRENT_VISIT");
        Instant now = store.now(), observed = input.observedAt().toInstant();
        // A claimed old clock must not make aged cached ETA appear fresh. Neither check verifies physical progress.
        Duration lag = Duration.between(observed, now);
        if (lag.isNegative() || lag.compareTo(Duration.ofSeconds(60)) > 0) return error(400, "INVALID_OBSERVATION_TIME");
        if (observed.isBefore(entry.originalDeparture()) || Duration.between(entry.originalDeparture(), observed)
                .compareTo(Duration.ofSeconds(input.elapsedStaySeconds())) < 0) return error(400, "INVALID_STAY_OBSERVATION");
        if (Math.multiplyExact(input.requestedTotalStayMinutes(), 60) < input.elapsedStaySeconds())
            return error(400, "TOTAL_STAY_BELOW_ELAPSED");
        // Anchor to server receipt time and age the observation; do not count request latency twice or refresh ETA.
        long elapsedAtCalculation = Math.addExact(input.elapsedStaySeconds(), lag.getSeconds());
        if (Math.multiplyExact(input.requestedTotalStayMinutes(), 60) < elapsedAtCalculation)
            return error(400, "TOTAL_STAY_BELOW_ELAPSED");
        var result = engine.preview(new StayReplanInput(entry.plan(), input.currentVisitIndex(), now.atOffset(input.observedAt().getOffset()),
                elapsedAtCalculation, input.requestedTotalStayMinutes()));
        if (result.changeStatus() == StayReplanResult.ChangeStatus.CALCULATION_OUT_OF_RANGE)
            return error(400, "CALCULATION_OUT_OF_RANGE");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ReplanPreviewResponse.from(entry, input, result, now));
    }
    @ExceptionHandler({ReplanPreviewRequest.BadRequest.class, IOException.class, IllegalArgumentException.class})
    public ResponseEntity<?> invalid() { return error(400, "INVALID_REQUEST"); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> failed() { return error(500, "INTERNAL_ERROR"); }
    private static ResponseEntity<?> error(int status, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ApiError(code));
    }
    public record ApiError(String code) {}
}
