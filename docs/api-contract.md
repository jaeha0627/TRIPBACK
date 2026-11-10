# Tripback 앱↔서버 API 계약 (v0.5, 선택 활성화 AI 왕복 추천)

현재 구현: `GET /api/v1/health`, DEMO 전용 `POST /api/v1/trips/plan`, 기본 비활성 `POST /api/v1/trips/validate`, 기본 비활성 `POST /api/v1/places/search`, 기본 비활성 `POST /api/v1/trips/recommend`, 기존 `GET /actuator/health`.
장소 검색·현재 자동차 ETA·귀가시간 계산을 연결한 별도 검증 API를 제공합니다. 별도 recommend API는 실제 LLM 호출이 가능한 구현이며 실제 카카오+LLM 통합 호출 검증은 별도 단계입니다. 저장·재계획은 미구현이고 기존 plan은 DEMO 계약을 유지합니다.

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

## POST /api/v1/places/search — 기본 비활성 장소 후보 검색 API

기존 PlaceSearchProvider와 KakaoPlaceSearchAdapter를 재사용하는 후보 검색 API입니다. 별도 카카오 HTTP 연동을 만들지 않습니다. 기존 DEMO plan·여행 validate 계약과 내부 계산 규칙은 유지합니다. LLM 호출이나 AI 여행 코스 추천 결과는 제공하지 않습니다.

### 활성화와 호출 범위

- 서버 환경변수 `TRIPBACK_ENABLE_PLACE_SEARCH_API=true`일 때만 Controller를 등록합니다. 기본/false 상태의 경로는 HTTP 404입니다. Spring 조건부 속성으로 평가하며 변경 후 서버 재시작이 필요합니다. 여행 검증 API 활성화 스위치와 독립적입니다.
- 키는 기존 Adapter가 서버 프로세스 환경변수 `TRIPBACK_KAKAO_LOCAL_REST_API_KEY`에서만 읽습니다. 클라이언트 요청에 키를 받지 않으며 코드·앱·문서·로그에 실제 값을 넣지 않습니다.
- 유효 요청당 Provider.search()를 한 번 호출합니다. page=1, size=15로 고정하고 center/radius는 전달하지 않습니다. 자동 재시도나 다음 페이지 조회는 없습니다. 서버 시작 시 키 조회·검색 호출이 없습니다.
- 인증·권한 검사·rate limit을 아직 제공하지 않으므로 외부 공개 배포는 금지합니다. 로컬/비공개 개발 환경에서만 활성화하고 공개 노출 전에 별도로 구현해야 합니다.

```powershell
$env:TRIPBACK_ENABLE_PLACE_SEARCH_API = 'true'
# backend 디렉터리에서 실행. 실제 키 값은 명령/로그에 기록하지 않습니다.
.\gradlew.bat bootRun
```

### 요청

`Content-Type: application/json` 또는 `application/json; charset=UTF-8`:

```json
{"keyword":"독립기념관 주차장"}
```

- keyword는 필수 JSON 문자열입니다. null/빈 문자열/공백만/앞뒤 공백/제어문자/잘못된 유니코드를 거부하며 최대 200자(Java UTF-16 길이)입니다. 한글은 UTF-8로 전달합니다.
- 다른 필드는 허용하지 않습니다. page, size, selectedId, 제약 조건, API 키 등을 추가해도 무시하지 않고 400입니다. 숫자/불리언/배열/객체를 문자열로 강제 변환하지 않습니다.
- 중복 JSON 키·후행 JSON·잘못된 JSON·잘못된 UTF-8을 거부합니다. UTF-8 이외의 명시적 charset은 415입니다.
- 본문 최대 4,096바이트입니다. Content-Length가 크면 읽기 전에 거부하고, 길이가 없거나 청크 전송이어도 최대 4,097바이트까지만 읽어 초과를 판정합니다. 초과는 413이며 Provider를 호출하지 않습니다. 프록시의 연결/본문 수신 시간 제한과 전체 서비스 호출량 제한은 별도 운영 설정입니다.

### 응답

유효한 요청의 Provider 결과는 HTTP 200으로 반환하며 status를 반드시 확인합니다.

| status | 의미 |
|---|---|
| FOUND | 현재 페이지 후보 있음, 최대 15개. 자동 선택한 장소가 아님 |
| NO_RESULTS | 정상 검색했으나 현재 페이지 후보가 없음. 공급자 장애와 구분 |
| UNAVAILABLE | 인증키 미설정 등 Provider 사용 불가 |
| FAILED | 인증/연결/타임아웃/호출 제한/원본 응답 검증 실패 또는 잘못된 Provider 결과 |

