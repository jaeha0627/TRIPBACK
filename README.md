# Tripback

AI 기반 왕복 여행 일정 추천 및 체류·귀가 관리 앱입니다. Pleos Connect에서 여행 조건 입력 → 코스 비교 → 상세 확인 → 수동 여행·체류·귀가 시연을 제공합니다.

## 제출 APK

- **[서명된 Release APK 다운로드](apk/Tripback_Tripback_release.apk)**
- 패키지: `com.tripback.app` · versionCode `1` · versionName `1.0`
- 크기: **6,596,717 bytes**
- SHA-256: `95b641f6a0724d23e0cd2b9a796b667f30d2f5e3a5707bf7960b6c3e1eec0da2`
- 기존 소유자 인증서와 일치하는 V2 서명, 서명자 1명. 2026-11-10 **Pleos Connect API 34 에뮬레이터 설치·실행 및 수동 시연 완료**.

설치 대상과 활성 사용자를 먼저 확인합니다. 기존 앱과 서명이 같을 때만 아래 명령으로 업데이트합니다. 서명이 다른 Debug 앱은 삭제하지 말고 별도 Pleos AVD를 사용하세요.

```powershell
adb -s <기기> shell am get-current-user
adb -s <기기> install -r --user <사용자ID> apk/Tripback_Tripback_release.apk
adb -s <기기> shell am start --user <사용자ID> -n com.tripback.app/.MainActivity
```

## 심사용 시연 범위

| 구분 | 제공 범위 |
|---|---|
| Release | 서버 없이 Fake 추천 2안, 일정 상세·선택, 수동 여행 시작·이동·도착, 체류 타이머, 단축·방문지 제외 변경안 승인·거절, 수동 귀가 완료·기기 내 시연 기록 저장 |
| Debug 개발 검증 | Backend를 통한 실제 OpenAI·카카오 추천 연동 경로 및 재계획 `PREVIEW_ONLY`. 별도 서버·공급자 설정 필요. HTTP·Fixture·SDK 개발 메뉴는 Release에 비노출 |
| Figma UI | 홈, 추천 결과, 일정 상세, 체류 화면 반영. 재계획 Preview 디자인은 Debug 전용 |
| 제한 | 실제 GPS·자동 도착 감지·차량 상태 기반 진행은 미구현/미연동. 실제 NaviHelper 목적지 경로 안내는 미검증이며 요청 차단 유지. 음성 입력 미지원 |

앱의 **AI 추천 화면 시연** → 조건 입력·주차 시연 확인 → **AI 일정 시연 만들기** → 상세 → 코스 선택 → **여행 진행 수동 시연**으로 진행합니다. 체류 변경은 사용자 승인 전 적용하지 않습니다. 종료 후 **시연 기록을 이 기기에 저장**을 누릅니다. 홈의 지난 여행 영역은 미연결이며, 저장 기록 확인은 시연 종료 화면에서 제공합니다.

Fake 장소·ETA·수동 이벤트는 `DEMO_SIMULATED`입니다. 실제 귀가 가능 여부를 보장하지 않으며, 실제 이동 미확인이나 경로 변경 재검증 불가 시 `UNKNOWN`을 유지합니다. AI 추천 이유도 미검증임을 표시합니다. `PREVIEW_ONLY`는 원본 일정을 적용하지 않는 읽기 전용 개발 검증입니다.

CarUxRestrictions 미확인 또는 제한 중에는 입력·편집이 차단됩니다. 일반 휴대전화 에뮬레이터에서는 차량 UX 상태를 확인하지 못해 차단될 수 있습니다.

## 프로젝트 구성과 실행 환경

| 경로 | 구성·환경 |
|---|---|
| `android/TripbackApp` | Kotlin·Jetpack Compose, Android Studio/JBR 25, compile/target SDK 37, minSdk 28, Build Tools 36.0.0, Gradle Wrapper 9.6.0, NaviHelper 3.0.5 |
| `backend` | Java 25, Spring Boot 4.1.2, Gradle Wrapper 9.8.2. 구조화 AI 후보와 결정적 귀가 조건 검증 |
| `docs` / `prototype` | API·SDK·개발 문서 / 가상 데이터 기반 HTML 시안 |

