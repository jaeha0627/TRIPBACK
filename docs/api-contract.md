# Tripback 앱↔서버 API 계약 (v0.3, 선택 활성화 검증 API)

현재 구현: `GET /api/v1/health`, DEMO 전용 `POST /api/v1/trips/plan`, 기본 비활성 `POST /api/v1/trips/validate`, 기존 `GET /actuator/health`.
장소 검색·현재 자동차 ETA·귀가시간 계산을 연결한 별도 검증 API를 제공합니다. Android 연결, AI, 저장 및 재계획은 미구현입니다. 기존 plan은 실제 장소·경로 검증을 수행하지 않습니다.

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

> replan/approve 및 위 향후 오류 정책은 제안입니다. health·DEMO 전용 plan과 아래 선택 활성화 validate API를 구현했습니다. Android·AI·Pleos SDK 연결은 미구현입니다.

## POST /api/v1/trips/validate — 기본 비활성 개발용 검증 API

기존 `/trips/plan` 계약은 변경하지 않습니다. 새 API는 내부 TripValidationService를 호출하는 별도 경로이며 Controller에서 카카오 HTTP 호출이나 귀가시간 계산을 재구현하지 않습니다.

### 활성화와 운영 범위

- 서버 시작 환경에 `TRIPBACK_ENABLE_TRIP_VALIDATION_API=true`를 설정한 경우에만 Controller가 등록됩니다. 기본/false에서는 `/api/v1/trips/validate`가 등록되지 않아 HTTP 404입니다. 서버 재시작이 필요합니다.
- Spring 조건부 속성으로 평가하며 자동 테스트에서는 해당 속성을 테스트 컨텍스트에만 설정합니다. 운영 키는 기존 Adapter가 서버 환경변수 `TRIPBACK_KAKAO_LOCAL_REST_API_KEY`, `TRIPBACK_KAKAO_MOBILITY_REST_API_KEY`에서만 읽습니다. 키를 JSON·설정 파일·Android·로그에 넣지 않습니다.
- 요청당 장소 검색 최대 5회, 경로 조회 최대 6회입니다. 장소가 하나라도 확인되지 않으면 모든 경로 조회를 보류합니다. 서버 시작 시 외부 호출이 없고 자동 재시도도 없습니다.
- 개발용 활성화 스위치는 인증·호출량 제한을 제공하지 않습니다. 인증과 rate limiting이 없는 공개 배포는 금지합니다. 로컬/비공개 네트워크에서만 사용하고 공개 노출 전에는 별도 인증·권한·호출량 제한을 구현해야 합니다.
- Android·NaviHelper 연결은 이번 범위에 포함하지 않습니다. 카카오 ID를 Pleos POI ID로 취급하거나 변환하지 않습니다.

로컬 PowerShell 활성화 예시 (키 값 입력/출력 예시는 제공하지 않음):

```powershell
$env:TRIPBACK_ENABLE_TRIP_VALIDATION_API = 'true'
.\gradlew.bat bootRun
```

### 요청

`Content-Type: application/json`. 아래 `선택한_카카오_ID`는 실제 검색에서 사용자가 선택한 ID로 바꾸는 자리표시자이며 실장소 검증 증거가 아닙니다.

```json
{
  "origin": {"latitude": 37.1, "longitude": 127.1},
  "departAt": "2026-11-07T08:00:00+09:00",
  "returnDeadline": "2026-11-07T18:00:00+09:00",
  "safetyBufferMin": 30,
  "visits": [
    {
      "keyword": "사용자가 선택한 장소 검색어",
      "selectedId": {"provider": "KAKAO_LOCAL", "value": "선택한_카카오_ID"},
      "stayMinutes": 60
    }
  ]
}
```

| 필드 | 검증 |
|---|---|
| origin | 필수 객체, 숫자 latitude -90~90 / longitude -180~180, 비유한 숫자 불가 |
| departAt / returnDeadline | 필수 문자열, ISO 8601 및 UTC 오프셋 필수, 마감은 실제 시점으로 출발보다 뒤여야 함. 동일 시점 불가 |
| safetyBufferMin | 필수 JSON 정수 15 / 30 / 45 |
| visits | 필수 배열 1~5개, 입력 순서 유지, null 원소 불가 |
| visits[].keyword | 필수 문자열, 공백 불가, 최대 200자 |
| visits[].selectedId | 필수 객체, provider는 KAKAO_LOCAL, value는 공백 없는 ID 문자열(최대 200자), 중복 ID 거부 |
| visits[].stayMinutes | 필수 JSON 정수 0 이상, long 및 엔진 시간 범위 이내 |