| 필드 | 계약 |
|---|---|
| provider | KAKAO_LOCAL |
| page / size | 항상 1 / 15. size는 페이지 상한이며 실제 후보 수는 candidates 길이 |
| candidates | 공급자 순서를 유지한 후보 배열. 실패/빈 결과에서는 빈 배열 |
| candidates[].index | 0부터 시작하는 응답 배열 index. 순위 추천 또는 선택 상태가 아님 |
| candidates[].id | provider와 원문 value. 선행 0을 보존하는 불투명 문자열이며 Pleos POI ID가 아님 |
| candidates[].name / address | 공급자가 반환한 이름/주소. 주소 미제공 시 빈 문자열 유지 |
| candidates[].coordinates | WGS84 latitude / longitude 숫자. 유한값 및 범위 검증 |
| candidates[].data | provider, dataMode, verification, retrievedAt를 그대로 보존 |
| candidates[].visitStatus | 항상 NOT_VERIFIED. 영업·예약·주차·차량 접근성·방문 가능성 미확인 |
| dataModes | 반환 후보의 출처 집합을 정렬한 배열. 빈 결과/실패는 빈 배열 |
| attemptedAt | Provider가 반환한 요청 시각. 예외/잘못된 결과라면 서비스가 실제 조회를 시도한 시각 |
| reason | 정상/빈 결과 null, 미설정 PROVIDER_UNAVAILABLE, Provider FAILED는 PROVIDER_FAILED |
| failure | 안전한 code와 선택 httpStatus만 포함, 정상/빈 결과 null |
| notice | 후보 검색 한계·자동 선택 없음·방문 가능성 미검증·AI 코스 추천 아님 고지 |

- VERIFIED_LIVE / VERIFIED_CACHED / DEMO_SIMULATED를 개별 후보에서 유지합니다. CACHED의 retrievedAt을 갱신하거나 LIVE로 승격하지 않습니다. 최상위에 일정 전체 LIVE나 귀가 가능성 판정을 붙이지 않습니다.
- 중복 후보 ID, 잘못된 namespace/좌표, 15개 초과, null 응답을 INVALID_PROVIDER_RESPONSE / failure.code=INVALID_DATA로 반환합니다. 일부 후보를 조용히 삭제하거나 15개로 잘라서 성공 처리하지 않습니다.
- Provider가 예외를 던지면 FAILED / reason=PROVIDER_EXCEPTION / failure=null입니다. 예외 메시지로 인증·연결 원인을 추측하지 않습니다. Provider가 안전한 ProviderFailure를 반환했다면 AUTHENTICATION_FAILED, CONNECTION_FAILED, TIMEOUT, RATE_LIMITED, HTTP_ERROR, INVALID_DATA 등을 그대로 보존합니다.
- 어떤 실패도 NO_RESULTS나 DEMO 후보로 대체하지 않습니다. 원본 Provider 객체·요청 URL·인증 헤더·오류 본문·예외 메시지를 직렬화하거나 로그로 출력하지 않습니다.

아래는 **설명용 DEMO 후보** 응답입니다. 실제 카카오 API 인증 호출의 증거가 아닙니다.

```json
{
  "provider": "KAKAO_LOCAL",
  "status": "FOUND",
  "page": 1,
  "size": 15,
  "candidates": [{
    "index": 0,
    "id": {"provider": "KAKAO_LOCAL", "value": "fixture-001"},
    "name": "가상 후보",
    "address": "가상 주소",
    "coordinates": {"latitude": 36.75, "longitude": 127.25},
    "data": {
      "provider": "KAKAO_LOCAL",
      "dataMode": "DEMO_SIMULATED",
      "verification": "UNVERIFIED",
      "retrievedAt": "2026-11-04T00:00:00Z"
    },
    "visitStatus": "NOT_VERIFIED"
  }],
  "dataModes": ["DEMO_SIMULATED"],
  "attemptedAt": "2026-11-04T00:00:00Z",
  "reason": null,
  "failure": null,
  "notice": "CANDIDATES_ONLY; NO_AUTOMATIC_SELECTION; VISITABILITY_NOT_VERIFIED; KAKAO_IDS_ARE_NOT_PLEOS_POI_IDS; NOT_AI_ITINERARY_RECOMMENDATIONS"
}
```

키가 없는 경우에는 status=UNAVAILABLE, candidates=[], dataModes=[], reason=PROVIDER_UNAVAILABLE, failure={"code":"NOT_CONFIGURED","httpStatus":null}입니다. NO_RESULTS는 candidates=[], reason=null, failure=null이므로 구분할 수 있습니다.

### HTTP 오류 및 남은 범위

- 400: `{"code":"INVALID_REQUEST"}`, 잘못된 입력/읽기 실패. Provider 호출 없음.
- 404: 기본 비활성 또는 경로 미등록.
- 413: `{"code":"REQUEST_TOO_LARGE"}`, 본문 바이트 상한 초과. Provider 호출 없음.
- 415: 지원하지 않는 Content-Type 또는 charset. Controller의 charset 거부는 `{"code":"UNSUPPORTED_CHARSET"}`이며 매핑 이전 오류는 Spring 기본 처리입니다.
- 500: 예상하지 못한 서버 내부 예외, `{"code":"INTERNAL_ERROR"}`. Provider의 정상 FAILED 결과와 구분합니다.
- 아직 없는 기능: 검색 페이지 확장·지역/반경 필터·선택 확정·캐시 정책·영업/주차 검증·LLM/AI 추천·후보의 여행 코스 자동 연결·운영 인증/호출량 제한. Android/NaviHelper 연결은 이번 작업 범위가 아닙니다.

