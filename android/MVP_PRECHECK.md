# 제출용 MVP 통합·Release 사전 검증

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

이하 기존 본문은 당시 unsigned 빌드와 진단 결과를 기록한 이력이다. 재서명·전체 실행 확인이 필요하다는 당시 판정은 위 최신 결과와 구분한다.

2026-11-05. Android feature/android-ai-recommendation-ui, Backend feature/backend-ai-trip-recommendations.
양쪽 기존 미커밋 변경 보존. Backend 수정/실행 없음. 브랜치/worktree 생성, reset/clean,
commit/push/PR/main 병합 없음. 키 파일·기존 서명 인증정보를 읽거나 출력하지 않았다.

## 통합 범위

신규 SubmissionScenarioTest 한 건이 FakeRecommendationRepository를 명시적으로 사용하여
조건 입력 → 추천 2안 → 상세/선택 → 출발 준비(실제 내비 비활성) → 수동 출발/이동/도착 → 체류 시작 →
추천 세션 재생성 → 체류 초과/귀가 부족 → 변경안 검토 → 거절 → 다시 제안/승인 → 다음 방문지 →
귀가 준비/완료 → 요약 → 시연 기록 저장을 연속 실행한다.
테스트 전용 wall/monotonic 시계를 8시간 진행시켜 기다림 없이 초과/부족을 재현한다.
실제 기기 시간은 변경하지 않으며 제품 UI에 시간 점프 기능을 넣지 않았다.

기록에서 DEMO_SIMULATED, actualDrivingVerified=false, 승인 [false,true], 첫 체류 계획 1200초,
중복 없는 STAY_FINISHED 2건을 확인한다. 실제 경로/GPS/도착과 혼동하지 않는다.

## 발견 및 최소 수정

- SDK 테스트 메뉴가 Release에도 표시되는 코드가 있었다. Debug 조건으로 표시/진입을 제한했다. 복원된 왕복 검증 화면도 Release에서 진입하지 못하도록 제한했다.
- 개발용 Fake 시나리오 선택기도 BuildConfig.DEBUG일 때만 보인다. Release의 기본 2안 DEMO는 유지한다.
- 연속 시나리오에 테스트 시계를 주입할 수 있도록 RecommendationFlow/RecommendationSession의 기본값 있는 인자를 추가했다.
  재생성 복원에도 같은 시계를 사용한다. 제품 기본값은 기존 AndroidJourneyClock 그대로다.
- 선택 계획 손실, 승인 전 변조, 타이머 중복에 대한 제품 오류는 이번 시나리오에서 발견하지 못했다.
- 신규 테스트의 wildcard import 모호성과 두 방문지를 동시에 찾는 문자열 선택자 오류를 수정했다.
  첫 방문지에 대한 정확한 거절 검사를 유지했다.

변경 파일: README.md, RecommendationSession.kt, RecommendationScreens.kt, TripPlannerScreen.kt,
신규 SubmissionScenarioTest.kt, android/MVP_PRECHECK.md. 이전 진단·NavigationAdapter·여행 모델 및 Backend는 보존했다.

## Release 검증

| 항목 | 결과 |
|---|---|
| assembleRelease | Lint vital + R8 포함 성공. 검사 생략 없음 |
| Release Kotlin | 성공 |
| 서명 설정 | Gradle release signingConfig 없음. 기존 키 탐색·재사용/새 키 생성 안 함 |
| APK 서명 검증 | apksigner verify 실패: DOES NOT VERIFY / Missing META-INF/MANIFEST.MF |
| API 34 설치 | INSTALL_PARSE_FAILED_NO_CERTIFICATES로 실패. 기존 Debug 앱 삭제 안 함 |
| Release 실행·화면 전환 | 설치 불가로 미검증 |
| Debug 메뉴 | 소스에서 HTTP/왕복 검증/SDK/Fake 시나리오 개발 UI를 Debug 조건으로 제한. Release 실기동 비노출은 미검증 |
| Release DEBUG | 생성 BuildConfig.DEBUG=false, manifest debuggable 선언 없음(default false) |
| cleartext | 패키지 manifest usesCleartextTraffic=false, 실제 패키지 network_security_config base-config=false, Debug loopback 예외 없음 |
| 현재 이용 범위 | 코드 기준 기본 Fake 추천→수동 DEMO와 로컬 기록. 실제 AI HTTPS 진입/실제 내비 시작 없음. Release 런타임 검증과 구분 |