- 위 모든 필드는 누락/null을 거부합니다. 숫자 문자열, 소수/지수 표기로 표현한 정수 시간(예: 30.0), 잘못된 타입, 중복 JSON 키와 후행 JSON을 거부합니다.
- 미지원 필드는 모든 객체 수준에서 거부합니다. 예산·필수 방문 제약·차종·임의 데이터 모드 등을 조용히 무시하지 않습니다. 요청 JSON 처리 한도는 64KiB이며 초과 시 400입니다. 이는 프록시/서버의 전송 크기 제한을 대체하지 않습니다.
- ID는 문자열 원문으로 취급하며 숫자로 바꾸지 않습니다. 잘못된 좌표와 중복 ID, 체류 합/입력 체류만으로 발생하는 시간 오버플로는 내부 서비스 호출 전에 거부합니다. 미래 날짜를 넣는 것은 미래 교통 검증을 뜻하지 않습니다.
- 검색은 첫 페이지 최대 15개 후보에서 정확한 ID 일치를 확인합니다. 해당 페이지에 없으면 SELECTED_PLACE_NOT_FOUND이며 다른 장소로 대체하거나 방문지를 삭제하지 않습니다.

### 응답

입력이 유효하면 FEASIBLE / INFEASIBLE / UNKNOWN 모두 HTTP 200입니다. Provider 인증/연결 오류, 미설정, 경로 없음, ETA 누락은 내부 통합 서비스의 정상 UNKNOWN 결과이며 HTTP 500과 구별합니다.

| 필드 | 의미 |
|---|---|
| feasibility | 제공된 ETA를 기준으로 한 수학적 FEASIBLE / INFEASIBLE / UNKNOWN |
| dataCoverage | INCOMPLETE / CONTAINS_DEMO / ALL_PROVIDER_DATA_DECLARED_VALIDATED |
| dataModes | 장소·경로의 원래 출처 집합. VERIFIED_LIVE / VERIFIED_CACHED / DEMO_SIMULATED, 정렬된 배열 |
| visits | 모든 방문지를 index 순서로 보존. requestedId, stayMinutes, confirmedPlace, identityStatus, visitStatus, searchStatus, attemptedAt, reason, failure |
| confirmedPlace | 확인된 id, name, address, coordinates, data. 미확인 시 null |
| identityStatus / visitStatus | MATCHED_SELECTED_ID 또는 UNCONFIRMED / 항상 NOT_VERIFIED. ID 일치가 영업·방문 가능 검증을 뜻하지 않음 |
| legs | 귀가 포함 N+1개. index, 요청 origin/destination, durationSeconds, 원래 Provider status, data, attemptedAt, reason, failure |
| data | provider, dataMode, verification, retrievedAt만 명시적으로 매핑 |
| failure | 안전한 code와 httpStatus만 반환. 원본 본문·헤더·예외 메시지 없음 |
| totalTravelMinutes / totalStayMinutes | 엔진 총 이동 분(구간별 초→분 올림) / 총 체류 분 |
| predictedReturnAt | 엔진 예상 귀가 시각 ISO 8601 문자열 또는 null |
| remainingMarginMin | 마감 대비 남은 분, 부족하면 음수. UNKNOWN은 null |
| returnDeadlineMet / safetyBufferSatisfied | 엔진 마감/안전 여유 충족 판정 또는 null |
| missingEtaLegIndexes | 계산용 ETA 누락 index 배열. 계산 자체 오버플로 시 null |
| routeDataStatus / unknownReasons | 기존 엔진의 경로 데이터 상태 및 미확인 사유 |
| calculationFailure | 정상 계산에서는 null. 총 시간/시각 오버플로는 CALCULATION_OVERFLOW |
| notice | 수학적 계산 및 현재 출발 ETA 한계, 귀가 보장 아님, 출발지/방문 가능성 미검증 고지 |

- 장소 확인 실패 시 방문지와 N+1개 구간을 그대로 유지합니다. query를 구성할 수 없는 구간은 origin/destination=null, status=NOT_VALIDATED, reason=PLACES_UNRESOLVED입니다.
- Provider가 응답한 status와 통합 실패 reason은 별도입니다. Provider가 FOUND라도 요청 좌표 불일치나 시간 오버플로로 거부된 ETA는 durationSeconds/data=null입니다. status만 보고 사용할 수 있는 ETA라고 판단하지 않습니다.
- ETA 부족 UNKNOWN은 예상 시각·총 이동시간·남은 여유·충족 여부가 null입니다. 계산 자체 오버플로는 총 시간·시각·ETA 누락 index가 null이고 calculationFailure가 원인을 설명합니다. 실패 index 및 원래 장소·구간 목록은 유지합니다.
- ALL_PROVIDER_DATA_DECLARED_VALIDATED는 모든 데이터가 실시간 LIVE라는 뜻이 아닙니다. CACHED가 포함될 수 있으므로 dataModes 및 각 data를 함께 확인해야 합니다. 엔진 routeDataStatus는 경로만의 상태이며 전체 장소/일정 상태를 대체하지 않습니다.
- FEASIBLE은 실제 귀가 보장이 아닙니다. 현재 출발 기준 ETA를 미래 날짜의 교통으로 해석하지 않습니다. 출발지 정체성·영업·예약·주차·차량 접근성·미래 교통은 검증하지 않습니다.

