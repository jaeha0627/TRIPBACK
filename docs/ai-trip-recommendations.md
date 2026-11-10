# AI 왕복 추천 구현·실행 안내

기준일: 2026-11-04. 실제 LLM 호출이 가능한 서버 구현이며, 이번 검증에서는 Fake LLM과 로컬 모의 HTTP만 사용했습니다. 실제 카카오+OpenAI 통합 성공이라고 보고하지 않습니다.

## 구조와 재사용

`POST /api/v1/trips/recommend` → 엄격한 요청 검증 → 서버 검색어 생성 → 기존 PlaceSearchProvider → 지역/후보 필터 → TripRecommendationModel → 구조화 JSON의 독립 검사 → 기존 TripValidationService.validate() → 기존 TripValidationResponse 매핑 → 수락/거부 대안입니다.

PlaceCandidatesService는 center/radius 없는 keyword 전용 API 서비스이므로 그대로 호출해 중심 반경 검사를 생략하지 않습니다. 해당 서비스가 사용하는 기존 Provider/모델을 재사용해 중앙 좌표·반경을 포함한 PlaceSearchQuery를 호출하며 카카오 HTTP 구현은 중복 작성하지 않았습니다.

각 요청의 Provider를 호출 한도/시간/선택 장소의 재검색 반경 검사로 감싸고 기존 TripValidationService 인스턴스를 생성합니다. 추천 전용 래퍼는 재검색에도 최초 targetCenter와 radius=20000을 전달합니다. 선택 ID는 원래 검색어로 다시 확인하며, 재조회 좌표가 최초 후보와 다르거나 20km 밖이면 INVALID_DATA로 거부합니다. 최초 후보로 재확인을 대체하지 않습니다. 기존 클래스의 장소 ID 확인·N+1 구간·RouteLegMapper·Feasibility 엔진 로직은 그대로 사용합니다. 기본 Spring TripValidationService와 기존 REST API의 동작은 변경하지 않았습니다.

새로운 의존성은 없습니다. 기존 Java 25 HttpClient와 설치된 Jackson 3를 사용합니다. Android/NaviHelper/프로토타입/DEMO 계약은 수정하지 않았습니다.

## 추가·수정 파일

`backend/src/main/java/com/tripback/backend/trip/recommendation/` 신규 파일:

| 파일 | 책임 |
|---|---|
| RecommendationJson.java | UTF-8·중복 키·필드·타입·배열을 엄격히 검사하는 로컬 JSON 유틸리티 |
| RecommendationRequest.java | 전용 입력 DTO 및 선호/필수 방문 입력 검사, 모델 입력 최소화 |
| RecommendationBudget.java | 요청별 180초 및 18회 장소/12회 도로 호출 한도 |
| RegionDistance.java | WGS84 Vincenty 타원체 거리, 비수렴은 반경 밖으로 처리 |
| CandidateCollector.java | 통제된 검색어, 첫 페이지 15개, 최대 30개 후보, 반경/ID 충돌/제외 사유 |
| TripRecommendationModel.java | 공급자 독립 모델 인터페이스, 안전한 상태와 명시적 호출 출처 |
| OpenAiTripRecommendationModel.java | 공식 Responses HTTP·엄격한 스키마·거부/잘림/오류/크기/시간 제한 |
| RecommendationConfiguration.java | 키 조회·호출 없이 모델 Bean 등록, 명시적 모델 Bean 재정의 가능 |
| ModelPlanParser.java | 모델 JSON 재검사, 허용 ID·중복·0~240분·필수 방문·민감 문장 형태 검사 |
| RecommendationResponse.java | 원문 모델 응답을 제외한 명시적 REST 결과 projection |
| TripRecommendationService.java | 후보→모델 1회→최대 2안 재검증, 동시 작업 2개 및 수락/거부 판정 |
| TripRecommendationController.java | 기본 비활성/loopback 제한, 입력 스트림 16KiB/응답 256KiB, 안전한 HTTP 오류 |

`backend/src/test/java/com/tripback/backend/trip/recommendation/` 신규 파일:

