# Tripback 앱↔서버 API 계약 (v0.2, DEMO plan 구현)

현재 구현: `GET /api/v1/health`, `POST /api/v1/trips/plan`, 기존 `GET /actuator/health`.
Android 네트워크 연결, AI, 실제 POI 검색, 경로·시간 검증, 저장 및 재계획은 미구현입니다.

## 기본 원칙
- JSON / UTF-8. 운영 통신은 HTTPS를 전제로 하며 현재 자동 테스트는 로컬 HTTP 임의 포트를 사용합니다.
- 시간은 오프셋을 포함한 ISO 8601 날짜·시간: `2026-11-07T08:30:00+09:00` 또는 UTC `Z`.
- 향후 실제 장소의 좌표는 WGS84의 latitude/longitude 필드로 표현하고 검증 후에만 내비게이션에 전달합니다.
- 데이터 출처: `VERIFIED_LIVE` / `VERIFIED_CACHED` / `DEMO_SIMULATED`. 현재 plan은 DEMO만 제공합니다.
- 요청 입력 검증 통과는 실제 장소·경로·ETA·귀가 마감·안전 여유 검증 통과를 뜻하지 않습니다.

## GET /api/v1/health

HTTP 200, `application/json`:
```json
{"status":"UP","apiVersion":"v1","dataMode":"DEMO_SIMULATED"}
```
기존 Actuator `/actuator/health`도 유지합니다. 이 API는 AI·POI·내비게이션 연결 성공을 보장하지 않습니다.

## POST /api/v1/trips/plan

DEMO 전용입니다. 입력 조건과 무관한 고정 시연 코스 여유형/알찬형 두 개를 반환합니다.
실제 AI 최적화 결과가 아니며, 조건 충족·장소 존재·시간·귀가 가능성은 확인하지 않습니다.

### 요청

`Content-Type: application/json`:
```json
{
  "dataMode":"DEMO_SIMULATED",
  "region":"서해안",
  "departAt":"2026-11-07T08:30:00+09:00",
  "returnDeadline":"2026-11-07T18:30:00+09:00",
  "safetyBufferMin":30,
  "companions":{"type":"FAMILY","childAgeGroup":"ELEMENTARY"},
  "interests":["EXPERIENCE","FOOD","CAFE"],
  "vehicleType":"EV",
  "maximumOneWayMinutes":120
}
```

| 필드 | 현재 검증/의미 |
|---|---|
| region | 필수, 공백 불가, 최대 100자. 실제 지역·장소 존재는 검증하지 않음 |
| departAt / returnDeadline | 필수, 실제 유효한 ISO 8601 날짜·시간과 UTC 오프셋 필요. 날짜/오프셋 없는 시각은 400 |
| 시간 간격 | 두 시각을 실제 시점으로 비교하여 귀가가 출발보다 **2시간 초과** 뒤여야 함. 정확히 2시간, 역전은 400 |
| safetyBufferMin | 필수 정수, 15/30/45만 허용. 소수값은 400 |
| companions.type | 필수: FAMILY / COUPLE / FRIENDS / PARENTS / SOLO |
| companions.childAgeGroup | FAMILY일 때 필수: INFANT / ELEMENTARY / TEEN. 그 외에는 생략 권장 |
| interests | 필수 배열, 빈 배열 허용. 중복은 집합으로 처리. EXPERIENCE / FOOD / NATURE / CAFE / CULTURE / SHOPPING / SPA / PHOTO만 허용, null 원소 불가 |
| vehicleType | 필수: EV / ICE / HEV / LPG / FCEV |
| maximumOneWayMinutes | 필수 정수 1~180분. Android UI 선택지는 60/120/180분. 실제 경로 제한 충족 여부는 미검증 |
| dataMode | 선택, 생략/null이면 DEMO. 제공한다면 DEMO_SIMULATED만 허용. 다른 모드는 400 |
| origin | **현재 DEMO에 한해서** 생략/null 허용. 제공 시 아래 범위 검사만 수행 |
| mustVisitPlaceIds | 미지원. 생략/null/빈 배열만 허용. 필수 방문지를 조용히 누락하지 않도록 비어 있지 않으면 400 |
| constraints | 예산·걷기 등 선택 제약은 현재 미지원. 생략/null/빈 객체만 허용. 값이 있으면 400 |

`origin`을 제공한다면 `name`(공백 불가), `latitude`(-90~90), `longitude`(-180~180)가 필요합니다.
`placeId`는 선택 문자열(최대 200자)입니다. 클라이언트가 전달해도 실제 장소로 검증되거나 응답에 복사되지 않습니다.
**출발지 미입력 또는 단순 좌표 범위 검사를 통과한 입력은 실제 경로·시간 검증용 입력이 아닙니다.**
향후 실제 경로 기능은 별도 확정 계약과 장소/출발지 검증이 필요합니다.

날짜가 명시되므로 다음 날 귀가도 시간 간격 조건을 충족하면 입력으로 허용합니다.
이는 귀가 가능하다는 판정이 아닙니다. 현재 Android 입력 화면은 같은 날 일정만 허용합니다.
Android의 `departTime`/`returnDeadline` 시각 문자열을 연결할 때 여행 날짜와 시간대/오프셋을 명시해야 하며 서버가 임의로 채우지 않습니다.
`companionType`/`childAgeGroup`는 `companions`로 묶고 나머지 기본 필드들은 같은 키로 매핑할 수 있습니다.
Android `budgetPerPersonKrw`와 이전 초안 `constraints.budgetKrw`의 의미·매핑은 미확정이며 현재 전송 대상이 아닙니다.

