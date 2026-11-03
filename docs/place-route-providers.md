# 장소 검색·자동차 경로 Provider 기반

현재 상태: 인터페이스·모델·검증·사용 불가 기본 구현만 있습니다. **카카오 외부 API 호출, 응답 Adapter, API 키 조회, 캐시, 영업/방문 가능성 검증은 미구현**입니다. `TripPlanService`나 REST API에는 연결하지 않았습니다.

기존 DEMO plan API의 `predictedReturnAt`, `travelMinutes`, `isFeasible`, `remainingMarginMin`은 null로 유지합니다. 기존 귀가시간 계산 엔진의 규칙도 변경하지 않았습니다.

## 코드 구조

`backend/src/main/java/com/tripback/backend/trip/provider/`:

| 파일 | 책임 |
|---|---|
| PlaceSearchProvider / RoadRouteProvider | 장소 검색 / 자동차 이동 한 구간 조회 계약 |
| PlaceSearchQuery | 키워드, 선택 중심좌표·반경, 페이지·크기 |
| ProviderPlaceId / Wgs84Point | 제공자별 불투명 ID / 유한한 WGS84 위도·경도 |
| PlaceCandidate | 이름·주소·좌표·출처·조회 시각, 영업/방문 상태 미검증 |
| PlaceSearchResult | FOUND / NO_RESULTS / UNAVAILABLE / FAILED 구분 |
| RoadRouteQuery | 순서를 유지한 출발·목적 좌표. 현재 조건의 자동차 한 구간용 |
| RoadRouteResult | FOUND / NO_ROUTE / ETA_MISSING / UNAVAILABLE / FAILED, 초 단위 ETA 또는 null |
| ProviderData | 제공자·데이터 출처·응답 데이터 검증 선언·조회 시각 |
| ProviderFailure | 안전한 분류 코드, 선택 HTTP 상태, 선택 재시도 대기시간 |
| UnavailableProviders | 운영용 기본 구현. 네트워크/환경변수를 사용하지 않고 항상 UNAVAILABLE 반환 |
| RouteLegMapper | 기존 TravelLeg로 명시적으로 변환. 필수 구간과 출처를 유지 |

현재 기본 구현의 오류는 ADAPTER_NOT_IMPLEMENTED입니다. 키가 있더라도 실제 Adapter가 없으므로 사용 가능으로 바뀌지 않습니다. 향후 Adapter는 키·설정 누락 시 NOT_CONFIGURED/UNAVAILABLE을 반환해야 합니다. 빈 검색 결과로 대신 성공 처리하면 안 됩니다.

## 데이터 경계

- `ProviderPlaceId(provider, value)`는 제공자 이름까지 포함한 ID입니다. 같은 문자열도 공급자가 다르면 다른 ID입니다. 공백/빈 ID를 거부하고 선행 0을 포함한 원문을 보존합니다.
- 카카오맵 ID는 **Pleos NaviHelper POI ID가 아닙니다**. 이 계층에는 Pleos ID 변환이나 내비게이션 전송 코드가 없습니다.
- 좌표는 `Double.isFinite`와 위도 -90~90 / 경도 -180~180 범위를 검사합니다. 범위 검사는 실장소 존재·차량 진입 가능성 검증이 아닙니다.
- 검색 후보의 `visitStatus`는 항상 NOT_VERIFIED입니다. 공급자 응답 데이터가 검증됐어도 현재 영업·예약·휴무·입장·주차 여부가 확인됐다는 뜻은 아닙니다. 주소가 제공되지 않은 경우 빈 문자열을 보존하며 지어내지 않습니다.
- ProviderData의 VERIFIED_LIVE/CACHED는 향후 Adapter가 실제 응답을 확인·검사한 뒤 명시해야 합니다. 현재 운영 기본 구현은 해당 데이터를 생성하지 않습니다. DEMO_SIMULATED는 UNVERIFIED만 허용합니다.
- `retrievedAt`은 원 데이터 조회 시각입니다. 결과의 `attemptedAt`과 다르며 캐시를 읽었다고 원 조회 시각을 갱신하거나 LIVE로 승격하면 안 됩니다. 현재 캐시 신선도 판정은 구현하지 않았습니다.
- 잘못된 모델 값/상태 조합은 IllegalArgumentException입니다. 향후 Adapter 경계에서는 이를 INVALID_DATA 실패로 변환해야 하며, 원본의 잘못된 값을 정상값처럼 보정하면 안 됩니다.