Unsigned APK: `android/TripbackApp/app/build/outputs/apk/release/app-release-unsigned.apk`.
이 파일은 제출용 실행 APK가 아니다. Debug 서명으로 Release를 만들거나 공식 제출 파일명으로 복사하지 않았다.

초기 --offline 빌드는 lint-gradle:32.4.1 캐시 누락으로 실패했다.
공식 Android 빌드 의존성 다운로드를 허용한 --no-configuration-cache 실행에서 성공했고,
이후 --offline --no-configuration-cache에서도 성공했다. OpenAI·카카오 API 호출은 없다.
Release 빌드의 androidx.graphics.path.so stripping 경고 및 기존 LocalLifecycleOwner deprecation 경고가 있었다.

### 패키지 내 키·개인정보 검사 범위

API 키 파일/환경 파일/keystore를 열지 않았다. 생성된 unsigned APK만 zip/dex 단위로 검사했다.
.env/local.properties/jks/keystore/pem 파일명 패턴 0, 알려진 OpenAI/AWS 키·개인키 헤더 패턴 0.
바이너리 전체의 이메일 모양 2건은 실제 DEX 문자열 경계를 읽어 확인하니 이메일 문자열은 0건이었다.
이 검사는 형식 기반 제한 검사이며 모든 공급자 키 형식·개인정보가 절대 없다는 보안 인증은 아니다.
서명 후에는 인증서 subject 메타데이터도 포함되므로 최종 제출 APK를 다시 검토해야 한다.
값/인증정보는 로그에 출력하지 않았다.

### 사용자가 직접 해야 할 서명 작업