### 장소 후보 API 자동 검증 결과 (2026-11-04)

- Java 25.0.3에서 backend의 `.\gradlew.bat test bootJar` 성공. 실행 JAR 생성 확인.
- 신규 25건: REST 21건, 기본 비활성 1건, 출처/좌표/예외 안전성 3건 모두 성공.
- 전체 189건: 성공 187, 실패 0, 오류 0, SKIPPED 2. 기존 DEMO·여행 검증 API 회귀 테스트 통과.
- 장소/경로 Live 테스트의 키와 실행 플래그를 빌드 프로세스에서 제거해 SKIPPED 처리했습니다. 실제 카카오 API 호출은 없습니다.
- `git diff --check` 및 신규 파일 공백 검사 통과. 실제 인증 검색·공개 배포·Android 연동은 이번 검증 범위에 포함하지 않습니다.

## POST /api/v1/trips/recommend — localhost 개발용 AI 왕복 추천

실제 장소 후보 검색 → 목표 중심에서 WGS84 타원체 거리 20km 검사 → OpenAI 구조화 제안 → 허용 ID 재검사 → 기존 TripValidationService의 장소 재검색·N+1 도로 구간·귀가 계산 순서입니다. 기존 `/trips/plan`, `/trips/validate`, `/places/search` 계약과 Adapter/계산 규칙은 수정하지 않습니다.

### 활성화 및 요청

`TRIPBACK_ENABLE_AI_RECOMMENDATION_API=true`일 때만 등록합니다. 기본은 404이며 별도 후보 검색/validate API 활성화 스위치를 켤 필요는 없습니다. 요청 원격 주소는 loopback(127.0.0.1 또는 IPv6 loopback)만 허용하며 나머지는 403 LOCAL_DEVELOPMENT_ONLY입니다. 프록시가 loopback으로 연결하면 이 검사는 외부 사용자를 인증하지 못하므로 공개 프록시 배포도 금지합니다. 인증·권한·rate limit은 별도 구현 전까지 제공하지 않습니다.

`Content-Type: application/json; charset=UTF-8`, 최대 16KiB. 길이가 없는 청크 본문도 실제 바이트 수를 제한합니다. 알 수 없는 필드, 중복 JSON 키, 후행 JSON, 잘못된 타입/UTF-8/좌표/날짜·오프셋은 400이며 외부 호출하지 않습니다.

```json
{
  "origin": {"latitude":36.77793445119644,"longitude":127.21911636555244},
  "targetAreaKeyword":"천안 독립기념관",
  "targetCenter":{"latitude":36.7814,"longitude":127.2232},
  "departAt":"2026-11-07T08:00:00+09:00",
  "returnDeadline":"2026-11-07T18:00:00+09:00",
  "safetyBufferMin":30,
  "companionType":"FAMILY",
  "childAgeGroup":"ELEMENTARY",
  "interests":["CULTURE","FOOD","CAFE"],
  "vehicleType":"EV",
  "maximumOneWayMinutes":120,
  "budgetPerPersonKrw":50000,
  "mealPreferences":["KOREAN"],
  "walkingPreference":"LOW",
  "parkingPreferences":["FREE"],
  "amenities":["ACCESSIBLE"],
  "preferIndoorWhenRaining":true
}
```

좌표와 지역 문자열은 사용자 제공 예시이며 실제 출발지·지역 정체성을 인증한 데이터가 아닙니다.

| 필드 | 계약 |
|---|---|
| origin / targetCenter | 필수 WGS84 latitude -90~90, longitude -180~180, JSON 숫자·유한값 |
| targetAreaKeyword | 필수 1~100자, 앞뒤 공백·제어문자 불가. 구체적인 지역을 사용자가 입력하며 지역명과 좌표가 같은 지역인지는 인증하지 않음 |
| departAt / returnDeadline | 필수 ISO 8601 UTC 오프셋 포함, 마감은 출발보다 실제 시점으로 뒤. 미래 날짜 교통 검증을 뜻하지 않음 |
| safetyBufferMin | 필수 JSON 정수 15 / 30 / 45 |
| companionType | 필수 FAMILY / COUPLE / FRIENDS / PARENTS / SOLO |
| interests | 필수 배열, 0~8개 중복 없는 EXPERIENCE / FOOD / NATURE / CAFE / CULTURE / SHOPPING / SPA / PHOTO. 빈 배열은 관광 검색 |
| vehicleType | 필수 EV / ICE / HEV / LPG / FCEV. 현재 경로 Provider에는 차종별 옵션이 없어 선호 힌트일 뿐 차량별 경로·에너지 검증 아님 |
| maximumOneWayMinutes | 필수 정수 1~180. 실제 첫 출발→첫 방문 및 마지막 방문→출발 구간의 구간별 올림 분으로 검사. 모든 방문지의 개별 origin→visit ETA나 전체 편도 순회 합을 검사하는 것은 아님 |
| childAgeGroup | 선택 INFANT / ELEMENTARY / TEEN, FAMILY에는 필수. 개인 나이나 이름은 받지 않음 |
| budgetPerPersonKrw | 선택 정수 0~100,000,000. 가격 데이터가 없어 충족 검증은 하지 않음 |
| mealPreferences | 선택 중복 없는 KOREAN / SEAFOOD / WESTERN / VEGETARIAN / KIDS |
| walkingPreference | 선택 LOW / NORMAL / HIGH |
| parkingPreferences | 선택 중복 없는 FREE / WIDE / NEAR |
| amenities | 선택 중복 없는 ACCESSIBLE / NURSING_PLAYROOM / PET |
| preferIndoorWhenRaining | 선택 JSON boolean. 날씨·실내 여부 검증 아님 |
| mustVisitPlaceIds | 선택 배열 최대 5개, 각 원소 {"provider":"KAKAO_LOCAL","value":"사용자가 선택한 ID"}. 중복 불가. 필터된 후보에 없으면 모델 호출 없이 실패하고 모든 제안에서 누락 여부를 검사 |

