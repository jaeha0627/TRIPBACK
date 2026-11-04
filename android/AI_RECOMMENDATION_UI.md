# Android AI 추천 화면 1차 구현

작업 환경: feature/android-ai-recommendation-ui, 기준 f09a8b205c4d855c551f79203ec2b926e7262e50.
원본 C:/dev/TRIPBACK의 미커밋 추천 백엔드 DTO는 읽기 전용으로 참조했습니다.

## 실행과 범위

앱의 기존 메뉴에서 **AI 추천 화면 시연**을 선택합니다. 기존 DEMO, 왕복 검증, NaviHelper 테스트 메뉴는 유지합니다.
이 진입은 FakeRecommendationRepository만 사용하며 HTTP 구현, API 키 조회, 내비게이션 전송 경로가 없습니다.
입력은 테스트용 좌표임을 표시하고 수동 주차 확인을 요구합니다. 이 확인은 CarUxRestrictions나 실제 차량 상태 확인이 아닙니다.

구현: 조건 입력 → 생성 로딩 → 0~2안 결과 → 상세 → 로컬 선택 및 출발 준비.
출발 준비의 실제 내비 버튼은 비활성입니다. 여행 시작·서버 저장·SMS·차량·에너지·음성 기능은 구현하지 않았습니다.
요청 중복을 차단하고 취소·화면 이탈·ON_STOP 후 늦은 응답을 무시합니다.
폼, 마지막 응답, 선택과 상세/준비 화면을 SavedInstanceState로 복구합니다. 중단된 요청은 자동 재실행하지 않습니다.
잘못된 응답은 원문 없이 오류를 표시하고 이전 결과를 유지합니다. 이전 결과는 원래 요청과 함께 복원합니다.

## 디자인 근거

C:/dev/Tripback-Figma-Refs/Tripback_Figma_23screens/SCREEN_INDEX.md 및 PNG 23장을 확인했습니다.
주요 참조는 입력(기본/_03), 로딩(_04), 비교(_05), 상세(_06/_07)입니다.
HTML v5의 조건·선택·왕복 일정 흐름과 대조했습니다.
흰 바탕, 남색 주요 동작, 청록 강조, 사각 카드, 넓은 화면의 2열 비교·상세 일정 영역을 반영했습니다.
계기판은 차량 시스템 UI이므로 복제하지 않았습니다. 제공되지 않은 출발 준비 전용 시안은 상세 화면의 카드 구성을 따랐습니다.
0/1안·오류·Fake 고지·테스트 설정은 서버 계약과 이번 범위에 필요한 추가 상태입니다.
알찬형·여유형을 임의 부여하지 않으며 백엔드 제목을 표시합니다. 픽셀 단위 일치를 주장하지 않습니다.

## 계약과 Fake

RecommendationRequest는 추천 API의 평면 필드·선택 선호·provider ID를 유지합니다.
RecommendationResponse는 candidates/searches/excludedCandidates/conditionStatus/limitations/calls 및 대안의 전체 validation을 파싱합니다.
기존 TripValidationJson 검증을 재사용해 요청 ID/순서, N+1 구간, 출처와 null/UNKNOWN 일관성을 검사합니다.
성공 alternatives는 FEASIBLE 및 안전 여유·첫/마지막 구간 제한을 확인합니다.
AI 추천 이유는 미검증으로 표시합니다. 시연 호출 통계는 실제 호출 0회라는 설명과 함께 표시합니다.

Fake 시나리오: 2안/1안/0안, INFEASIBLE, UNKNOWN과 null ETA·귀가/여유,
AI 미설정, 모델 오류, 타임아웃, 잘못된 JSON, 출처 표시 안내.
모든 가상 장소/ETA의 출처는 DEMO_SIMULATED입니다.
LIVE/CACHED는 메타데이터 단위 테스트와 UI 범례로만 구분하며 가상 코스를 실제 데이터로 승격하지 않습니다.
src/test/resources/recommendation의 JSON 6개는 요청과 2/1/0/UNKNOWN/미설정 응답의 독립 계약 fixture입니다.

## 수행한 검증

Android Studio 내장 JDK 25.0.3, 기존 의존성 캐시를 사용했습니다.

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest --offline
```

- Kotlin 컴파일, Debug APK·테스트 APK 생성 성공.
- 단위 테스트 73건 성공: 기존 51 + 신규 추천 22, 실패/오류/SKIPPED 0.
- 연결 기기의 API 34 및 모델 Connect on x86_64 emulator를 확인했습니다.
- connectedDebugAndroidTest --offline은 UTP gradle-work-action:32.4.1 미캐시로 시작하지 못했습니다.
- 빌드된 두 APK를 adb install -r로 설치하고 아래 방식으로 직접 실행해 전체 instrumentation 6건(기존 1 + 신규 5) 성공.
- 이후 캡처 저장 위치를 내부 캐시로 바꾸고 신규 UI 5건을 재확인했습니다.
- 첫 5개 화면 전환, 로컬 선택/복원, 0/1안, UNKNOWN/실패 표시, 잘못된 JSON, 로딩 취소를 검증했습니다.
- git diff --check 및 미추적 소스의 공백 검사 확인.
- 실제 OpenAI·카카오 호출, 실제 경로 안내는 실행하지 않았습니다.

```powershell
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w com.tripback.app.test/androidx.test.runner.AndroidJUnitRunner
```

Debug APK: android/TripbackApp/app/build/outputs/apk/debug/app-debug.apk.
UI 테스트는 앱 캐시에 추천 5개 화면의 PNG를 저장합니다. build 산출물은 Git에 포함하지 않습니다.

## 실제 연결 전

HTTP Repository와 180초 서버 작업에 맞는 취소·타임아웃, localhost 제한을 보존하는 개발 연결을 준비해야 합니다.
현재 추천 backend는 인증·rate limit 없는 loopback 개발용입니다.
실제 현재 위치 및 UX 제한, 카카오 ID와 Pleos POI 식별·전달 가능성, 실제 SDK 콜백은 별도 검증이 필요합니다.
Fake FEASIBLE은 실제 귀가·방문 가능성이나 내비 전송 허가를 뜻하지 않습니다.

## 후속 HTTP 연동

위 내용은 최초 Fake UI 구현 기록입니다. HTTP Repository, Debug 연결과 최신 검증 결과는 [AI_RECOMMENDATION_HTTP.md](AI_RECOMMENDATION_HTTP.md)를 확인하세요. 기존 Fake 메뉴는 유지됩니다.