### 응답

HTTP 200, `application/json`. `tripId`는 요청마다 새 `demo-<UUID>`이며 저장/조회용 리소스는 아직 없습니다.
`planId`와 `stopId`는 화면 구분용 ID로, 실제 placeId/POI ID가 아닙니다.
다음 예시의 모든 장소·체류시간은 시연 데이터이며, 실제 장소 식별자·주소·좌표는 없습니다.

```json
{
  "tripId":"demo-<UUID>",
  "dataMode":"DEMO_SIMULATED",
  "validationStatus":"NOT_ROUTE_TIME_VALIDATED",
  "notice":"고정 시연 코스이며 AI 최적화 결과가 아닙니다. 서버 경로·시간 검증 전입니다. 실제 내비게이션에 사용할 수 없습니다.",
  "alternatives":[
    {
      "planId":"demo-relaxed",
      "title":"여유형",
      "description":"방문 수를 줄인 고정 시연 예시입니다. 시간 여유가 검증된 코스는 아닙니다.",
      "dataMode":"DEMO_SIMULATED",
      "stops":[
        {"stopId":"relaxed-1","name":"시연 체험 공간","category":"체험","stayMinutes":60,"description":"가상의 체험 장소에서 머무는 일정 예시","dataMode":"DEMO_SIMULATED"},
        {"stopId":"relaxed-2","name":"시연 식사 공간","category":"식사","stayMinutes":60,"description":"가상의 식사 장소에서 쉬어가는 일정 예시","dataMode":"DEMO_SIMULATED"},
        {"stopId":"relaxed-3","name":"시연 휴식 공간","category":"휴식","stayMinutes":45,"description":"가상의 휴식 장소에서 마무리하는 일정 예시","dataMode":"DEMO_SIMULATED"}
      ],
      "predictedReturnAt":null,
      "travelMinutes":null,
      "isFeasible":null,
      "remainingMarginMin":null
    },
    {
      "planId":"demo-full",
      "title":"알찬형",
      "description":"활동 종류를 늘린 고정 시연 예시입니다. 입력 조건 충족 여부는 검증하지 않았습니다.",
      "dataMode":"DEMO_SIMULATED",
      "stops":[
        {"stopId":"full-1","name":"시연 산책 공간","category":"산책·자연","stayMinutes":40,"description":"가상의 산책 장소를 둘러보는 일정 예시","dataMode":"DEMO_SIMULATED"},
        {"stopId":"full-2","name":"시연 문화 공간","category":"전시·문화","stayMinutes":50,"description":"가상의 문화 장소를 방문하는 일정 예시","dataMode":"DEMO_SIMULATED"},
        {"stopId":"full-3","name":"시연 식사 공간","category":"식사","stayMinutes":50,"description":"가상의 식사 장소를 방문하는 일정 예시","dataMode":"DEMO_SIMULATED"},
        {"stopId":"full-4","name":"시연 체험 공간","category":"체험","stayMinutes":60,"description":"가상의 체험 장소를 방문하는 일정 예시","dataMode":"DEMO_SIMULATED"},
        {"stopId":"full-5","name":"시연 카페 공간","category":"카페","stayMinutes":30,"description":"가상의 카페 장소에서 마무리하는 일정 예시","dataMode":"DEMO_SIMULATED"}
      ],
      "predictedReturnAt":null,
      "travelMinutes":null,
      "isFeasible":null,
      "remainingMarginMin":null
    }
  ]
}
```

`stops`의 배열 순서가 방문 순서입니다. 최종 출발지 귀가 경로는 생성하지 않습니다.
미검증 계산 필드의 `null`은 0분/false/성공을 뜻하지 않습니다. 이 응답을 NaviHelper에 전송하면 안 됩니다.
현재 Java record DTO와 Android 로컬 UI 모델은 별개이며 네트워크 변환 계층은 미구현입니다.

### HTTP 오류
- 400: 필수값 누락/null, 잘못된 JSON·날짜·시간·오프셋·enum, 시간 간격 오류, 범위/정수 오류, 미지원 필수 방문지·제약 조건.
- 잘못된 Content-Type은 415. 오류 응답 본문은 현재 Spring Boot 기본 처리이며 클라이언트용 오류 DTO는 아직 확정하지 않았습니다.

### v0.1 초안 대비 변경
- 출발지 필수/시연 좌표 예시 제거 → DEMO에 한해서 선택 입력. 실경로 검증 가능 입력이라고 주장하지 않음.
- 가상 stops의 placeId/latitude/longitude 제거 → 화면용 stopId와 가상 장소명/구분/설명/시연 체류시간 사용.
- predictedReturnAt/remainingMarginMin/isFeasible 확정 예시 제거 → 모두 null. travelMinutes도 null, 미검증 상태/안내 추가.
- Android 시연 코스와 같은 의미의 여유형 3곳·알찬형 5곳을 고정 반환. 실서비스의 유효 대안 수 정책과는 별도.
- mustVisitPlaceIds와 constraints의 실제 적용은 미구현이므로 값이 있으면 거부. 필수 방문지를 누락한 성공 응답을 만들지 않음.
- 아래 replan/approve는 계속 미구현 제안으로 유지.

## POST /api/v1/trips/{tripId}/replan — 제안, 미구현

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

## POST /api/v1/trips/{tripId}/approve — 제안, 미구현

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

> replan/approve 및 위 향후 오류 정책은 제안입니다. 현재 구현된 API는 health와 DEMO 전용 plan이며, Android·AI·Pleos SDK 통합은 미구현입니다.
