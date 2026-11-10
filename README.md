# Tripback (트립백)

AI 기반 차량 연동형 왕복 드라이브 플래너 · Pleos Connect.
가는 길 → 방문지 체류 → 오는 길을 관리하고, 귀가시간이 부족하면 사용자 승인 후 시연 일정을 변경합니다.

## 최신 Release 검증 — 2026-11-07 반영

소유자가 확인하여 제공한 최신 검증 결과를 반영했다. 이번 문서 보완에서 서명·설치·앱 실행을 다시 수행한 것은 아니다.

| 항목 | 최신 확인 결과 |
|---|---|
| Release APK | 소유자 서명 성공 |
| 실행 환경 | Pleos API 34 에뮬레이터 설치·실행 성공 |
| R8 접근성 | DEX 불법 상속 관계 0건 |
| Release DEMO | 여행 진행·체류시간 변경·귀가·여행 종료·로컬 기록 저장 검증 완료 |
| 실제 NaviHelper | 목적지 경로 안내는 아직 미검증. 추천 장소 내비 시작 비활성 유지 |
| 실제 공급자 | 위 DEMO 결과는 실제 OpenAI·카카오·내비게이션 전체 연동 검증을 뜻하지 않음 |

서명된 APK와 대응 mapping·metadata는 `android/TripbackApp/app/release/`에 로컬 보존하며 소스 커밋에서 제외한다.
기존 unsigned APK 설치 실패와 R8 진단 기록은 당시 산출물에 대한 검증 이력으로 유지한다.

## 이전 검증 이력 — 2026-11-05

| 구분 | 당시 범위 |
|---|---|
| Android | Kotlin/Compose 추천 입력·2안·상세·선택·출발 준비, 수동 여행·체류·귀가·변경 승인·시연 기록 |
| 실제 SDK | Pleos API 34에서 NaviHelper 3.0.5 서비스 연결·권한, CarUxRestrictions 초기 조회·리스너 등록 확인 |
| 실제 내비 | 추천 장소 경로 요청 비활성. 카카오→Pleos POI 매핑/좌표 단독 요청 규약 미확인 |
| DEMO | FakeRepository와 수동 이벤트는 DEMO_SIMULATED. GPS/실제 도착/문자/차량 제어로 표시하지 않음 |
| 귀가 계산 | 기존 ETA 가정 계산과 실제 판정을 구분. 실제 이동 미확인·경로 변경 재검증 불가는 UNKNOWN |
| 테스트 | 단위 114 성공. 계측/연속 시나리오와 정확한 결과는 아래 사전 검증 보고 참고 |
| Release | Lint·R8 포함 빌드 성공, unsigned. 서명 검증·설치 실패이므로 제출 가능한 Release APK는 아직 없음 |

현재 Android 기능은 `feature/android-ai-recommendation-ui`, AI 추천 백엔드는 별도
`feature/backend-ai-trip-recommendations`에 있습니다. 두 브랜치는 아직 통합하지 않았습니다.
이 Android 브랜치의 backend 폴더만으로 최신 AI 추천 서버를 재현할 수 있다고 주장하지 않습니다.
실제 공급자 성공에 관한 이전 보고를 이번 실행의 API 검증 결과로 간주하지 않습니다.

## Android 실행 — 서버 없이 시연

필요 환경: Android Studio/JBR 25, 설치 Android SDK compileSdk 37(minSdk 28), SDK Build Tools,
Pleos Connect API 34 AVD, NaviHelper 3.0.5 의존성 접근. `android.car`는 설치 SDK의 선택 플랫폼 라이브러리입니다.
Gradle Wrapper와 고정 의존성을 사용합니다. 처음 빌드는 공식 Android/Gradle 의존성 다운로드가 필요할 수 있습니다.
키 파일은 Android에 넣지 않습니다.

```powershell
cd android/TripbackApp
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease compileReleaseKotlin --no-configuration-cache
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start --user 10 -n com.tripback.app/.MainActivity
```

위 user 10은 이번 AVD의 활성 사용자입니다. 다른 환경에서는 `adb shell am get-current-user` 결과를 사용합니다.
JAVA_HOME은 Android Studio JBR, ANDROID_HOME은 설치 SDK 경로로 설정합니다.
공식 Pleos 의존성 설정은 프로젝트의 기존 저장소 설정을 사용하며 인증정보를 문서/소스에 복사하지 않습니다.

