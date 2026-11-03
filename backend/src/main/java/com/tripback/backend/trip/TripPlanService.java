package com.tripback.backend.trip;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.tripback.backend.trip.TripPlanResponse.Alternative;
import com.tripback.backend.trip.TripPlanResponse.Stop;

@Service
public class TripPlanService {
    private static final String DEMO = "DEMO_SIMULATED";

    public TripPlanResponse plan(TripPlanRequest request) {
        OffsetDateTime depart = parseTime(request.departAt());
        OffsetDateTime deadline = parseTime(request.returnDeadline());
        if (Duration.between(depart.toInstant(), deadline.toInstant()).compareTo(Duration.ofHours(2)) <= 0) {
            throw badRequest("returnDeadline must be more than two hours after departAt");
        }
        if (!Set.of(15, 30, 45).contains(request.safetyBufferMin().intValueExact())) {
            throw badRequest("safetyBufferMin must be 15, 30 or 45");
        }
        if (request.companions().type() == TripPlanRequest.CompanionType.FAMILY
                && request.companions().childAgeGroup() == null) {
            throw badRequest("childAgeGroup is required for FAMILY");
        }
        // Only input syntax/ranges were validated. Origin, places, routes and timing remain unverified.
        return new TripPlanResponse("demo-" + UUID.randomUUID(), DEMO, "NOT_ROUTE_TIME_VALIDATED",
                "고정 시연 코스이며 AI 최적화 결과가 아닙니다. 서버 경로·시간 검증 전입니다. 실제 내비게이션에 사용할 수 없습니다.",
                List.of(
                    alternative("demo-relaxed", "여유형", "방문 수를 줄인 고정 시연 예시입니다. 시간 여유가 검증된 코스는 아닙니다.", List.of(
                        stop("relaxed-1", "시연 체험 공간", "체험", 60, "가상의 체험 장소에서 머무는 일정 예시"),
                        stop("relaxed-2", "시연 식사 공간", "식사", 60, "가상의 식사 장소에서 쉬어가는 일정 예시"),
                        stop("relaxed-3", "시연 휴식 공간", "휴식", 45, "가상의 휴식 장소에서 마무리하는 일정 예시")
                    )),
                    alternative("demo-full", "알찬형", "활동 종류를 늘린 고정 시연 예시입니다. 입력 조건 충족 여부는 검증하지 않았습니다.", List.of(
                        stop("full-1", "시연 산책 공간", "산책·자연", 40, "가상의 산책 장소를 둘러보는 일정 예시"),
                        stop("full-2", "시연 문화 공간", "전시·문화", 50, "가상의 문화 장소를 방문하는 일정 예시"),
                        stop("full-3", "시연 식사 공간", "식사", 50, "가상의 식사 장소를 방문하는 일정 예시"),
                        stop("full-4", "시연 체험 공간", "체험", 60, "가상의 체험 장소를 방문하는 일정 예시"),
                        stop("full-5", "시연 카페 공간", "카페", 30, "가상의 카페 장소에서 마무리하는 일정 예시")
                    ))
                ));
    }

    private OffsetDateTime parseTime(String value) {
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException error) {
            throw badRequest("Timestamps must be valid ISO 8601 dates and times with an explicit UTC offset");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private Alternative alternative(String id, String title, String description, List<Stop> stops) {
        return new Alternative(id, title, description, DEMO, stops, null, null, null, null);
    }

    private Stop stop(String id, String name, String category, int minutes, String description) {
        return new Stop(id, name, category, minutes, description, DEMO);
    }
}