선택 필드의 null은 미지정으로 취급합니다. 예산·걷기·식사·주차·편의시설·우천·동행 조건은 모델에 전달하는 힌트이며 실제 충족 여부는 검증하지 않습니다. 추가 제약을 지원한 척하지 않으며 미지원 필드는 400으로 거부합니다.

### 후보 및 모델 처리

- 서버가 지역 문자열에 관심사별 고정 접미사(체험/맛집/자연/카페/관광/쇼핑/온천/명소)를 붙여 최대 8개 검색어를 생성합니다. 각 검색은 page=1, size=15, targetCenter, radius=20000입니다. LLM은 검색어·도구·외부 호출을 실행하지 않습니다.
- 반환 좌표를 WGS84 타원체 Vincenty 거리로 재검사합니다. 20km 초과/거리 비수렴/잘못된 좌표는 제외합니다. ID 기준으로 중복 제거하고 같은 이름·다른 ID는 보존합니다. 동일 ID 좌표가 충돌하면 그 ID를 제외합니다.
- 후보는 최대 30개입니다. 먼저 수집한 후보·순서를 유지하며 한도 도달 시 남은 검색은 NOT_SEARCHED/CANDIDATE_LIMIT로 기록합니다. 전체 후보/검색/제외 사유를 반환하므로 모든 관심사·장소를 조사했다고 주장하지 않습니다.
- 후보의 원래 검색어·ID·이름·주소·좌표·출처·검증 라벨·검색/조회 시각을 보존합니다. 실제 검색 장애는 실패 코드로 기록하고 가상 후보로 대체하지 않습니다.
- 모델 인터페이스는 TripRecommendationModel이며 운영 구현은 OpenAiTripRecommendationModel입니다. 고정 공식 HTTPS `POST https://api.openai.com/v1/responses`를 사용하고, `text.format={type:json_schema,strict:true,...}`의 additionalProperties=false 스키마를 전달합니다. placeId enum은 수집한 후보 ID만 포함합니다.
- JSON 스키마는 plans 배열, 제목, 추천 이유, orderedVisits의 provider/placeId/stayMinutes만 허용합니다. 모델에 새 좌표·ETA·영업·주차·가격 등의 구조화 필드를 만들 권한이 없습니다. 서버는 JSON 구조/새 ID/namespace/중복/필수 방문/1~5방문/정수 체류 0~240분을 다시 검사합니다.
- 모델에는 선호 조건과 선별한 ID·짧은 이름·주소만 보냅니다. 출발/목표 좌표, 카카오/OpenAI 키, 차량 이벤트, 개인 이름/연락처는 전달하지 않습니다. 후보 이름·주소와 사용자 입력은 명령이 아닌 외부 데이터로 구분합니다. 원문 모델 출력은 로그에 남기지 않으며 생성 설명의 연락처/API 키 형태도 거부합니다. 자유 문장의 진실성을 보장하는 기능은 아니므로 설명은 항상 AI_REASON_UNVERIFIED입니다.
- 선택 ID의 원래 검색어를 기존 TripValidationService에 전달해 재검색합니다. 추천 전용 Provider 래퍼에서 최초 targetCenter와 radius=20000을 재검색에도 유지합니다. 선택 후보의 좌표가 최초 후보와 다르거나 목표 반경 밖이면 Provider INVALID_DATA로 거부하여 해당 코스는 UNKNOWN입니다. ID가 누락되면 다른 장소로 대체하지 않습니다. 장소 검증 규칙을 우회하지 않으며 기존 validate API의 검색 동작은 유지합니다.

### 응답

유효 요청의 작업 결과는 HTTP 200이며 recommendationStatus와 aiProviderStatus, 각 코스의 validation을 함께 확인합니다.

