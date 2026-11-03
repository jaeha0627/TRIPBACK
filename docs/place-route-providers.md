# 장소 검색·자동차 경로 Provider 기반

현재 상태: **카카오 로컬 키워드 검색 및 카카오모빌리티 자동차 길찾기 HTTP Adapter와 Spring 설정을 구현했습니다. 실제 키 없는 로컬 모의 서버 테스트로 검증하며, 공식 카카오모빌리티 API 인증 호출은 수행하지 않았습니다.** 캐시, 영업/방문 가능성 검증은 미구현입니다. `TripPlanService`나 REST API에는 연결하지 않았습니다.

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
| kakao/KakaoPlaceSearchAdapter | 카카오 로컬 키워드 HTTP 요청·제한·응답 검증 |
| kakao/KakaoPlaceSearchConfiguration | 키 없는 서버 시작도 가능한 Spring PlaceSearchProvider Bean 등록 |
| kakao/KakaoRoadRouteAdapter | 현재 기준 자동차 한 구간 HTTP 조회 및 초 단위 ETA 검증 |
| kakao/KakaoRoadRouteConfiguration | 키 없는 서버 시작도 가능한 Spring RoadRouteProvider Bean 등록 |

Spring에서 사용하는 PlaceSearchProvider는 이제 KakaoPlaceSearchAdapter입니다. 실제 검색 시 환경변수 키가 없으면 NOT_CONFIGURED/UNAVAILABLE을 반환하며 서버 시작은 실패하지 않습니다. 명시적 Bean 재정의가 가능하고 기존 UnavailableProviders 팩터리는 호환성을 위해 유지합니다. Spring RoadRouteProvider는 KakaoRoadRouteAdapter이며 키 없는 route()는 NOT_CONFIGURED/UNAVAILABLE입니다. 기존 UnavailableProviders 팩터리는 호환성을 위해 ADAPTER_NOT_IMPLEMENTED/UNAVAILABLE을 유지합니다. 빈 검색 결과를 장애 대신 성공 처리하지 않습니다.

## 데이터 경계

- `ProviderPlaceId(provider, value)`는 제공자 이름까지 포함한 ID입니다. 같은 문자열도 공급자가 다르면 다른 ID입니다. 공백/빈 ID를 거부하고 선행 0을 포함한 원문을 보존합니다.
- 카카오맵 ID는 **Pleos NaviHelper POI ID가 아닙니다**. 이 계층에는 Pleos ID 변환이나 내비게이션 전송 코드가 없습니다.
- 좌표는 `Double.isFinite`와 위도 -90~90 / 경도 -180~180 범위를 검사합니다. 범위 검사는 실장소 존재·차량 진입 가능성 검증이 아닙니다.
- 검색 후보의 `visitStatus`는 항상 NOT_VERIFIED입니다. 공급자 응답 데이터가 검증됐어도 현재 영업·예약·휴무·입장·주차 여부가 확인됐다는 뜻은 아닙니다. 주소가 제공되지 않은 경우 빈 문자열을 보존하며 지어내지 않습니다.
- ProviderData의 VERIFIED_LIVE/CACHED는 실제 공급자 응답을 확인·검사한 뒤에만 명시해야 합니다. 새 장소 Adapter는 고정된 공식 HTTPS URL의 HTTP 200·JSON·전체 후보 검증을 모두 통과한 경우에만 VERIFIED_LIVE를 반환합니다. 루프백 모의 서버 응답은 DEMO_SIMULATED/UNVERIFIED로 고정하며 검증된 실데이터로 승격하지 않습니다. 아직 공식 인증 호출을 실행한 증거는 없습니다.
- `retrievedAt`은 원 데이터 조회 시각입니다. 결과의 `attemptedAt`과 다르며 캐시를 읽었다고 원 조회 시각을 갱신하거나 LIVE로 승격하면 안 됩니다. 현재 캐시 신선도 판정은 구현하지 않았습니다.
- 잘못된 모델 값/상태 조합은 IllegalArgumentException입니다. 장소 Adapter는 이를 INVALID_DATA 실패로 변환하며, 원본의 잘못된 값을 정상값처럼 보정하거나 유효하지 않은 후보를 조용히 제외하지 않습니다.

