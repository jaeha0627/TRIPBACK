# Android 추천 HTTP 연동 검증 (2026-11-04)

작업 브랜치: `feature/android-ai-recommendation-ui`. 새 브랜치/worktree, commit/push/PR 없음.
기존 미커밋 UI 및 원본 백엔드의 미커밋·미추적 파일을 보존했다.
원본 백엔드는 읽기 전용으로 참고했고, 기존 실행 JAR만 실행했다. 백엔드 Gradle은 실행하지 않았다.

## 구현 및 변경 파일

`android/TripbackApp/` 기준:

- `app/src/main/java/com/tripback/app/trip/recommendation/HttpRecommendationRepository.kt`: 신규 HTTP 전송, 오류 분류, 응답 검증, 크기/시간 제한, 취소.
- `app/src/main/java/com/tripback/app/trip/recommendation/RecommendationRepository.kt`: 인터페이스 설명 갱신. 기존 Fake 구현 보존.
- `app/src/main/java/com/tripback/app/trip/recommendation/RecommendationSession.kt`: HTTP/연결/타임아웃 오류 구분, 취소 문구 정정. 이전 결과와 요청 세대 검증 보존.
- `app/src/main/java/com/tripback/app/trip/recommendation/RecommendationScreens.kt`: HTTP 상태 배너, Fake 시나리오 숨김, 서버 호출 통계·조건·제한·실제 dataModes 표시.
- `app/src/main/java/com/tripback/app/trip/TripPlannerScreen.kt`: Debug 전용 `AI 추천 HTTP (개발)` 진입점. 기존 Fake 메뉴 유지.
- `app/src/debug/res/xml/network_security_config.xml`: Debug `127.0.0.1` cleartext 허용 추가. 기존 DEMO용 `10.0.2.2` 유지.
- `app/build.gradle.kts`: BuildConfig 생성 활성화.
- `app/src/test/java/com/tripback/app/trip/recommendation/HttpRecommendationTests.kt`: 신규 모의 HTTP 테스트 15건.
- `app/src/androidTest/java/com/tripback/app/RecommendationHttpLoopbackTest.kt`: 키 없는 로컬 백엔드 전용 opt-in 계측 테스트.
- `android/AI_RECOMMENDATION_UI.md`: 기존 시연 문서에 이번 연동 문서 링크 추가.
- 이 문서 `android/AI_RECOMMENDATION_HTTP.md`.

기존 RecommendationModels/RecommendationJson/테스트 fixture는 수정하지 않았다.
기존 NaviHelper, DEMO plan API, 왕복 검증 코드와 prototype은 수정하지 않았다.

## 계약과 Repository

FakeRecommendationRepository와 HttpRecommendationRepository는 별도 클래스다.
공통 RecommendationRepository 인터페이스를 유지하며 HTTP는 FakeScenario를 사용하지 않는다.
HTTP 실패 시 Fake로 대체하지 않는다. 기존 Fake 메뉴의 기본 동작은 동일하다.

원본 Java RecommendationRequest.decode, RecommendationResponse, TripRecommendationController,
TripRecommendationService와 TripValidationService를 확인했다. 실제 wire 계약의 선택 조건은
`preferences` 객체가 아닌 루트 필드다. 기존 Kotlin 직렬화는 이 계약과 일치한다.
요청에는 origin/targetAreaKeyword/targetCenter/departAt/returnDeadline/safetyBufferMin/
companionType/interests/vehicleType/maximumOneWayMinutes 및 지정한 선택 조건과 mustVisitPlaceIds가 포함된다.
좌표는 명시된 테스트 입력이며 GPS 위치 인증을 의미하지 않는다. 인증키는 읽거나 저장하지 않는다.

RecommendationJson을 재사용해 recommendationStatus/aiProviderStatus/alternatives/rejectedAlternatives/
orderedVisits/validation/feasibility/dataModes/conditionStatus/limitations/calls 및 후보·검색 보고를 파싱한다.
서버 응답 파싱과 세션 반영 시 계약을 검증한다. FEASIBLE, 마감·안전 여유, 첫/마지막 편도 ETA를
통과한 alternatives만 선택할 수 있다. UNKNOWN/INFEASIBLE/null ETA는 귀가 가능으로 승격하지 않는다.
DEMO_SIMULATED는 계속 시연 출처이고 AI 설명은 미검증이다. 실제 내비 출발은 비활성 상태다.

## 개발 연결과 보안

```powershell
adb -s emulator-5554 reverse tcp:8080 tcp:8080
adb -s emulator-5554 reverse --list
```

Debug HTTP 주소: `http://127.0.0.1:8080/api/v1/trips/recommend`.
기존 `10.0.2.2` DEMO 주소를 추천 주소로 재사용하지 않는다.
서버는 `--server.address=127.0.0.1`로 실행한다. 기존 Controller의 getRemoteAddr loopback 제한은 그대로다.
Release에서는 HTTP Repository가 HTTP 주소를 거절한다. main 네트워크 설정의 cleartext 차단을 유지하며
HTTP 메뉴는 Debug에만 표시한다. 운영 endpoint/인증/rate limit은 이번에 구성하지 않았다.

연결 5초, 읽기 190초, 전체 195초 제한이다. 서버 180초 처리시간에 응답 여유를 둔다.
응답 최대 256KiB, 요청 최대 16KiB, UTF-8/JSON Content-Type 검증을 적용한다.
리다이렉트와 애플리케이션 재시도는 없다. HTTP 오류 본문은 사용자나 로그로 노출하지 않는다.
세션과 Repository에서 중복 요청을 차단하며 취소 시 연결 해제를 요청한다.
요청 세대가 달라진 늦은 응답은 반영하지 않고 마지막 검증 결과를 보존한다.
클라이언트 취소/타임아웃은 서버 작업 취소나 공급자 과금 방지를 보장하지 않는다.