| 필드 | 의미 |
|---|---|
| recommendationStatus | COMPLETED(수락 2안), PARTIAL(수락 1안), NO_FEASIBLE_ALTERNATIVES(수락 0안), NO_CANDIDATES, NO_ELIGIBLE_CANDIDATES, AI_UNAVAILABLE, AI_FAILED, INVALID_MODEL_OUTPUT, PROCESSING_TIMEOUT, CANCELLED, BUSY 또는 FAILED |
| aiProviderStatus | provider/status/invocation. OPENAI/COMPLETED/OPENAI_HTTP는 공식 모델 HTTP 시도 및 완료. 이것만으로 코스·이유가 검증됐다는 뜻은 아님 |
| alternatives | 귀가 FEASIBLE 및 첫 출발/최종 귀가 편도 제한을 통과한 안만 반환. 0~2개, 억지로 채우지 않음 |
| rejectedAlternatives | index, 안전한 reason, 선택 proposal. ID·JSON 오류는 proposal=null. INFEASIBLE/UNKNOWN·편도 제한 실패는 상세 proposal/validation을 유지 |
| alternatives[].planId | ai-proposal-1/2, 해당 요청의 임시 제안 구분자이며 저장/승인/내비 POI ID가 아님 |
| title / recommendationReason | AI 생성 설명. 검증된 사실로 취급하지 않음 |
| orderedVisits | 원래 순서의 index, ID, originalKeyword, 제안 체류 분 |
| validation | 기존 TripValidationResponse 명시적 매핑. 장소/구간/출처·예상 시각·마감·안전 여유·실패/누락 index 제공 |
| feasibility / dataModes / notices | 해당 제안의 실제 엔진 판정과 장소/경로 출처, 설명·교통·방문 가능성 한계 |
| candidates / searches / excludedCandidates | 후보 출처와 검색 상태/안전한 failureCode, 제외 ID·사유. 초기 검색 결과이며 재검증 데이터는 validation에 별도 보존 |
| calls | 실제 시작한 Provider 검색/도로 조회 수와 모델 인터페이스 generate 호출 수. 모델 HTTP 비용 청구 횟수의 증거로 해석하지 않음 |
| conditionStatus | 실제 검사 범위와 모델 선호 힌트/미검증 상태 |
| limitations | 사용자 좌표 미인증, 미래 교통/방문 가능성 미검증, 검색·편도 검사 범위 및 공개 제한 |

- rejectedAlternatives.index=-1은 전체 작업/스키마/시간 초과 등 코스 index를 부여할 수 없는 실패입니다. 대표 사유는 UNLISTED_PLACE_ID, DUPLICATE_VISIT, REQUIRED_VISIT_MISSING, INVALID_VISIT_COUNT, INTEGER_OUT_OF_RANGE, VALIDATION_INFEASIBLE, VALIDATION_UNKNOWN, MAXIMUM_ONE_WAY_EXCEEDED, MODEL_RETURNED_NO_ALTERNATIVES입니다. 원본 모델 문자열이나 예외 메시지는 실패 사유로 반환하지 않습니다.
- 모델 거부(REFUSED), 토큰 잘림(INCOMPLETE), 잘못된 envelope(INVALID_RESPONSE), 크기 초과, 인증/호출 제한/타임아웃/연결 오류는 안전한 모델 상태로 반환합니다. FAILED 코스를 다른 장소나 DEMO로 채우지 않습니다.
- invocation=FAKE_MODEL 또는 TEST_HTTP는 단위 테스트 가짜 모델/로컬 모의 OpenAI HTTP이며 실제 OpenAI 성공이 아닙니다. NOT_CALLED는 미호출입니다. REQUESTED/UNCONFIRMED는 모델 인터페이스 호출은 시작했지만 전체 처리시간 초과/예외 등으로 실제 HTTP 결과를 확인하지 못했다는 뜻이며 성공으로 표시하지 않습니다.
- 모든 키/모델 ID가 미설정이면 AI_UNAVAILABLE/NOT_CONFIGURED이며 카카오 검색도 시작하지 않습니다. 빈 후보나 필수 후보 누락은 유료 모델 호출 전에 중단합니다.
- VERIFIED_LIVE/CACHED/DEMO는 공급자 장소·도로 데이터의 출처입니다. AI 문장을 인증하거나 미래 여행 날짜의 교통·실제 귀가를 보장하지 않습니다. CACHED를 LIVE로 승격하지 않습니다.

모델 미설정 상태의 안전한 응답 발췌 예시 (실제 모델 호출 결과 아님):

```json
{
  "recommendationStatus":"AI_UNAVAILABLE",
  "aiProviderStatus":{"provider":"OPENAI","status":"NOT_CONFIGURED","invocation":"NOT_CALLED"},
  "alternatives":[],
  "rejectedAlternatives":[],
  "candidates":[],
  "searches":[],
  "excludedCandidates":[],
  "calls":{"placeSearches":0,"roadRoutes":0,"modelGenerations":0}
}
```

`conditionStatus`와 `limitations`는 이 경우에도 전체 응답에 포함됩니다. 상세 구현·Fake 응답 예시·실행 방법은 [AI 추천 실행 문서](ai-trip-recommendations.md)를 참조하세요.

### 한도·HTTP 상태