Android는 `android/TripbackApp`을 Android Studio에서 엽니다. `JAVA_HOME`은 JBR, `ANDROID_HOME`은 설치 SDK 경로로 설정하고 기존 Wrapper를 사용합니다. 최초 빌드는 공식 의존성 다운로드가 필요할 수 있습니다.

```powershell
cd android/TripbackApp
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:compileReleaseKotlin :app:lintRelease --no-configuration-cache
```

위 Release 빌드는 소유자 서명 설정 없이는 제출용 서명을 제공하지 않습니다. 제출 시에는 위에 링크한 검증 완료 APK를 사용하세요.

Backend는 통합 저장소의 `backend`에서 실행합니다.

```powershell
cd backend
.\gradlew.bat bootRun
# 별도 터미널의 로컬 상태 확인
Invoke-RestMethod http://127.0.0.1:8080/api/v1/health
```

실제 OpenAI·카카오 공급자는 기본 비활성/미설정이며, API 키는 **Android와 GitHub에 포함하지 않고 Backend 실행 환경에 별도 설정**합니다. 실제 공급자 활성화·Live 테스트는 비용이 발생할 수 있는 별도 작업입니다. Debug HTTP 연결은 localhost 개발 서버와 ADB 연결 설정이 필요하며 Release의 제품용 실제 AI 추천 UI는 제공하지 않습니다.
설정은 [Backend 안내](backend/README.md)와 [AI 추천 문서](docs/ai-trip-recommendations.md)를 참고하세요.

## 통합·검증 결과 — 2026-11-10

통합 브랜치: `submission/2026-11-14` · 소스 통합 기준 커밋: `289493e19f6df654137fde3de01b7571d8f3d562`

- Android 원본: `446185d621f449c3df674b0b450fdbe23e3afc22`
- Backend 원본: `ca9966c97d11536fc39ac261fc67ab8be929eaff`

| 검사 | 결과 |
|---|---|
| Android 단위 테스트 | 236건 통과, 실패·Skipped 0건 |
| Backend 테스트 | 308건 통과, 실패 0건, 카카오 Live 테스트 2건 Skipped (`LIVE_TASK_NOT_AUTHORIZED`) |
| 빌드 | Debug·Release APK, Release Kotlin 컴파일, Backend Java 컴파일·실행 가능한 Spring Boot JAR 성공 |
| R8·Lint | R8 성공, 최종 signed APK DEX 불법 superclass 접근 0건. Lint 오류 0건·경고 15건 |
| Release 시연 | Pleos API 34·사용자 10에서 서명 일치 확인 후 업데이트 설치, 홈·2안·상세·체류·변경 승인/거절·귀가 완료·로컬 저장 확인 |
| 데이터 보존·오류 | 기존 로컬 기록 1건 유지 후 새 시연 기록 저장으로 2건 확인. Tripback 치명적 오류 미발견 |

자동 검증은 공급자 인증정보를 상속하지 않는 격리 환경에서 기존 캐시·오프라인 모드로 수행했습니다. 실제 OpenAI·카카오 및 NaviHelper 경로 요청은 실행하지 않았습니다. 별도 Pleos 시스템 앱 `ai.pleos.gleoai.model.provider`의 Firebase 오류는 관찰됐으나 Tripback 시연은 완료됐습니다. 이 결과는 실제 차량 주행·실제 공급자 전체 연동 검증을 의미하지 않습니다.

## 보안·참고 문서

서명키·비밀번호·API 키·`.env`·`local.properties`·개인정보·R8 mapping은 제출 파일에 포함하지 않습니다. APK의 서명 인증서는 공개 검증 정보이며 개인키는 포함하지 않습니다. 비공개 GitHub 저장소를 제출한다면 소유자가 심사 계정의 접근 권한을 별도로 확인해야 합니다.

- [API 계약](docs/api-contract.md)
- [SDK 가용성 검증표](docs/sdk-capability-matrix.md)
- [여행 진행·체류·귀가 개발 기록](android/TRIP_PROGRESS.md)
- [이전 MVP 검증 이력](android/MVP_PRECHECK.md) — 과거 산출물 기준이며 현재 제출 APK 상태는 본 문서를 따릅니다.