[Android 공식 서명 가이드](https://developer.android.com/studio/publish/app-signing)에 따라:

1. 소유자가 관리할 release keystore/alias/password를 준비한다. 새 키가 필요하면 Android Studio의
   Build → Generate Signed Bundle/APK → APK → Create new를 직접 사용한다. 키/암호는 Git 밖에 보관한다.
2. 실제 서명 주체와 인증서 메타데이터를 확인한다. 개인 연락처나 불필요한 정보를 인증서에 넣지 않는다.
3. app 모듈과 release variant를 선택해 서명한다. Debug signingConfig를 대신 지정하지 않는다.
   현재 Gradle에 release signingConfig가 없으므로 GUI로 직접 서명하거나, 별도 로컬 보안 설정을 소유자가 마련해야 한다.
4. apksigner verify --verbose로 서명과 인증서 지문을 확인한다.
5. 기존 Debug와 서명이 다르면 그대로 업데이트할 수 없다. 기존 데이터를 보존하고 별도 테스트 AVD에서
   signed Release 설치·실행·전체 시연·메뉴 비노출·네트워크 정책을 검증한다. 이번에 기존 앱을 지우지 않았다.
6. 통과한 APK만 `apk/<팀명>_Tripback_release.apk`로 배치하고 파일 크기/SHA256/시험 환경을 기록한다.

## 공식 제출 요건 확인

[공모전 제출 안내](https://appdevchallenge.auto-hmg.io/guide/submission), 2026-11-05 확인:

- 1차 시연 영상·GitHub·APK는 선택 제출/가산점. 목업·프로토타입·화면 녹화 인정.
- GitHub에는 소스와 APK를 함께 제공. APK는 apk/ 폴더, `팀명_앱이름_release.apk`, 실행 가능한 release 버전.
- 용량 제한을 넘으면 GitHub Releases 페이지 주소 제출 가능.
- Private 저장소는 공지된 심사 계정의 접근을 마감 전에 확인해야 한다.
- README에는 앱 소개·실행 방법·테스트 환경/설정·APK 경로 또는 Release 링크가 필요하다.
- 2차 PT 전에는 실제 동작 가능한 앱 개발 완료가 요구된다.

README는 현재 기능/DEMO/실제 한계/두 브랜치 상태/실행/환경/unsigned APK 차단 상태로 갱신했다.
제출 이메일/서명 지원서/심사 계정 초대/업로드는 수행하지 않았다.

## 병합 전 확인

merge-base: f09a8b205c4d855c551f79203ec2b926e7262e50.
커밋 기준 Android 변경 경로 22, Backend 24, 교집합 0. 이는 경로 충돌 예비 점검이며 실제 merge 결과가 아니다.
현재 미커밋 Android 작업과 Backend RecommendationRejectionDiagnosticTests.java를 모두 리뷰/보존해야 한다.
두 브랜치가 합쳐진 트리에서 추천 DTO/localhost 접근 제한/출처/실패 코드를 대조하고 양쪽 회귀를 다시 실행한다.
현재 Android 브랜치 backend 폴더에는 최신 AI 추천 구현이 아직 없으므로 이 브랜치만 제출하면 통합 소스가 누락된다.
계약·README·SDK 표의 최신 현황을 통합 트리 기준으로 다시 확인한다. 이번에 merge/commit/push를 실행하지 않았다.

## 시연 영상용 화면

`android/TripbackApp/app/build/outputs/submission-evidence/`에 다음 연속 시나리오 캡처를 보관한다:

1. submission-01-input.png
2. submission-02-two-plans.png
3. submission-03-detail.png
4. submission-04-ready.png
5. submission-05-travel-demo.png
6. submission-06-stay.png
7. submission-07-overrun-shortage.png
8. submission-08-proposal.png
9. submission-09-rejected.png
10. submission-10-approved.png
11. submission-11-return-preparation.png
12. submission-12-summary.png
13. submission-13-history.png

실제 Pleos API 34에서 1080×720dp의 앱 콘텐츠를 캡처한다. 전체 차량 디스플레이 캡처가 아니며
체류 초과 구간은 테스트 시계 사용임을 영상에 명시한다. 실제 주행/실시간 교통/내비 안내 성공으로 설명하지 않는다.
별도 여행/제한 화면은 이전 journey-evidence/에도 있다. 이번에 영상 파일 자체는 제작하지 않았다.

## 실제 NaviHelper 검증 준비

[Debug 계획](../docs/navi-debug-test-plan.md)의 공식 예제 42dot POI, 권한·UX·연결·사용자 승인,
1회 요청·목적지 대조·콜백 확인 절차를 검토했다. 실행 직전 공식 문서도 다시 대조해야 한다.
실제 requestRoute는 실행하지 않았으며 승인 대기다. 카카오 ID를 Pleos ID로 변환하지 않는다.
지도/좌표 단독 요청 규약과 공식 매핑 방법이 확인될 때까지 추천 내비는 비활성이다.

## 제출 판정

연속 DEMO와 소스·영상 준비의 기반은 있으나 **실행 가능한 signed Release APK 제출 준비는 미완료**다.
필수 잔여: 소유자 Release 서명 → API 34 설치/실행/전환 검증 → 통합 소스 리뷰·회귀 → 제출 파일명/경로·심사 접근 확인.
실제 차량 왕복 내비 MVP에는 POI 규약/실제 경로·도착 이벤트와 서버 재계획 검증도 별도로 남아 있다.

## 최종 실행 결과

```powershell
$env:JAVA_HOME='<Android Studio JBR 경로>'
$env:ANDROID_HOME='<Android SDK 경로>'
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease compileReleaseKotlin --offline --no-configuration-cache
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w com.tripback.app.test/androidx.test.runner.AndroidJUnitRunner
apksigner verify --verbose app/build/outputs/apk/release/app-release-unsigned.apk
adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release-unsigned.apk
git diff --check
```

단위 114건 성공, 실패/skip 0. 전체 계측 `OK (16 tests)`, 실제 15건 성공 + 조건부 1건 skipped.
Debug/계측 APK 설치 성공. 연속 시나리오 성공. Lint·R8 포함 unsigned Release 빌드/Release Kotlin 성공.
서명 검증 실패와 설치 거절은 미해결 차단 사항이며 성공으로 집계하지 않는다.
생성 APK의 파일 크기/SHA256 및 제한 검사 결과는 캡처 폴더의 unsigned-release-inspection.json에도 저장했다.
전체 계측 최초 실행은 신규 시나리오의 거절 문자열 선택자에서 1건 실패했고 수정 후 최종 전체 통과했다.
초기 빌드의 import 오류와 오프라인 Lint 캐시 누락도 위 실패 기록으로 남겼다.
Backend 테스트/공급자 호출/실제 경로 요청/영상 제작/Release 런타임 실행은 하지 않았다.

최종 unsigned Release SHA256: `26ab680d74bfe15d506b730c6180ff315f98127362553586a069272d8bb12503`, 3518741 bytes.