- 요청당 초기 검색 최대 8회 + 재검색 최대 10회 = 장소 호출 최대 18회, 도로 조회 최대 12회, 모델 generate 최대 1회. 재시도 없음. 동시 추천 작업 최대 2개이며 추가 요청은 BUSY로 거부합니다. 이 동시성 제한은 rate limit/사용자별 비용 한도가 아닙니다.
- 전체 작업 제한 180초, OpenAI 연결 3초/전체 본문 25초, 모델 응답 envelope 128KiB/출력 JSON 32KiB, 출력 토큰 2048, 모델 요청 본문 64KiB, REST 응답 256KiB입니다. 기존 카카오 Adapter의 각 5초 제한도 유지합니다. 전체 제한 시 worker를 취소하고 이후 Provider 호출을 막습니다. 이미 접수한 유료 모델 요청은 시간 초과/취소 후에도 비용이 발생할 수 있습니다.
- 400 INVALID_REQUEST: 잘못된 입력. 외부 호출 없음. 403 LOCAL_DEVELOPMENT_ONLY: 비-loopback 요청. 404: 기본 비활성. 413 REQUEST_TOO_LARGE: 요청 본문 초과. 415: 잘못된 Content-Type/charset. 502 RESPONSE_SIZE_LIMIT: 응답 직렬화 상한 초과. 예상하지 못한 Controller 예외는 500 INTERNAL_ERROR이며 원문 로그/응답 없음.
- 실제 카카오+OpenAI 통합 검증과 가격·모델 계정 접근 가능 여부는 아직 확인하지 않았습니다. 비용은 선택 모델의 입력/출력 토큰·계정 정책에 따라 달라지며 실행 전 운영자가 확인해야 합니다.

### AI 추천 자동 검증 결과 (2026-11-04)

- backend에서 `.\gradlew.bat test bootJar` 성공. 신규 55건 모두 성공.
- 전체 244건: 성공 242, 실패 0, 오류 0, SKIPPED 2. 기존 API/Provider/계산 엔진 회귀 통과.
- Fake 모델 및 루프백 OpenAI 모의 HTTP만 사용했으며 실제 OpenAI/카카오 API 호출은 없습니다. 기존 Live 테스트 2건은 키/플래그를 제거하여 SKIPPED입니다.
- `git diff --check` 및 신규 파일 공백 검사 통과. 실제 모델 계정/유료 통합 검증은 별도 단계입니다.

## v0.5 추가 초안 — POST /api/v1/trips/replan-preview (개발용 Preview)

기존 v0.5 요청/응답 JSON과 `/plan`, `/validate`, `/recommend` 동작은 유지한다. 아래는 새 선택 활성화 기능이다. 승인 API, DB, 영구 여행 세션, 실제 위치/주행 상태 확정, 내비게이션 전송은 구현하지 않는다.

### 활성화와 신뢰 경계

- `TRIPBACK_ENABLE_REPLAN_PREVIEW_API=true`일 때만 신규 Controller와 임시 저장소를 등록한다. 기본은 404이다. `/validate`에서 참조를 발급하려면 기존 `TRIPBACK_ENABLE_TRIP_VALIDATION_API=true`도 필요하다.
- Preview와 참조 발급은 loopback 요청만 허용한다. 인증/소유권 검증/rate limit은 없다. 참조는 사용자 인증을 대체하지 않으며 운영용으로 안전하다는 의미가 아니다. 공개 프록시로 노출하지 않는다. X-Forwarded-For로 loopback 제한을 우회하지 않는다.
- 서버 내부 `TripValidationResult`의 FEASIBLE, 장소 ID/좌표 연결, 모든 왕복 경로와 검증된 출처를 확인하고 순수 엔진 재계산 결과까지 대조한 경우에만 `ValidatedReplanPlan`을 등록한다. 클라이언트가 ETA/출처/방문 순서를 제출해 등록하는 엔드포인트는 없다.
- 모든 검증은 Provider 데이터 수준이며 실제 방문 가능성·실제 위치·실제 귀가를 인증하지 않는다. 관측시각·체류 경과시간·현재 방문지는 클라이언트 보고 값이며 응답은 `CLIENT_REPORTED_NOT_VERIFIED`를 명시한다.
- 기존 `/recommend`의 `ai-proposal-1`은 저장된 여행 참조가 아니다. 이번 참조 발급 경로는 `/validate`로 한정한다. 추천 결과를 사용할 때에는 원래 귀가 지점과 선택한 방문 순서/ID/체류시간을 기존 `/validate` 계약으로 검증해야 한다. 이 검증은 기존 Provider 호출을 수행할 수 있지만 이후 Preview는 추가 조회를 수행하지 않는다. 추천 필수 방문지 정보가 손실되지 않도록 등록된 모든 방문지를 보존 대상으로 취급한다.

### 서버 소유 참조 발급: 기존 /validate 선택 헤더

기존 검증 요청 JSON에 필드를 추가하지 않고 `X-Tripback-Register-Replan: true` 헤더로 명시적으로 요청한다. 헤더가 없으면 참조를 생성하지 않는다. 기존 검증 응답 JSON은 그대로다.

등록에 성공한 HTTP 200 응답에 다음 헤더가 추가된다.

