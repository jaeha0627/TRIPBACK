# 여행 진행·체류·귀가 관리 검증 보고

2026-11-05, 기존 feature/android-ai-recommendation-ui. 기존 미커밋 진단/NaviHelper 변경 보존.
백엔드는 읽기만 수행했다. 신규 브랜치/worktree, reset/clean/commit/push/PR/main 병합 없음.
OpenAI·카카오 API와 실제 경로 요청은 실행하지 않았다.

## A. 실제 차량 SDK 연결

| 항목 | 구현 | 실측 범위 |
|---|---|---|
| NaviHelper 3.0.5 | 기존 PleosNavigationAdapter 재사용 | API 34 SDK 연결/권한 확인. 추천 경로 전송은 계속 차단 |
| CarUxRestrictions | android.car 선택 플랫폼 라이브러리, 초기 조회와 변경 리스너 | 실제 emulator-5554에서 known=true, requiresOptimization=false, flags=0 조회 및 등록 성공 |
| 제한 변경·해제·상태 소실 | 초기 미확인/서비스 중단/권한 오류/비차량 플랫폼은 차단. 제한 시 입력·선택·편집 UI 전체 숨김 | 주입한 상태 변경에 대한 차단·해제·저장 상태 복원 계측 통과. 실제 주행 신호 변경 콜백 실측은 미완료 |
| 권한·DO | 추가 차량 제어 권한 없음. DO 추가 없음 | android.car.jar 실제 시그니처 및 공식 가이드 대조 |
| 실제 추천 장소 안내·도착 | 미지원 처리 | POI 매핑/좌표 단독 요청 규약 미확인. 요청·도착 콜백 없음 |

Car.createCar의 비동기 lifecycle listener로 서비스 연결을 확인한다. 준비되면
CAR_UX_RESTRICTION_SERVICE를 조회하고 registerListener 및 getCurrentCarUxRestrictions를 호출한다.
해당 callback의 isRequiresDistractionOptimization/activeRestrictions를 반영한다.
화면이 중단되면 unregisterListener/disconnect 및 미확인 상태로 전환하고 복귀 시 다시 조회한다.
이 앱은 DO를 선언하지 않으므로 known && !requiresOptimization && activeRestrictions==0일 때만 복잡한 UI를 허용한다.
주차 체크박스는 이 상태를 변경하지 않는다. SaveableStateHolder로 제한 중 숨긴 화면의 상태를 보존한다.

설치 SDK: platforms/android-37.0/optional/android.car.jar. Gradle `useLibrary("android.car")`,
Manifest `<uses-library android:name="android.car" android:required="false"/>`. 추가 Maven 버전/권한 없음.
API 34 에뮬레이터에서도 조회가 동작했으나 다른 차량/플랫폼과 Release APK 실행을 검증한 것은 아니다.