## 장애 및 ETA 부족

- NO_RESULTS는 정상적으로 조회했으나 후보가 없는 경우, UNAVAILABLE은 키·설정·구현이 없는 경우입니다. NO_ROUTE도 오류/ETA 누락과 구분합니다.
- ProviderFailure: NOT_CONFIGURED, ADAPTER_NOT_IMPLEMENTED, AUTHENTICATION_FAILED, RATE_LIMITED, TIMEOUT, CONNECTION_FAILED, HTTP_ERROR, INVALID_DATA, MISSING_ETA.
- HTTP 401/403은 인증/권한 실패, 429는 호출 제한, 그 외 4xx/5xx는 HTTP_ERROR로 분류합니다. 타임아웃·연결 오류는 HTTP 코드 없이 표현할 수 있습니다. 오류 모델에는 원본 헤더·응답 본문·키·요청 URL을 담지 않습니다.
- `retryable`은 분류상 가능성만 뜻하며 자동 재시도/백오프/쿼터 소비 제어는 미구현입니다. 인증·설정·잘못된 데이터는 무조건 반복 호출하지 않습니다.
- FOUND에만 명시적 ETA와 ProviderData가 있습니다. ETA가 없는 실패·미설정·경로 없음 결과에는 durationSeconds/data가 null이며 0이나 임의 출처로 채우지 않습니다.
- RouteLegMapper는 null 결과 또는 ETA가 없는 결과를 **동일 index의 minutes=null TravelLeg**로 바꿉니다. 기존 엔진은 이를 UNKNOWN으로 처리합니다. 필수 방문지나 마지막 귀가 구간을 필터링하지 않습니다.
- 결과의 출발/목적지가 요청 구간과 다르면 변환을 거부합니다. 공급자의 도로 스냅 좌표는 향후 Adapter가 별도로 검증하고, 도메인 결과에는 요청 구간 정체성을 유지해야 합니다.
- 알려진 ETA의 초→분 변환은 구간별 올림입니다: 0초→0분, 1~60초→1분, 61초→2분. 원래 초는 RoadRouteResult에 남습니다. 임의 ETA 생성이 아니라 기존 분 단위 엔진 입력을 위한 보수적 변환이며 오버플로는 거부합니다.
- 변환해도 DEMO/CACHED/LIVE 출처와 원 조회 시각은 보존합니다. 테스트 ETA로 수학적 FEASIBLE이 나와도 실제 귀가 가능·실시간 데이터 검증 성공이 아닙니다.
- 왕복 일정의 호출자는 N개 방문지에 N+1개의 구간(귀가 포함)을 제공해야 합니다. 이번에는 그 오케스트레이션이나 API 연결을 만들지 않았습니다.

## 우선 검토한 공식 API

공식 문서 확인일: 2026-11-03. 아래는 **추후 Adapter 검토용**이며 실행 코드에 URL/요청/응답 변환을 구현하지 않았습니다.

### 카카오 로컬 키워드 장소 검색