```http
X-Tripback-Plan-Registration: REGISTERED
X-Tripback-Plan-Ref: <서버가 생성한 43자 base64url 참조>
X-Tripback-Plan-Revision: 1
X-Tripback-Plan-Expires-At: 2026-11-07T01:30:00Z
Cache-Control: no-store
```

- 참조는 SecureRandom 32바이트(256비트)이며 순차 ID나 클라이언트 제공 ID가 아니다. 로그/공유 자료에 실제 참조를 기록하지 않는다.
- TTL은 발급부터 30분으로 고정하며 Preview를 반복해도 연장되지 않는다. 프로세스당 최대 256개, 동기화된 등록/조회로 용량을 제한한다. 꽉 찬 경우 활성 계획을 임의로 제거하지 않는다.
- 만료는 조회/등록 시 정리하며 재시작하면 모두 소멸한다. 미존재·만료·재시작 후 참조는 동일하게 `404 PLAN_NOT_FOUND_OR_EXPIRED`를 반환한다.
- `X-Tripback-Plan-Registration`은 `REGISTERED`, `NOT_ELIGIBLE`(DEMO/불완전/불가/불일치 검증), `CAPACITY_EXCEEDED`, `DISABLED` 중 하나다. 마지막 세 경우에는 계획 참조가 없다. 기존 검증 결과 JSON/상태는 유지된다. 비-loopback 참조 발급 요청은 활성화 상태에서 외부 Provider 호출 전에 403으로 거절한다.
- revision은 불변 snapshot의 `1`이다. 이번 단계에서는 수정/승인/삭제/갱신 API가 없다. 같은 참조의 모든 Preview는 최초 계획을 기준으로 한다.

### Preview 요청

```json
{
  "planRef": "<X-Tripback-Plan-Ref 헤더 값>",
  "revision": 1,
  "currentVisitIndex": 0,
  "phase": "STAYING",
  "observedAt": "2026-11-07T10:00:00+09:00",
  "elapsedStaySeconds": 600,
  "requestedTotalStayMinutes": 50
}
```

- 현재 방문지의 총 계획 체류시간을 변경한다. 추가 체류시간이 아니다. 방문지 인덱스는 0부터 시작하며 등록된 순서를 따른다. 다른 진행 단계는 지원하지 않는다.
- 입력은 UTF-8 JSON, 최대 8192바이트다. 중복 키·알 수 없는 필드·문자열 숫자·소수 duration·누락/null·후행 JSON은 거절한다. ETA/출처/방문 순서/귀가 지점을 입력받지 않는다.
- HTTP 입력의 총 체류시간은 0..1440분, 경과시간은 0..604800초다. 총 체류시간이 관측 경과시간보다 짧으면 오류다. 서비스의 순수 계산 모델은 별도 내부 계약을 유지한다.
- 관측시각에는 UTC offset이 필요하다. 서버 수신시각보다 미래이거나 60초보다 오래된 관측은 거절한다. 최초 여행 출발보다 앞선 관측 또는 출발 이후 경과보다 긴 체류도 거절한다. 이 검사는 실제 위치/진행을 인증하지 않는다.
- 서버 수신시각을 계산 기준으로 삼고 관측 이후 지난 정수 초를 체류 경과에 반영한다. 그 사이 요청한 총 체류시간을 초과한 경우도 명시적으로 거절한다. 과거 관측시각으로 ETA를 최신으로 위장할 수 없다.

### Preview 응답

정상 계산은 HTTP 200이다. FEASIBLE은 등록된 ETA와 보고된 체류 상태를 전제로 한 수학적 결과이며 실제 귀가 보장이 아니다. 아래는 오프라인 Fake 검증 테스트의 발췌 예시다(실제 장소/교통 기록 아님).

```json
{
  "status": "PREVIEW_ONLY",
  "baseRevision": 1,
  "planExpiresAt": "2026-11-07T01:30:00Z",
  "calculatedAt": "2026-11-07T01:00:00Z",
  "progressVerification": "CLIENT_REPORTED_NOT_VERIFIED",
  "currentVisitIndex": 0,
  "safetyBufferMinutes": 30,
  "returnDeadline": "2026-11-07T04:00:00Z",
  "before": {
    "feasibility": "FEASIBLE",
    "arithmeticFeasibility": "FEASIBLE",
    "predictedReturnAt": "2026-11-07T02:00:00Z",
    "remainingMarginSeconds": 7200,
    "remainingMarginMinutes": 120,
    "returnDeadlineMet": true,
    "safetyBufferSatisfied": true,
    "currentRemainingStayMinutes": 20
  },
  "after": {
    "feasibility": "FEASIBLE",
    "arithmeticFeasibility": "FEASIBLE",
    "predictedReturnAt": "2026-11-07T02:20:00Z",
    "remainingMarginSeconds": 6000,
    "remainingMarginMinutes": 100,
    "returnDeadlineMet": true,
    "safetyBufferSatisfied": true,
    "currentRemainingStayMinutes": 40
  }
}
```

