# Tripback 앱↔서버 API 계약 초안 (v0.1)

> **제안 규격**: 아직 Spring Boot 또는 Android 구현과 호환 확인되지 않았으며 두 개발자의 합의 후 확정합니다.

## 기본 원칙
- JSON / UTF-8 / HTTPS
- 시간: ISO 8601 (예: `2026-11-07T08:30:00+09:00`)
- 모든 좌표: WGS84, latitude/longitude 별도 필드
- `dataMode`: `VERIFIED_LIVE` / `VERIFIED_CACHED` / `DEMO_SIMULATED`
- LLM이 생성한 장소는 실제 장소 식별자/좌표를 서버에서 검증한 후에만 내비로 전달
- 네비 재설정은 사용자 승인 후 실행. 정확한 차량 상태는 사용 가능한 SDK만 사용.

## GET /api/v1/health

응답 예시:
```json
{"status":"UP","apiVersion":"v1","dataMode":"DEMO_SIMULATED"}
```

## POST /api/v1/trips/plan

요청 예시:
```json
{
  "origin":{"placeId":"origin-example","name":"출발지(시연)","latitude":36.82,"longitude":127.15},
  "departAt":"2026-11-07T08:30:00+09:00",
  "returnDeadline":"2026-11-07T18:30:00+09:00",
  "safetyBufferMin":30,
  "region":"서해안",
  "companions":{"type":"FAMILY","childAgeGroup":"ELEMENTARY"},
  "vehicleType":"EV",
  "interests":["EXPERIENCE","FOOD","CAFE"],
  "maximumOneWayMinutes":120,
  "mustVisitPlaceIds":[],
  "constraints":{"walkingPreference":"LOW","budgetKrw":100000}
}
```

응답 예시 (좌표·예상시간은 **시연 전용 가상 데이터**):
```json
{
  "tripId":"demo-trip-001",
  "dataMode":"DEMO_SIMULATED",
  "alternatives":[{
    "planId":"relaxed",
    "title":"여유형",
    "stops":[{
      "stopId":"demo-stop-01",
      "placeId":"demo-place-01",
      "name":"시연 체험 장소",
      "latitude":36.83,
      "longitude":127.16,
      "stayMinutes":60,
      "required":false,
      "dataMode":"DEMO_SIMULATED"
    }],
    "predictedReturnAt":"2026-11-07T17:50:00+09:00",
    "remainingMarginMin":40,
    "isFeasible":true
  }]
}
```

`alternatives`는 최대 2개를 목표로 하며, 적합한 코스가 2개보다 적으면 존재하지 않는 데이터를 만들어 채우지 않습니다. 이동시간·귀가시간은 백엔드의 검증 가능한 경로/일정 계산으로 다시 계산합니다.

## POST /api/v1/trips/{tripId}/replan

요청 예시:
```json
{
  "currentPlanId":"relaxed",
  "eventType":"STAY_OVERRUN",
  "eventAt":"2026-11-07T14:10:00+09:00",
  "delayMinutes":25,
  "eventDataMode":"DEMO_SIMULATED"
}
```

응답 예시:
```json
{
  "proposalId":"proposal-001",
  "requiresApproval":true,
  "reasonCodes":["RETURN_BUFFER_RISK"],
  "changes":[{"stopId":"demo-stop-01","action":"REDUCE_STAY","newStayMinutes":35}],
  "predictedReturnAt":"2026-11-07T18:00:00+09:00",
  "remainingMarginMin":30,
  "isFeasible":true,
  "dataMode":"DEMO_SIMULATED"
}
```

## POST /api/v1/trips/{tripId}/approve

요청 예시: `{"proposalId":"proposal-001","approved":true}`

서버는 승인 전에는 적용하지 않고, 승인된 경로/일정만 반환합니다.

## 오류 처리

| 상황 | 권장 대응 |
|---|---|
| LLM JSON 구조 불일치 | 502 / 마지막 검증된 일정 유지 |
| 실제 POI 식별 불가 | 422 / 해당 후보 제외 후 재추천 |
| 귀가시간 달성 불가 | 422 또는 명시적인 `isFeasible:false` 응답 |
| 서버 통신 실패 | 캐시 및 DEMO 모드 표시, 실제 경로 안내 성공으로 표기 금지 |
| SDK 미지원 | 앱에서 수동 도착·출발 흐름으로 대체 |

> 예시는 계약 초안이며 실제 API 서버, AI 응답, Pleos 차량 SDK와 통합·테스트되기 전에는 동작 보증이 없습니다.