[공식 REST 문서](https://developers.kakao.com/docs/ko/local/dev-guide#search-by-keyword)

- GET `https://dapi.kakao.com/v2/local/search/keyword.json`
- 인증은 Authorization 헤더의 KakaoAK 방식과 REST API 키입니다. 실제 키 값은 이 문서에 기록하지 않습니다.
- 필수 query, 선택 x(경도)/y(위도), radius(0~20,000m), page(1~45), size(1~15). 현재 PlaceSearchQuery는 이 초기 범위를 사용합니다.
- `documents`의 id/place_name/address_name/road_address_name/x/y를 후보로 검증·변환하는 Adapter가 필요합니다. x는 longitude, y는 latitude이며 String 좌표의 숫자 변환·유한성 검사가 필요합니다.
- 검색 응답은 영업/방문 가능 검증으로 취급하지 않습니다. ID는 카카오 로컬 네임스페이스에 보관합니다.

### 카카오모빌리티 자동차 길찾기

[공식 자동차 길찾기](https://developers.kakaomobility.com/guide/navi-api/directions.html), [API 종류 및 출발 기준](https://developers.kakaomobility.com/guide/navi-api/start)

- GET `https://apis-navi.kakaomobility.com/v1/directions`
- Authorization은 KakaoAK 방식의 REST API 키, Content-Type은 application/json입니다.
- origin/destination은 경도,위도 순서입니다. 성공 여부는 HTTP 코드뿐 아니라 `routes[].result_code` 등을 확인해야 하며, `summary.duration`은 초 단위입니다. 실제 Adapter는 미구현입니다.
- 현재 자동차 길찾기는 현재 출발 기준입니다. 미래 일정의 교통 예측으로 바꿔 해석하지 않습니다. 미래 운행 API는 별도 검토 대상이고 현재 RoadRouteQuery는 미래 시각을 받지 않습니다.
- 공식 시작 가이드상 일반 자동차 길찾기의 경유지는 최대 5개입니다. 현재 인터페이스는 한 구간 조회만 표현하며, 제휴 전용 URL/계약을 일반 API에 섞지 않습니다.

## 인증·이용 제한·상업 서비스 적용 전 확인

- 향후 실제 Adapter는 서버 환경변수에서만 비밀 값을 읽도록 합니다. 내부 환경변수 이름은 `TRIPBACK_KAKAO_LOCAL_REST_API_KEY`, `TRIPBACK_KAKAO_MOBILITY_REST_API_KEY`를 사용할 계획이며, 이번에는 읽기조차 하지 않습니다. 앱/DTO/응답/toString/로그/문서/저장소에 비밀 값을 넣지 않습니다. 키 발급·입력은 이번 작업에 필요하지 않습니다.
- [카카오맵 사용 조건](https://developers.kakao.com/docs/ko/kakaomap/common): 앱 활성화와 플랫폼 키 설정을 확인해야 하며, 무료 쿼터 적용 앱 및 초과 사용의 유료 설정 조건이 있습니다.
- [공식 쿼터](https://developers.kakao.com/docs/ko/getting-started/quota): 확인일 기준 키워드 검색 일간 제공량 100,000건. 실제 앱 적용량·권한·월간 정책은 배포 전에 다시 확인해야 합니다.
- [카카오모빌리티 공식 가격/쿼터](https://developers.kakaomobility.com/price/): 확인일 기준 일반 자동차 길찾기 일간 10,000건, 무료 초과분은 게시된 유료 정책 대상입니다. 쿼터/가격은 바뀔 수 있어 코드에 요금이나 운영 허용량을 확정하지 않습니다.
- [공식 오류 안내](https://developers.kakaomobility.com/guide/navi-api/solution.html): 401/403 인증·권한, 429 쿼터/호출 한도, 5xx 공급자 장애를 구분해야 합니다. HTTP 성공만으로 정상 경로·ETA를 보장하면 안 됩니다.
- 상업 서비스 전에는 [서비스 약관](https://developers.kakao.com/terms/ko/site-terms), [운영 정책](https://developers.kakao.com/terms/ko/site-policies), 위 제품별 정책에서 과금/권한·허용 서비스 범위·데이터 보관/재배포·지도 표시/상표 조건을 검토해야 합니다. 이 문서는 Tripback의 상업적 사용 승인이나 무제한 캐시·재배포 허용을 확인한 문서가 아닙니다.

## 검증 범위

가짜 장소·좌표·ETA는 테스트 코드에만 존재합니다. 테스트는 모델 검증·상태 분리·출처 보존·기존 엔진 UNKNOWN 유지를 검사하며, 실제 외부 장소/도로 API 성공을 검증하지 않습니다.
기존 여행 일정 API와 Health API 회귀 테스트를 함께 실행합니다. 기존 API 시연 데이터, 귀가시간 엔진, Android/NaviHelper/Gradle/HTML은 변경하지 않습니다.