실제 응답에는 `originalHome`, `orderedVisits`, `requiredVisits`, `notices`도 포함된다. before/after 각각에 `remainingLegs`(originalLegIndex/minutes/source/dataMode/verification/retrievedAt/cached)와 `reasons`가 포함된다. 실제 계획 참조 자체는 응답 본문에 반복하지 않는다.

- 현재 방문지의 도착 전 구간은 제외하고 남은 체류·이동·최종 귀가 구간을 각각 한 번 포함한다. 현재까지 경과한 체류시간을 다시 더하지 않는다. 남은 체류 초는 분으로 올림하며 기존 구간별 ETA 올림 규칙과 순수 엔진을 재사용한다.
- 동일한 ETA 가정에서는 총 30분/경과 10분을 총 50분으로 바꾸면 귀가 예상은 20분 늦어진다.
- 재사용 ETA는 `VERIFIED_CACHED`로 표시하고 원래 출처·조회시각을 보존한다. 서버 시각 기준 15분을 초과하거나 미래 조회시각/미검증/누락이면 UNKNOWN이다. `arithmeticFeasibility`는 가정 계산을 별도로 표시한다. 누락 시 귀가시각/여유는 null이다.
- 등록은 완전한 검증 계획만 허용하므로 정적 snapshot의 ETA 누락은 원칙적으로 참조 발급 단계에서 차단된다. 순수 엔진의 누락/미검증 방어도 유지한다.
- 유효한 ETA에서 이미 지난 마감은 입력 오류가 아니라 INFEASIBLE이다. 마감 충족과 안전 버퍼 충족을 따로 표시한다.
- Preview는 일정/순서/필수 방문지/귀가 지점/체류시간/revision을 변경하지 않는다. AI·장소·도로 Provider 조회, 자동 재시도, 승인, 내비 요청이 없다. 응답/오류는 `Cache-Control: no-store`다.

### 오류 규약

응답은 입력이나 예외 원문 없이 `{"code":"..."}` 형식이다.

| HTTP | code | 의미 |
|---|---|---|
| 400 | INVALID_REQUEST | JSON/자료형/범위/단계/날짜/알 수 없는 필드 오류 |
| 400 | INVALID_CURRENT_VISIT | 계획에 없는 방문지 인덱스 |
| 400 | INVALID_OBSERVATION_TIME | 미래 또는 60초보다 오래된 관측 |
| 400 | INVALID_STAY_OBSERVATION | 출발시각과 체류 관측 모순 |
| 400 | TOTAL_STAY_BELOW_ELAPSED | 요청 총 체류가 계산 기준 경과시간보다 짧음 |
| 400 | CALCULATION_OUT_OF_RANGE | 합산 또는 시각 계산 범위 초과 |
| 403 | LOCAL_DEVELOPMENT_ONLY | 비-loopback 요청 |
| 404 | PLAN_NOT_FOUND_OR_EXPIRED | 미존재/만료/재시작으로 사라진 참조 |
| 409 | PLAN_REVISION_MISMATCH | 기준 revision 불일치 |
| 413 | REQUEST_TOO_LARGE | 8192바이트 초과 |
| 415 | UNSUPPORTED_CHARSET | UTF-8 이외 charset |
| 500 | INTERNAL_ERROR | 예외 원문을 숨기는 서버 오류 |

기본 비활성 경로의 404 및 Spring의 잘못된 Content-Type/메서드 오류는 기존 프레임워크 처리를 따른다. 오류·실패·취소로도 원본 snapshot은 수정하지 않는다.

### 현재 검증 범위

- Fake Provider만 사용하는 서버 검증 → `/validate` 헤더 발급 → 실제 loopback HTTP Preview를 검증한다. 운영 코드에 Fixture 등록/우회 경로는 없다.
- 정상/단축/연장, 마감/버퍼 경계, 노후 ETA, 누락/DEMO 등록 거절, 만료/미존재, revision 불일치, 엄격한 입력 검증, 반복 Preview 불변성, 서버 오류 보존, 용량/동시성/재시작 소멸, 기본 비활성/loopback 차단을 검증한다.
- Preview 테스트에서 장소·도로 호출 수 불변 및 AI 모델 미호출을 확인한다. 순수 계산 엔진의 기존 누락 ETA UNKNOWN 테스트도 유지한다.
- 기존 `/plan`, `/validate`, `/recommend` 회귀 테스트를 포함한 Backend `test --offline`을 실행한다. 실제 OpenAI/카카오/도로 호출과 Live 테스트는 실행하지 않는다.
- Android 연동, 장치 위치/체류 관측, 인증·계획 소유권, 서버 승인/revision 갱신, 실제 외부 Provider 통합 검증은 아직 수행하지 않았다.

검증 실행 결과 (2026-11-07): Android Studio JBR을 JAVA_HOME으로 지정하고 프로젝트 루트에서 ./backend/gradlew.bat -p backend test --offline 실행, BUILD SUCCESSFUL. 전체 310개: 통과 308, 실패/오류 0, Live 2개 SKIPPED. 이번 API 단계 신규 25개와 기존 재계획 계산 24개 모두 통과. git diff --check 및 신규 파일 개별 공백 검사 통과. 실제 외부 API/장치/Android 연동은 실행하지 않았다.