## 실제 유료 API 없는 검증

모의 서버는 JDK HttpServer를 127.0.0.1의 임의 포트에 바인딩했다.
15개 신규 테스트: POST/flat JSON/모든 선택 조건/필수 ID/원격 주소, 2안, 1안, 0안,
AI 미설정, UNKNOWN/null ETA, INFEASIBLE, 시연 출처, HTTP 400/403/404/413/415/500/502/302,
잘못된 JSON·구조·크기·Content-Type, 연결 실패, 읽기 타임아웃, 전체 타임아웃,
취소/중복/늦은 응답, 비loopback HTTP·URL 인증정보 거절.
짧은 테스트 전용 제한으로 타임아웃 경로를 확인했으며 실제 180초 작업은 실행하지 않았다.

로컬 백엔드 실행은 별도 PowerShell 프로세스에서 OPENAI_API_KEY, TRIPBACK_AI_MODEL,
TRIPBACK_KAKAO_LOCAL_REST_API_KEY, TRIPBACK_KAKAO_MOBILITY_REST_API_KEY와 Java 옵션 변수를 제거했다.
키의 기존 값을 출력하거나 파일에 기록하지 않았다.
모델 미설정이면 service가 후보 수집 전에 반환함을 소스로 확인했다.

기존 backend/build/libs/tripback-backend-0.0.1-SNAPSHOT.jar를 다음 두 설정으로 실행했다.

1. `--TRIPBACK_ENABLE_AI_RECOMMENDATION_API=false`: 실제 Android POST가 HTTP 404.
2. `--TRIPBACK_ENABLE_AI_RECOMMENDATION_API=true`, 모델/키 없음: 실제 Android POST가 HTTP 200,
   AI_UNAVAILABLE / NOT_CONFIGURED / NOT_CALLED, calls=(0,0,0).

두 경우 모두 API 34 에뮬레이터의 실제 HttpRecommendationRepository를 adb reverse로 실행했다.
각 계측 실행은 1건 성공했다. 사용한 서버는 검증 후 종료했다.
OpenAI·카카오 실제 HTTP 요청은 발생시키지 않았다.

Tomcat access log를 켜서 프록시 헤더가 아닌 실제 원격 주소를 확인했다:

```text
127.0.0.1 POST /api/v1/trips/recommend 404
127.0.0.1 POST /api/v1/trips/recommend 200
```

재검증 시 임시 로그 경로를 지정하고 서버에 다음 옵션을 추가한다:
`--server.tomcat.accesslog.enabled=true --server.tomcat.accesslog.directory=<임시절대경로>`
`--server.tomcat.accesslog.pattern="%a %m %U %s"`.
로그에 요청 본문·키를 포함하지 않는다. 종료 후 access log의 `%a`를 확인한다.

opt-in 테스트는 자동으로 공급자를 활성화하지 않는다. 키 없는 서버를 명시적으로 준비한 뒤 실행한다:

```powershell
adb -s emulator-5554 shell am instrument -w -e class com.tripback.app.RecommendationHttpLoopbackTest -e keylessBackendExpected 404 com.tripback.app.test/androidx.test.runner.AndroidJUnitRunner
# 서버를 키/모델 없이 API만 활성화한 상태로 다시 시작한 뒤:
adb -s emulator-5554 shell am instrument -w -e class com.tripback.app.RecommendationHttpLoopbackTest -e keylessBackendExpected AI_UNAVAILABLE com.tripback.app.test/androidx.test.runner.AndroidJUnitRunner
```

## 빌드와 회귀 결과

실행 명령 (Android Studio JBR 25.0.3, 로컬 SDK/기존 캐시 사용):

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest compileReleaseKotlin --offline
.\gradlew.bat processReleaseMainManifest --offline
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e class com.tripback.app.ExampleInstrumentedTest,com.tripback.app.RecommendationFlowTest com.tripback.app.test/androidx.test.runner.AndroidJUnitRunner
git diff --check
```

- Kotlin Debug/Release 컴파일 성공. 기존 LocalLifecycleOwner deprecation 경고만 남는다.
- 단위 테스트 88건 성공: 기존 73 + HTTP 15, 실패/오류/skipped 0.
- Debug APK와 계측 APK 빌드 성공.
- 기존 Pleos API 34 계측 테스트 6건 성공. 추가 loopback 테스트는 404/미설정 각각 1건 성공.
- Release manifest 처리 성공. main 네트워크 설정의 cleartext 차단 유지.
- 기존 단위/계측 회귀 테스트 통과. DEMO·왕복 검증·NaviHelper 코드는 보존했다.
- 실제 NaviHelper/Vehicle 주행 통합, Release APK 실행, 장시간 공급자 작업, 실제 유료 통합은 테스트하지 않았다.
- 최종 APK: android/TripbackApp/app/build/outputs/apk/debug/app-debug.apk.

## 실제 API 통합 전 남은 작업

사용자가 별도 승인한 서버 환경에서 실제 장소/좌표/계정 권한과 공급자 모델 설정을 준비하고
유료 호출 범위를 명시해야 한다. 키는 Android에 넣지 않는다.
실제 공급자 응답/지연/취소 후 서버 처리, 장소 ID 및 20km 범위, 실제 ETA와 조건 충족을 검증해야 한다.
운영 배포는 HTTPS endpoint, 사용자 인증, rate limit 및 비용 제한을 별도로 준비해야 한다.
현재 위치·CarUxRestrictions·Pleos POI 매핑과 내비 전달은 후속 검증이며 이번 변경은 활성화하지 않는다.