키가 설정되지 않은 경우의 응답 예시입니다. 시각과 ID는 설명용이며 실제 API 호출 결과가 아닙니다.

```json
{
  "feasibility": "UNKNOWN",
  "dataCoverage": "INCOMPLETE",
  "dataModes": [],
  "visits": [{
    "index": 0,
    "requestedId": {"provider": "KAKAO_LOCAL", "value": "선택한_카카오_ID"},
    "stayMinutes": 60,
    "confirmedPlace": null,
    "identityStatus": "UNCONFIRMED",
    "visitStatus": "NOT_VERIFIED",
    "searchStatus": "UNAVAILABLE",
    "attemptedAt": "2026-11-03T00:00:00Z",
    "reason": "PLACE_SEARCH_UNAVAILABLE",
    "failure": {"code": "NOT_CONFIGURED", "httpStatus": null}
  }],
  "legs": [
    {"index": 0, "origin": null, "destination": null, "durationSeconds": null, "status": "NOT_VALIDATED", "data": null, "attemptedAt": null, "reason": "PLACES_UNRESOLVED", "failure": null},
    {"index": 1, "origin": null, "destination": null, "durationSeconds": null, "status": "NOT_VALIDATED", "data": null, "attemptedAt": null, "reason": "PLACES_UNRESOLVED", "failure": null}
  ],
  "totalTravelMinutes": null,
  "totalStayMinutes": 60,
  "predictedReturnAt": null,
  "remainingMarginMin": null,
  "returnDeadlineMet": null,
  "safetyBufferSatisfied": null,
  "missingEtaLegIndexes": [0, 1],
  "routeDataStatus": "INCOMPLETE",
  "unknownReasons": ["MISSING_ETA"],
  "calculationFailure": null,
  "notice": "MATHEMATICAL_RESULT_ONLY; CURRENT_DEPARTURE_ETAS_NOT_FUTURE_TRAFFIC; RETURN_NOT_GUARANTEED; ORIGIN_COORDINATES_ONLY; VISITABILITY_NOT_VERIFIED"
}
```

### HTTP 오류와 검증 범위

- 400: 잘못된 입력. 응답은 `{"code":"INVALID_REQUEST"}`이며 입력/파서/도메인 예외 원문을 반환하거나 로그에 남기지 않습니다. 외부 Provider 호출 없음.
- 404: 기본 비활성 상태에서 엔드포인트 미등록.
- 415: JSON Content-Type 미사용 (Spring MVC 기본 처리).
- 500: 예상하지 못한 서버 내부 예외. `{"code":"INTERNAL_ERROR"}`만 반환하며 Provider UNKNOWN과 구분합니다.
- 자동 테스트는 Fake Provider 및 로컬 HTTP 서버를 사용합니다. 가상 장소/ETA는 DEMO로 유지하고, 혼합 출처 HTTP 매핑은 별도 메타데이터 전용 mock으로 확인합니다. 실제 인증키 및 외부 API 호출은 필요하지 않습니다. 인증·호출량 제한과 실제 Live 왕복 검증은 구현/검증 범위에 포함하지 않습니다.

### 검증 API 자동 검증 결과 (2026-11-03)

- backend에서 Java 25.0.3으로 `.\gradlew.bat test bootJar` 성공, 실행 JAR 생성 확인.
- 신규 31건: Fake Provider REST 27건, 기본 비활성 1건, 내부 예외/출처 매핑 안전성 3건 모두 성공.
- 전체 164건: 성공 162, 실패 0, 오류 0, SKIPPED 2. 기존 DEMO API 회귀 테스트 통과.
- 장소·경로 Live 테스트는 활성화 플래그와 인증키를 빌드 프로세스에서 제거해 SKIPPED 처리했습니다. 실제 외부 카카오 API 호출 없음.
- `git diff --check` 및 신규 파일 공백 검사 통과. 실제 인증 호출·공개 배포·Android/실차 통합은 검증하지 않았습니다.