## 장애 및 ETA 부족

- NO_RESULTS는 정상적으로 조회했으나 후보가 없는 경우, UNAVAILABLE은 키·설정·구현이 없는 경우입니다. NO_ROUTE도 오류/ETA 누락과 구분합니다.
- ProviderFailure: NOT_CONFIGURED, ADAPTER_NOT_IMPLEMENTED, AUTHENTICATION_FAILED, RATE_LIMITED, TIMEOUT, CONNECTION_FAILED, HTTP_ERROR, INVALID_DATA, MISSING_ETA.
- HTTP 401/403은 인증/권한 실패, 429는 호출 제한, 그 외 4xx/5xx는 HTTP_ERROR로 분류합니다. 타임아웃·연결 오류는 HTTP 코드 없이 표현할 수 있습니다. 오류 모델에는 원본 헤더·응답 본문·키·요청 URL을 담지 않습니다.
- `retryable`은 분류상 가능성만 뜻하며 자동 재시도/백오프/쿼터 소비 제어는 미구현입니다. 인증·설정·잘못된 데이터는 무조건 반복 호출하지 않습니다.
- FOUND에만 명시적 ETA와 ProviderData가 있습니다. ETA가 없는 실패·미설정·경로 없음 결과에는 durationSeconds/data가 null이며 0이나 임의 출처로 채우지 않습니다.
- RouteLegMapper는 null 결과 또는 ETA가 없는 결과를 **동일 index의 minutes=null TravelLeg**로 바꿉니다. 기존 엔진은 이를 UNKNOWN으로 처리합니다. 필수 방문지나 마지막 귀가 구간을 필터링하지 않습니다.
- 결과의 출발/목적지가 요청 구간과 다르면 변환을 거부합니다. 현재 자동차 Adapter는 공급자의 도로 스냅 좌표를 사용하지 않고, 도메인 결과에 요청 구간 정체성을 유지합니다.
- 알려진 ETA의 초→분 변환은 구간별 올림입니다: 0초→0분, 1~60초→1분, 61초→2분. 원래 초는 RoadRouteResult에 남습니다. 임의 ETA 생성이 아니라 기존 분 단위 엔진 입력을 위한 보수적 변환이며 오버플로는 거부합니다.
- 변환해도 DEMO/CACHED/LIVE 출처와 원 조회 시각은 보존합니다. 테스트 ETA로 수학적 FEASIBLE이 나와도 실제 귀가 가능·실시간 데이터 검증 성공이 아닙니다.
- 왕복 일정의 호출자는 N개 방문지에 N+1개의 구간(귀가 포함)을 제공해야 합니다. 이번에는 그 오케스트레이션이나 API 연결을 만들지 않았습니다.

## 우선 검토한 공식 API

공식 문서 확인일: 2026-11-03. **장소 키워드 검색과 현재 기준 자동차 한 구간 조회를 HTTP Adapter로 구현**했습니다. 여행 일정 API 연결과 미래 운행 정보는 구현 범위에 포함하지 않습니다.

### 카카오 로컬 키워드 장소 검색