앱에서 **AI 추천 화면 시연** → 정차 확인과 조건 입력 → 생성 → 2안 상세 → 코스 선택 → 출발 준비 →
**여행 진행 수동 시연 · DEMO_SIMULATED**를 선택합니다.
출발/이동/도착/체류 종료/귀가 완료는 수동 버튼입니다. 실제 내비 안내는 시작하지 않습니다.
CarUxRestrictions 미확인/제한 중에는 입력·편집 화면을 잠급니다. 비차량 일반 에뮬레이터에서는 차단될 수 있습니다.

체류 초과와 귀가 부족을 빠르게 재현하는 연속 계측 시나리오는 테스트 전용 시계를 사용합니다.
제품 시계를 조작하거나 실제로 8시간 주행했다는 뜻이 아닙니다. 제품에는 자동 시간 점프 기능을 추가하지 않았습니다.

```powershell
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e class com.tripback.app.SubmissionScenarioTest com.tripback.app.test/androidx.test.runner.AndroidJUnitRunner
```

## Backend 실행 — 별도 브랜치에서만

별도 작업 경로의 `feature/backend-ai-trip-recommendations`에서 Java 25/Spring Boot 4.1.2를 사용합니다.
이 작업에서는 서버를 실행하거나 공급자를 호출하지 않았습니다.

```powershell
cd backend
.\gradlew.bat bootRun
# 별도 터미널에서 로컬 상태 확인:
Invoke-RestMethod http://127.0.0.1:8080/api/v1/health
```

AI 추천·장소·경로 API는 기본 비활성/미설정 상태로 시작합니다. 실제 공급자를 켜는 것은 별도 명시적 작업입니다.
API 계약과 Backend 브랜치의 `docs/ai-trip-recommendations.md`를 확인하세요.
Debug의 HTTP 추천 연결은 adb reverse와 localhost 개발 서버가 필요하며 Release에서는 비노출/HTTP 차단입니다.
Release에서 실제 AI 추천을 사용하는 제품용 HTTPS 연결 UI는 아직 없습니다.

## APK와 제출 — 2026-11-05 이력

이 절의 미준비·실패 상태는 당시 unsigned 산출물 기준이다. 현재 서명 Release 상태는 위 최신 검증을 따른다.

- 개발 시연용 Debug: `android/TripbackApp/app/build/outputs/apk/debug/app-debug.apk`.
- 서명되지 않은 Release 빌드 결과: `android/TripbackApp/app/build/outputs/apk/release/app-release-unsigned.apk`.
- **공식 제출용 signed Release APK: 미준비.** unsigned/Debug 파일을 제출용 Release로 이름만 바꾸지 않습니다.
- 공식 제출 위치·이름: `apk/팀명_Tripback_release.apk` (실제 팀명 확정 및 서명·설치·실행 검증 후 배치).
- 이번에는 apk/ 배치, GitHub 업로드, 서명키 생성/사용, commit/push/PR/병합을 수행하지 않았습니다.

공식 안내는 실행 가능한 release APK, README의 실행 방법·환경·APK 경로, 비공개 저장소 심사 접근 확인을 요구합니다.
1차 시연 영상에는 목업·프로토타입·화면 녹화도 인정됩니다.
[공식 제출 안내](https://appdevchallenge.auto-hmg.io/guide/submission).

## 개발 문서

- [제출용 MVP 사전 검증·서명 작업·캡처](android/MVP_PRECHECK.md)
- [여행 진행·체류·귀가 보고](android/TRIP_PROGRESS.md)
- [SDK 가용성 검증표](docs/sdk-capability-matrix.md)
- [승인 대기 중인 NaviHelper Debug 경로 검증 계획](docs/navi-debug-test-plan.md)
- [개발 계획](docs/Tripback_Development_Plan.md), [API 계약](docs/api-contract.md)
- [HTML v5 프로토타입](prototype/Tripback_차량화면_시안_v5.html) — 가상 데이터 기반, 원본 보존
- [Codex 작업 지침](AGENTS.md)

서명 지원서·연락처·API 키·환경 파일·keystore는 저장소에 넣지 않습니다.
제품 목표인 실제 왕복 안내와 현재 검증된 DEMO 범위를 구분합니다.