근거: [Pleos 주행 규제 개발](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide),
[AAOS manager](https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictionsManager),
[Car](https://developer.android.com/reference/android/car/Car).

## B. 여행 기능 구현 및 검증 범위

| 기능 | DEMO 구현 | 실제 연동 한계 |
|---|---|---|
| 진행 | READY → DEPARTURE → TRAVELING → ARRIVED → STAYING, 다음 방문 반복 → RETURN_PREPARATION → RETURNING → COMPLETED | 전 과정 DEMO_SIMULATED. 실제 GPS/내비 주행 상태로 승격하지 않음 |
| 출발/도착 | 별도 DemoNavigationAdapter와 수동 버튼. expected phase/revision으로 중복·늦은 이벤트 거부 | 실제 NavigationAdapter의 지원 상태는 false 유지 |
| 체류 | 계획/남은 시간/초과 경고. 시작 wall time+elapsedRealtime 앵커 저장 | 실제 도착 감지 없음. 자동 다음 이동·계획 수정 없음 |
| 복원 | RecommendationSession의 raw 응답/선택 계획과 여행 JSON 스냅샷을 함께 저장·복원 | Bundle 재생성 및 화면 중단/복귀 범위. 강제 종료·재부팅 후 자동 재개용 DB는 없음 |
| 시계 오류 | wall/monotonic 차이 5초 초과, 역행 감지 시 UNKNOWN 및 체류 종료 차단 | 재부팅 검증을 실제 실행하지는 않음. 사용자가 안전하게 복구하는 별도 UI는 후속 |
| 귀가 | 마지막 검증 legs의 ETA와 남은 체류 합산, 구간별 분 올림, 정확한 마감/안전 여유 비교 | 실제 이동 미확인으로 실제 feasibility는 UNKNOWN. 가정 계산 FEASIBLE/INFEASIBLE/UNKNOWN을 따로 표시 |
| 오래된 ETA | 공급자 retrievedAt의 15분 초과/미래값과 시연/미검증 출처를 표시 | 15분은 로컬 참고 경고 정책이며 최신 교통/서버 인증 기준이 아님 |
| 변경 제안 | 현재 체류 10분 단축, 앞으로의 선택 방문지 제외, 전후 비교, 승인/거절 기록 | 제외로 경로가 바뀌면 귀가시각·가정 판정 모두 UNKNOWN. 서버 재검증 완료로 표시하지 않음 |
| 필수 방문 | required ID를 인덱스에 대응, 일반 제안과 조작된 변경안 모두 필수 제외 차단 | 필수 방문지 제외 동의 UI는 제공하지 않으므로 항상 보호 |
| 승인 | 확정본 유지, 승인 시 현재 시각으로 재계산, 이전 revision 중복 승인 거부 | 변경은 시연 일정에만 적용. 실제 서버 replan/approve는 미구현 |
| 종료·기록 | 계획 장소, 수동 도착, 체류, 원래 귀가 예상, 변경 승인/거절 구분 | SharedPreferences에 최대 20개의 시연 기록. actualDrivingVerified/actualHomeArrivalVerified=false |

진입: AI 추천 시연 또는 HTTP 추천 → 일정 상세 → 이 코스 선택 → 출발 준비 →
`여행 진행 수동 시연 · DEMO_SIMULATED` → `승인하고 여행 시연 준비`.
HTTP에서 선택한 공급자 장소도 수동 시연 모드를 명시적으로 선택한 경우에만 시연한다.
여행 진행 중 기존 기능으로 빠져나가는 버튼은 잠그고 종료 후 추천 화면으로 돌아간다.

모델에는 백그라운드 카운트다운 작업이 없다. UI에만 disposable LaunchedEffect ticker가 한 개 있으며,
재구성할 때 남은 시간을 앵커에서 계산하므로 앱이 멈춘 동안의 시간도 중복 차감하지 않는다.
앵커가 불확실하면 시간을 새로 시작하거나 실제 체류 기록으로 주장하지 않는다.

Backend TripValidationService와 TripFeasibilityService의 기존 계산/출처를 확인했고 변경하지 않았다.
로컬 계산은 이미 내려온 검증 DTO만 재사용하며 네트워크 재검증을 하지 않는다.
방문지 제외/순서 변경의 새 ETA를 예전 구간을 이어 붙여 생성하지 않는다. 순서 조정 UI는 이번에 추가하지 않았다.

## 변경 파일

- app/build.gradle.kts: android.car 선택 라이브러리.
- app/src/main/AndroidManifest.xml: android.car required=false. 기존 Navi 권한 유지.
- app/src/main/java/com/tripback/app/trip/TripInputScreen.kt: 기존 안전 안내를 현재 UX 차단 구현에 맞게 수정.
- app/src/main/java/com/tripback/app/MainActivity.kt: 실제 UX 상태에 따른 안전 gate 및 lifecycle 연결.
- app/src/main/java/com/tripback/app/trip/progress/DrivingSafety.kt: 정책/인터페이스/플랫폼 감지.
- app/src/main/java/com/tripback/app/trip/progress/CarDrivingSafetyMonitor.kt: 실제 플랫폼 조회·리스너·정리.
- app/src/main/java/com/tripback/app/trip/progress/DrivingSafetyGate.kt: 제한 화면과 저장 상태 보존.
- app/src/main/java/com/tripback/app/trip/progress/TripJourney.kt: 시연 어댑터, 상태·체류·귀가·승인·스냅샷.
- app/src/main/java/com/tripback/app/trip/progress/JourneyScreens.kt: 진행/체류/승인/종료/시연 기록 화면.
- app/src/main/java/com/tripback/app/trip/recommendation/RecommendationSession.kt: 선택 일정과 여행 상태 통합.
- app/src/main/java/com/tripback/app/trip/recommendation/RecommendationScreens.kt: 수동 시연 진입. 기존 진단 기능 보존.
- app/src/test/java/com/tripback/app/trip/progress/TripJourneyTests.kt: 16개 순수 단위 테스트.
- app/src/androidTest/java/com/tripback/app/TripJourneyFlowTest.kt: 5개 계측 테스트 및 캡처.
- android/TRIP_PROGRESS.md, docs/sdk-capability-matrix.md, docs/navi-debug-test-plan.md.

위 app 경로는 android/TripbackApp 기준이다. NavigationAdapter/PleosNavigationAdapter와 기존 DEMO/Fake/HTTP/거부 진단 구현을 보존했다.

## 테스트와 실패 기록

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='<Android SDK 경로>'
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest compileReleaseKotlin --offline
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w com.tripback.app.test/androidx.test.runner.AndroidJUnitRunner
git diff --check
```

- 단위 테스트 114 성공, 실패/오류/skip 0 (기존 98 + 신규 16).
- 전체 계측 최종 `OK (15 tests)`: 실제 14건 성공 (기존 9 + 신규 5), keyless loopback 1건은 opt-in 전제 미충족으로 skipped.
- Debug 앱/계측 APK 빌드 및 Release Kotlin 컴파일 성공. 기존 LocalLifecycleOwner deprecation 경고만 남는다.
- 정상 왕복, 승인 전 비변경, 거절, 필수 보호, 중복/늦은 이벤트, 체류 초과, 재생성,
  시계 오류, 귀가 부족, 누락/오래된 ETA, UNKNOWN, SDK 미지원, UX 상태 변경·상태 복원 검증.
- 중간 계측 2건 실패: 문자열 검사에 substring 옵션 누락, 고정 generate/select 버튼에 performScrollTo 사용.
  선택자만 수정 후 전체 통과. 제품 검증 기준을 낮추지 않았다.
- `adb shell cmd car_service help`는 user build의 SecurityException으로 거절됐다.
  제한 우회나 실제 차량 제어는 시도하지 않았다. 실제 주행 신호 변경 이벤트 검증은 미완료다.
- 실제 API 34 UX 조회 로그: `known=true, requiresOptimization=false, restrictions=0`.
  실제 NaviHelper 연결 로그: `supported=false, permissionGranted=true`, POI 미검증으로 차단.
- 최종 안내 문구만 정리한 뒤 빌드·단위 테스트를 다시 통과했고, 최종 APK도 재설치/실행했다. 전체 계측은 그 직전 기능적으로 동일한 빌드에서 실행한 결과다.
- Backend 테스트, 실제 경로 요청, 서버 재검증, 실제 도착, Release APK 실행은 이번에 수행하지 않았다.

## 에뮬레이터 화면 캡처

로컬 기본 경로:
`C:/dev/TRIPBACK-android-ai-ui/android/TripbackApp/app/build/outputs/journey-evidence/`

| 화면 | 파일 |
|---|---|
| 시연 준비 READY | journey-ready.png |
| 출발 준비 DEPARTURE | journey-departure.png |
| 이동 시연 TRAVELING | journey-traveling.png |
| 도착 시연 ARRIVED | journey-arrived.png |
| 체류 STAYING | journey-staying.png |
| 변경 승인 대화상자 | journey-change-proposal.png |
| 체류 초과·귀가 부족·실제 UNKNOWN | journey-stay-overrun-return-shortage.png |
| 추천 세션에서 체류 상태 복원 | journey-stay-restored.png |
| 귀가 준비 RETURN_PREPARATION | journey-return-preparation.png |
| 귀가 이동 시연 RETURNING | journey-returning.png |
| 완료·시연 기록 요약 COMPLETED | journey-completed.png |
| UX 제한 상태 (주입 상태) | journey-ux-restricted.png |

여행 화면은 실제 Pleos API 34에서 Compose 계측 콘텐츠를 1080×720dp로 제한한 캡처다.
UX 제한 주입 테스트 캡처는 기본 앱 창 1682×1210이며, 별도 `app-launch-full-display.png`는 MainActivity 실제 실행 후 전체 디스플레이다.
`am start --user 10 -W` Status ok 및 dumpsys activity의 MainActivity RESUMED/visible=true/reportedDrawn=true를 확인했다.
전체 차량 디스플레이/GPS/실제 지도 안내 캡처로 해석하지 않는다. 스크롤 밖 내용은 별도다.
참조 PNG 23장 contact sheet와 체류/초과/완료 원본을 확인했다. 색상/흐름을 참고했지만 픽셀 완전 일치 주장은 하지 않는다.

## C. 공모전 제출용 MVP 잔여 작업

1. 공식 POI/좌표 규약 회신 및 승인 후 Debug 실제 requestRoute 검증.
   [계획과 지원팀 질문](../docs/navi-debug-test-plan.md). 자동 실행하지 않았다.
2. 실제 내비 목적지/경로/도착 이벤트의 상관 검증 및 주행 상태 기반 진행 연결.
3. 실제 UX 제한 변경 콜백·서비스 재연결·다른 플랫폼/차량 테스트와 제한 상태 QA.
4. replan/approve 서버 계약 구현·재검증, 승인 후 실제 경로 변경. 순서 조정과 실패 복구 UI.
5. 진행 상태의 장기 저장/강제 종료·재부팅 복구, 기록 조회·삭제 UX와 데이터 보관 정책.
6. 시계 불확실 상태의 수동 복구, 접근성·대화면 레이아웃, 서명 release APK 실행 및 정직한 데모 녹화.

현재는 핵심 여행 흐름을 시연으로 검증한 단계이며 실제 차량 왕복 안내 MVP 완성으로 표시하지 않는다.