[공식 REST 문서](https://developers.kakao.com/docs/ko/local/dev-guide#search-by-keyword)

- GET `https://dapi.kakao.com/v2/local/search/keyword.json`
- 인증은 Authorization 헤더의 KakaoAK 방식과 REST API 키입니다. 실제 키 값은 이 문서에 기록하지 않습니다.
- 필수 query, 선택 x(경도)/y(위도), radius(0~20,000m), page(1~45), size(1~15). 현재 PlaceSearchQuery는 이 초기 범위를 사용합니다.
- `documents`의 id/place_name/address_name/road_address_name/x/y를 검증·변환합니다. x는 longitude, y는 latitude로 처리합니다. 도로명 주소가 없으면 지번 주소, 둘 다 없으면 빈 문자열을 유지합니다.
- 검색 응답은 영업/방문 가능 검증으로 취급하지 않습니다. ID는 카카오 로컬 네임스페이스에 보관합니다.

### 카카오모빌리티 자동차 길찾기

[공식 자동차 길찾기](https://developers.kakaomobility.com/guide/navi-api/directions.html), [API 종류 및 출발 기준](https://developers.kakaomobility.com/guide/navi-api/start)

- GET `https://apis-navi.kakaomobility.com/v1/directions`
- Authorization은 KakaoAK 방식의 REST API 키, Content-Type은 application/json입니다.
- origin/destination은 경도,위도 순서입니다. 성공 여부는 HTTP 코드뿐 아니라 `routes[].result_code` 등을 확인해야 하며, `summary.duration`은 초 단위입니다. KakaoRoadRouteAdapter가 이 계약을 구현합니다.
- 현재 자동차 길찾기는 현재 출발 기준입니다. 미래 일정의 교통 예측으로 바꿔 해석하지 않습니다. 미래 운행 API는 별도 검토 대상이고 현재 RoadRouteQuery는 미래 시각을 받지 않습니다.
- 공식 시작 가이드상 일반 자동차 길찾기의 경유지는 최대 5개입니다. 현재 인터페이스는 한 구간 조회만 표현하며, 제휴 전용 URL/계약을 일반 API에 섞지 않습니다.

## 인증·이용 제한·상업 서비스 적용 전 확인

- 장소 Adapter는 검색 시 서버 프로세스 환경변수 `TRIPBACK_KAKAO_LOCAL_REST_API_KEY`에서만 인증 값을 읽습니다. `application.properties`, Android, 요청 DTO 또는 별도 REST 파라미터에서 키를 받지 않습니다. Bean 생성 시에는 키 조회·네트워크 호출이 없습니다. 자동차 경로 Adapter도 route() 호출 시에만 `TRIPBACK_KAKAO_MOBILITY_REST_API_KEY`에서 키를 읽습니다. 비밀 값은 코드/테스트 파일/앱/DTO/응답/toString/로그/문서/저장소에 넣지 않습니다. 이번 검증에 실제 키는 필요하지 않습니다.
- [카카오맵 사용 조건](https://developers.kakao.com/docs/ko/kakaomap/common): 앱 활성화와 플랫폼 키 설정을 확인해야 하며, 무료 쿼터 적용 앱 및 초과 사용의 유료 설정 조건이 있습니다.
- [공식 쿼터](https://developers.kakao.com/docs/ko/getting-started/quota): 확인일 기준 키워드 검색 일간 제공량 100,000건. 실제 앱 적용량·권한·월간 정책은 배포 전에 다시 확인해야 합니다.
- [카카오모빌리티 공식 가격/쿼터](https://developers.kakaomobility.com/price/): 확인일 기준 일반 자동차 길찾기 일간 10,000건, 무료 초과분은 게시된 유료 정책 대상입니다. 쿼터/가격은 바뀔 수 있어 코드에 요금이나 운영 허용량을 확정하지 않습니다.
- [공식 오류 안내](https://developers.kakaomobility.com/guide/navi-api/solution.html): 401/403 인증·권한, 429 쿼터/호출 한도, 5xx 공급자 장애를 구분해야 합니다. HTTP 성공만으로 정상 경로·ETA를 보장하면 안 됩니다.
- 상업 서비스 전에는 [서비스 약관](https://developers.kakao.com/terms/ko/site-terms), [운영 정책](https://developers.kakao.com/terms/ko/site-policies), 위 제품별 정책에서 과금/권한·허용 서비스 범위·데이터 보관/재배포·지도 표시/상표 조건을 검토해야 합니다. 이 문서는 Tripback의 상업적 사용 승인이나 무제한 캐시·재배포 허용을 확인한 문서가 아닙니다.

## 장소 Adapter 운영 설정과 제한

- 서버 프로세스 실행 환경에 `TRIPBACK_KAKAO_LOCAL_REST_API_KEY`를 설정해 사용할 수 있습니다. 실제 값을 채팅·문서·명령 이력·커밋에 남기지 마세요. 현재 작업은 키 발급/입력을 요구하지 않으며 공식 인증 호출도 수행하지 않았습니다.
- Java 25 HttpClient를 재사용합니다. 연결 타임아웃 3초, 요청 및 전체 본문 수신 제한 5초입니다. 청크 본문이 멈춰도 제한 후 요청을 취소합니다. Spring 컨텍스트 종료 시 HttpClient도 종료합니다.
- 키워드는 UTF-8 URL 인코딩으로 전송하고 키는 Authorization 헤더에만 둡니다. 애플리케이션 자체 자동 재시도는 없습니다.
- 운영 URL은 공식 HTTPS 엔드포인트로 고정되어 있으며 환경변수/요청 파라미터로 바꾸지 못합니다. 테스트 전용 생성자는 패키지 내부에서 루프백 HTTP만 허용합니다. 리다이렉트는 따라가지 않고 3xx는 INVALID_DATA로 처리하므로 인증 헤더를 다른 위치로 전달하지 않습니다.
- 본문은 최대 1MiB입니다. Content-Length 및 실제 수신량을 검사하며, JSON Content-Type이 아니거나 요청하지 않은 압축 응답이면 거부합니다. 오류 응답 본문은 읽어 결과/로그에 옮기지 않습니다.
- HTTP 401/403, 429, 기타 4xx/5xx는 기존 오류 모델로 분류합니다. 타임아웃은 TIMEOUT, 연결 실패는 CONNECTION_FAILED입니다. HTTP 200의 잘못된 JSON·필드·좌표는 INVALID_DATA입니다.
- meta의 total_count/pageable_count/is_end와 documents 배열 구조를 검사합니다. 한 페이지 후보 수는 요청 size 이하이어야 합니다. 중복 JSON 키·장소 ID, 후행 JSON 토큰, 누락/공백 ID·이름, 숫자가 아닌 좌표, NaN/무한대·범위 밖 좌표를 거부합니다. 후보 하나라도 유효하지 않으면 전체 FAILED를 반환합니다.
- NO_RESULTS는 유효한 응답의 **현재 페이지** documents가 빈 경우입니다. 공급자 장애 또는 설정 누락을 뜻하지 않습니다.
- 이름·주소·좌표 확인은 영업 중·방문 가능·주차 가능 보장이 아닙니다. 모든 후보의 visitStatus는 NOT_VERIFIED입니다. 전화번호 등 불필요한 응답 필드는 수집하지 않습니다.
- Adapter는 원본 요청/인증 헤더/응답 본문/예외 메시지를 로그나 실패 결과에 기록하지 않습니다. 운영에서도 JDK HttpClient의 헤더/전체 디버그 로깅을 활성화하지 않아야 합니다.
- 실제 검색 Adapter는 독립 Bean으로만 제공됩니다. 기존 여행 계획 API, Android, NaviHelper 및 자동차 경로/귀가 엔진에는 연결하지 않았습니다.

## 검증 범위

가짜 장소·좌표·ETA는 테스트 코드에만 존재합니다. Adapter 테스트의 인증 문자열은 실행 시 임의 생성하며 실제 카카오 키가 아닙니다. 로컬 HTTP 모의 서버에서 UTF-8 요청/인증 방식, 좌표/주소 매핑, 빈 결과·장애 분리, 타임아웃/응답 크기/리다이렉트 제한, 오류·로그의 인증값 비노출을 검증합니다. 기존 모델·UNKNOWN 유지·여행 API/Health 회귀 테스트도 수행합니다. **공식 카카오 API의 인증된 응답은 아직 검증하지 않았으므로 실제 장소 검색 성공이라고 보고하지 않습니다.**
기존 여행 일정 API와 Health API 회귀 테스트를 함께 실행합니다. 기존 API 시연 데이터, 귀가시간 엔진, Android/NaviHelper/Gradle/HTML은 변경하지 않습니다.

## 자동차 경로 Adapter 구현 및 제한 (2026-11-03)

- 운영 GET URL은 `https://apis-navi.kakaomobility.com/v1/directions`로 고정합니다. origin/destination은 경도,위도이며 `priority=RECOMMEND&summary=true`만 추가합니다. 경유지·대안 경로는 요청하지 않습니다.
- 인증 값은 서버 프로세스 환경변수 `TRIPBACK_KAKAO_MOBILITY_REST_API_KEY`에서만 읽고 Authorization 헤더에만 넣습니다. 키 조회와 HTTP 요청은 Bean 생성 시 수행하지 않습니다. 사용자 정의 RoadRouteProvider Bean이 있으면 기본 등록은 생략합니다.
- 연결 제한 3초, 요청과 전체 본문 수신 제한 5초, 본문 최대 1MiB, 리다이렉트 차단, JSON/identity 응답 검사 및 안전한 오류 분류는 장소 Adapter와 같은 원칙입니다. 자동 재시도는 없습니다. 테스트용 생성자는 공식 HTTPS 또는 루프백 HTTP만 허용합니다. JDK HttpClient 헤더/전체 디버그 로깅은 비활성 상태로 사용해야 합니다.
- routes는 정확히 한 개의 객체이어야 하며 result_code는 정수여야 합니다. 공식 일반 길찾기 코드 `1`, `101`~`107`은 NO_ROUTE입니다. 알 수 없는 코드와 다중 출발지/목적지 전용 코드는 INVALID_DATA로 실패합니다. 코드 표: [공식 레퍼런스](https://developers.kakaomobility.com/guide/navi-api/reference.html).
- result_code=0이면 summary 객체와 duration을 검사합니다. duration 누락/null은 ETA_MISSING/MISSING_ETA이고, summary 자체 누락/잘못된 구조는 INVALID_DATA입니다. duration은 0 이상의 long 범위 정수 초만 허용합니다. 문자열·소수·음수·범위 초과·비유한 숫자, 중복 JSON 키와 후행 토큰은 실패합니다. 거리 필드는 추가하지 않았습니다.
- FOUND일 때만 ProviderData를 부여합니다. 고정 공식 HTTPS 응답이 검증을 통과한 경우에만 VERIFIED_LIVE/VALIDATED_PROVIDER_DATA입니다. 루프백 모의 응답은 DEMO_SIMULATED/UNVERIFIED입니다. 실제 공식 응답의 성공 여부는 별도 로컬 PowerShell Live 검증 단계에서 확인해야 합니다.
- 요청 RoadRouteQuery를 그대로 결과에 유지합니다. 응답의 도로 스냅 좌표를 원래 좌표로 바꾸어 넣지 않습니다. 원본 응답 본문·인증 헤더·키·예외 메시지를 실패 결과나 로그로 전달하지 않습니다.
- 로컬 자동 테스트는 요청 좌표 순서·가짜 인증·미설정·경로 없음·ETA 누락/잘못된 숫자·HTTP 오류·연결 실패·헤더/청크 본문 타임아웃·리다이렉트·응답 크기·출처·RouteLegMapper 올림/UNKNOWN 보존을 검사합니다. 실제 키나 공식 인증 호출은 필요하지 않습니다.
- ETA는 현재 출발 조건의 추정치이며 미래 여행 시각 예측 또는 실제 귀가 보장이 아닙니다. TripPlanService/REST API, Android/NaviHelper, AI, 귀가시간 엔진과 왕복 N+1구간 오케스트레이션에는 연결하지 않았습니다. 캐시·미래 출발·차종/유종별 입력·거리 반환 확장은 별도 작업입니다.

### 이번 자동 검증 결과

- Java 25.0.3에서 backend의 `.\gradlew.bat test bootJar` 성공.
- 총 104건: 성공 103, 실패 0, 오류 0, SKIPPED 1. 신규 자동차 Adapter 15건 및 Spring 설정 2건 모두 성공.
- SKIPPED는 인증 환경변수를 전달하지 않은 기존 KakaoPlaceSearchLiveTests 1건입니다. 자동차/장소 공식 인증 API는 이번 실행에서 호출하지 않았습니다.
- `git diff --check` 통과. 실행 JAR 생성은 확인했지만 배포 실행 및 실제 자동차 ETA는 검증하지 않았습니다.