- RecommendationFixtures.java: 가상 Provider/Fake LLM·가상 장소/ETA fixture(모두 DEMO)
- TripRecommendationServiceTests.java: 33건, 후보·지역·ID·추천 검증·기존 엔진·한도·출처
- OpenAiTripRecommendationModelTests.java: 13건, 로컬 HTTP에서 실제 전송 코드·스키마·상태·보안 검증
- TripRecommendationApiTests.java: 11건, 실제 localhost HTTP + Fake Provider/Fake 모델
- TripRecommendationApiDisabledTests.java: 1건, 기본 404·시작 시 미호출
- RecommendationSafetyTests.java: 4건, 비-loopback 차단·예외 비노출·동시성·호출 상한

문서: docs/api-contract.md의 신규 recommend 계약, 이 실행 문서를 추가/갱신했습니다.

## 공식 OpenAI 연동 근거

확인한 공식 문서:

- [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs): Responses의 text.format + json_schema + strict, 거부와 incomplete 처리
- [Text generation](https://developers.openai.com/api/docs/guides/text): Responses REST 메시지 및 출력 처리
- [GPT-4.1 mini 모델 지원 표](https://developers.openai.com/api/docs/models/gpt-4.1-mini): Responses와 Structured Outputs 지원 예시

모델 이름을 코드 기본값으로 강제하지 않습니다. 위 모델은 공식 기능 지원을 확인한 예시이며 이번에 실제로 실행한 모델이 아닙니다. 운영자는 계정에서 사용 가능한 Responses/Structured Outputs 지원 모델 ID를 TRIPBACK_AI_MODEL로 명시해야 합니다. API 호환성이나 계정 권한이 없는 이름은 안전한 HTTP_ERROR로 실패하며 다른 모델로 자동 대체하지 않습니다. 모델 목록 조회나 자동 모델 선택을 위한 추가 API 호출도 없습니다.

모델 HTTP 요청은 공식 `https://api.openai.com/v1/responses`로 고정되며 리다이렉트를 따르지 않습니다. Authorization은 서버 헤더에만 넣고 raw 오류/출력/키는 로그·실패 응답에 넣지 않습니다. store=false는 Responses 저장 설정이며 공급자의 모든 데이터 보존 정책을 별도로 보증하는 의미는 아닙니다.

JSON Schema의 허용 ID enum과 추가 필드 금지 외에도 서버에서 출력 구조·개수·시간 범위·필수 방문을 다시 검사합니다. 모델이 JSON Schema를 지켰다는 것만으로 실제 장소·경로·설명 내용이 검증되지는 않습니다.

## 로컬 실행 설정

필요한 서버 환경변수 이름:

| 이름 | 역할 |
|---|---|
| TRIPBACK_ENABLE_AI_RECOMMENDATION_API | true일 때만 API/모델 호출 활성화 |
| OPENAI_API_KEY | 서버 전용 OpenAI 인증 값 |
| TRIPBACK_AI_MODEL | 사용자가 명시한 지원 모델 ID, 기본값 없음 |
| TRIPBACK_KAKAO_LOCAL_REST_API_KEY | 기존 장소 검색 Adapter 인증 값 |
| TRIPBACK_KAKAO_MOBILITY_REST_API_KEY | 기존 도로 ETA Adapter 인증 값 |
| SERVER_ADDRESS | 로컬 검증에서는 127.0.0.1로 설정 권장 |

키와 모델 ID는 사용자가 로컬 실행 환경에서 안전하게 준비합니다. 실제 키 값을 채팅·파일·명령 이력·로그로 전달하는 예시는 제공하지 않습니다. API 키를 요청하거나 읽어서 출력하지 않습니다.

```powershell
# backend 디렉터리에서, 인증키와 모델 ID는 이미 안전하게 설정된 환경을 사용
$env:SERVER_ADDRESS = '127.0.0.1'
$env:TRIPBACK_ENABLE_AI_RECOMMENDATION_API = 'true'
.\gradlew.bat bootRun
```

키/모델/활성화 조건이 없거나 JDK HttpClient 진단 로깅이 켜져 있으면 모델을 NOT_CONFIGURED로 취급합니다. 이 경우 카카오 수집도 시작하지 않습니다. 서버 시작 시에는 외부 호출이 없습니다. 후보 검색/validate REST API 자체의 활성화 스위치는 켤 필요가 없습니다.

HTTP 클라이언트는 `http://127.0.0.1:8080/api/v1/trips/recommend`에 API 계약의 JSON을 전송합니다. Android 연결은 이번 범위가 아닙니다.

## 비용·시간 제한과 해석

- 기본 비활성이고 모델 미설정이면 유료 호출은 없습니다. 유효 요청의 최대 호출은 후보 검색 8 + 재검색 10 + 도로 12 + 모델 1입니다. 빈 후보/필수 후보 누락은 모델 호출 전에 중단합니다. 30개 후보가 모이면 나머지 검색도 생략합니다.
- 모델 요청은 입력 본문 64KiB, max_output_tokens=2048, 전체 본문 제한 25초입니다. envelope 128KiB 및 생성 JSON 32KiB 제한을 따로 적용합니다. 전체 추천은 180초, REST 응답은 256KiB이며 무한 재시도가 없습니다.
- 금액은 선택 모델·입출력 토큰·계정 및 카카오 쿼터 정책에 따라 달라집니다. 금액을 코드에 고정하지 않았습니다. 실제 Live 실행 전에 운영자가 현재 요금·계정 한도를 확인해야 합니다. 이미 공급자가 접수한 요청은 취소/시간 초과가 발생해도 과금될 수 있습니다.
- 동시 작업 2개 제한과 loopback 검사는 사용자 인증·rate limit이 아닙니다. 인증·호출량/사용자별 비용 제한 없이 공개 배포하거나 공개 reverse proxy에 연결하지 않습니다.
- 수락 대안은 기존 귀가 FEASIBLE + 첫 outbound/final return 구간의 maximumOneWayMinutes 통과를 의미합니다. 전체 이동 순회의 편도 합이나 모든 origin→중간 방문지 ETA는 추가 조회하지 않습니다. 이 범위를 conditionStatus/limitations에 명시합니다.
- INFEASIBLE, UNKNOWN, 편도 제한 초과는 rejectedAlternatives에 상세 validation과 함께 남습니다. 최대 2개의 구조화 제안 중 유효한 안만 반환하며 첫 후보/다른 장소/DEMO로 강제 대체하지 않습니다.
- modelGenerations는 모델 인터페이스 호출 횟수이며 결제된 모델 호출 횟수가 아닙니다. OPENAI_HTTP는 공식 HTTP를 시도했음을 나타내고, COMPLETED도 설명이나 일정의 실세계 검증 완료를 뜻하지 않습니다. FAKE_MODEL/TEST_HTTP는 테스트 실행입니다. UNCONFIRMED는 호출 결과를 확인하지 못한 상태입니다.

## Fake 결과 예시 (발췌)

아래는 테스트 설명용이며 실제 카카오/OpenAI 응답이 아닙니다. 실제 응답에는 모든 후보·검색·제외 사유·validation 계약 필드·조건과 제한사항이 포함됩니다.

```json
{
  "recommendationStatus":"PARTIAL",
  "aiProviderStatus":{"provider":"FAKE","status":"COMPLETED","invocation":"FAKE_MODEL"},
  "alternatives":[{
    "planId":"ai-proposal-1",
    "title":"여유형",
    "recommendationReason":"가상 모델의 검증되지 않은 설명",
    "orderedVisits":[
      {"index":0,"id":{"provider":"KAKAO_LOCAL","value":"001"},"originalKeyword":"천안 독립기념관 관광","stayMinutes":30},
      {"index":1,"id":{"provider":"KAKAO_LOCAL","value":"002"},"originalKeyword":"천안 독립기념관 관광","stayMinutes":30}
    ],
    "validation":{
      "feasibility":"FEASIBLE",
      "dataCoverage":"CONTAINS_DEMO",
      "dataModes":["DEMO_SIMULATED"],
      "totalTravelMinutes":30,
      "totalStayMinutes":60,
      "predictedReturnAt":"2026-11-07T09:30+09:00",
      "remainingMarginMin":510,
      "returnDeadlineMet":true,
      "safetyBufferSatisfied":true,
      "missingEtaLegIndexes":[]
    },
    "feasibility":"FEASIBLE",
    "dataModes":["DEMO_SIMULATED"],
    "notices":["AI_REASON_UNVERIFIED","CURRENT_ETAS_ONLY_NOT_RETURN_GUARANTEE","VISITABILITY_NOT_VERIFIED"]
  }],
  "rejectedAlternatives":[]
}
```

## 명시적 카카오 Live 테스트 실행

일반 `test`는 `tripback.liveTests.allowed=false`를 강제합니다. 키와 공급자별 Live 환경변수, `-PenableLiveTests=true`가 있어도 실제 호출하지 않으며 Live 2건은 SKIPPED로 남습니다. 사유는 `build/test-results/test/TEST-*.xml`에서 확인할 수 있습니다. `KakaoLiveTestSafetyTests` 2건은 실제 전송 전에 중단되는지 검사합니다.

실제 Live 호출은 별도 `liveTest` 태스크, `-PenableLiveTests=true`, 실행할 공급자의 플래그 `1`, 해당 키가 모두 있을 때만 가능합니다. 기본 동의 상태는 비활성입니다. 아래 명령은 실제 공급자 요청과 비용이 발생할 수 있는 별도 실행 방법이며 이번 작업에서는 실제 호출하지 않았습니다. 키는 실행 환경에 안전하게 준비하고 명령/문서/로그에 값을 기록하지 않습니다.

```powershell
# 장소 검색만 명시적으로 동의 (backend 디렉터리)
$env:TRIPBACK_RUN_KAKAO_LOCAL_LIVE_TESTS = '1'
.\gradlew.bat liveTest -PenableLiveTests=true --tests '*KakaoPlaceSearchLiveTests' --rerun-tasks

# 도로 경로만 명시적으로 동의
$env:TRIPBACK_RUN_KAKAO_MOBILITY_LIVE_TESTS = '1'
.\gradlew.bat liveTest -PenableLiveTests=true --tests '*KakaoRoadRouteLiveTests' --rerun-tasks
```

태스크 허가 누락은 `LIVE_TASK_NOT_AUTHORIZED`, 공급자 동의 누락은 `LIVE_TEST_NOT_ENABLED`, 키 누락은 키 미설정 사유로 SKIPPED됩니다. 진단 HTTP 로깅이 켜져 있어도 중단합니다. Live 결과는 `build/test-results/liveTest/`에 기록됩니다. 키가 없는 기본 실행, 합성 키와 동의 플래그가 있는 일반 test, 허가된 liveTest의 공급자 동의 누락 및 키 누락 경로를 실제 API 호출 없이 확인했습니다.

## 실제 수행한 자동 검증

Java 25.0.3, backend 디렉터리:

```powershell
.\gradlew.bat test bootJar --rerun-tasks
```

- BUILD SUCCESSFUL, 실행 JAR 생성 확인.
- 기존 추천 테스트 55건과 추가 추천 회귀 7건, Live 차단 회귀 2건 모두 성공. 전체 253건: 성공 251, 실패 0, 오류 0, SKIPPED 2.
- 기존 DEMO·장소·도로·귀가 검증 API/엔진 회귀 테스트 통과.
- 전체 회귀 실행에서는 실제 키/실행 플래그를 제거했습니다. 일반 test는 별도로 Live 실행 허가를 강제 비활성화하여 장소·도로 Live 테스트가 LIVE_TASK_NOT_AUTHORIZED 사유로 SKIPPED됩니다. OpenAI 키/모델/활성화 플래그도 제거했습니다. 새 테스트는 Fake 또는 전송 전 차단 검사입니다.
- 실제 OpenAI 호출, 실제 카카오 호출, 유료 API 사용, Android/차량 연동은 수행하지 않았습니다. 테스트용 생성 데이터는 DEMO이며 LIVE를 위장하지 않습니다.
- git diff --check 및 신규 파일 공백 검사 통과.

남은 검증/기능: 사용자가 선택한 실제 모델·계정의 기능/권한/요금 확인, 실제 카카오+LLM 통합, 지역명/좌표 정체성 확인, 미래 교통·캐시 신선도·영업·주차·가격·에너지·선호 조건의 실증 검사, 모든 origin→각 방문 ETA, 운영 인증/rate limit/비용 한도, Android 연결, 일정 저장·선택 승인·재계획·내비게이션 전송입니다.
