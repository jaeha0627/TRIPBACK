# Pleos SDK 기능 가용성 검증표

> 최초 상태는 **검증 전**입니다. 문서에 명시되어 있다는 이유만으로 에뮬레이터에서 정상 작동한다고 표시하지 마세요.

| 필요 기능 | 후보 SDK/방법 | 사용 가능 여부 | 실측 근거/로그 | 대체 방식 |
|---|---|---|---|---|
| 목적지 경로 설정 | NaviHelper 3.0.5 | SDK 연결 확인 / 추천 경로 전달 비활성 | 아래 2026-11-05 기록 | 미검증 POI를 보내지 않고 차단 사유 표시 |
| 경유지 변경 | NaviHelper | 미검증 | 테스트 필요 | 다음 목적지별 순차 안내 |
| 경로 ETA/재탐색 알림 | NaviHelper | 미검증 | 테스트 필요 | 검증된 서버 ETA 또는 시뮬레이션 |
| 차량 기어·주행 상태 | Vehicle State | 미검증 | 권한·콜백 확인 필요 | UI에서 주행 상태 명시 |
| 전기차 잔여 에너지 | Vehicle State | 미검증 | 모델·항목별 확인 필요 | 사용자 입력/시연 값 |
| 위치·도착 | NaviHelper 콜백 또는 위치 제공자 | 미검증 | 권한·시뮬레이터 확인 | 수동 도착 버튼 |
| 음성 명령 | SpeechToText | 가용성 확인 필요 | 프로젝트 SDK 목록 | 시연 버튼, 실제 STT로 표현 금지 |
| 음성 안내 | TextToSpeech 또는 승인된 방법 | 가용성 확인 필요 | 프로젝트 SDK 목록 | 화면 안내 |
| 문자 발송 | 별도 합법적 SMS 서비스 | 미구현 | 전송 결과 확인 필요 | 미발송 시뮬레이션 |

## 통과 기준
1. 실제 의존성을 프로젝트 빌드에서 확인
2. Playgrounds/SDK별 권한 설정 확인
3. Pleos API 34 AVD에서 콜백 및 화면 테스트
4. 실패·미지원 항목은 앱 화면과 시연 영상에 명시
5. 테스트 일시, SDK 버전, 빌드 버전, 결과를 아래에 기록

## 테스트 기록
| 일시 | 실행 환경 | SDK/기능 | 결과 | 증거 |
|---|---|---|---|---|
|  |  |  |  |  |

공식 문서: https://document.pleos.ai/

## 2026-11-05 NaviHelper 추천 첫 목적지 연동 검증

작업 위치: 기존 `feature/android-ai-recommendation-ui` 브랜치. 신규 브랜치/worktree, commit/push/PR 없음.
기존 미커밋 추천 거부 진단 기능 보존. 백엔드 변경·실행 및 OpenAI·카카오 API 호출 없음.

### 공식 문서와 설치 SDK 대조

- `ai.pleos.playground:NaviHelper:3.0.5`의 로컬 sources.jar와 AAR classes.jar를 확인했다.
- POM 의존성: `navi-data:3.0.4`, `navi-aidl:3.0.1`. NaviHelper 및 RouteInfo의 실제 바이너리 시그니처도 JBR `javap`로 확인했다.
- 존재 확인: `NaviHelper(Context)`, `initialize()`, `initialize(onConnected, onFailed)`, `addListener`, `removeListener`, `release`, `requestRoute(RouteInfo)`, `requestRouteWithWaypoints(RouteWithWaypointsRequest)`.
- 콜백 존재 확인: `onRouteStarted(RouteStartInfo)`, `onRoutingCompleted()`, `onDestinationChanged()`, `onError(NaviErrorCode)`, `onDestinationArrived(DestinationArrivedInfo)`, `onWayPointArrived(WaypointArrivedInfo)`.
- `RouteInfo`는 latitude/longitude(Double), poiName/poiId/address/poiSubId(String)를 요구한다. displayLatitude/displayLongitude는 nullable이며 routeOption 기본값은 RECOMMENDED이다. poiProvider 기본값은 빈 문자열이다.
- **좌표 필드가 있다는 사실만으로 좌표 단독 요청 지원을 입증할 수 없다.** 필수 poiId/poiSubId에 빈 값·임의 값·카카오 ID를 넣는 규약은 확인하지 못했다. 지원 불가로 단정하지 않으며, 검증될 때까지 앱에서는 미지원 처리한다.
- `requestRoute`/경유지 요청: `pleos.car.permission.NAVI_ROUTE_SEARCH` (설치 SDK 설명: Normal).
- 목적지·경로 정보 조회: `pleos.car.permission.NAVI_ROUTE`. 지도 화면 요청: `pleos.car.permission.NAVI_CUSTOM_MAP` (이번에 호출/권한 추가 안 함).
- 기존 Manifest의 NAVI_ROUTE/NAVI_ROUTE_SEARCH 선언 유지. 신규 권한·DO(distractionOptimized) 선언 없음.

공식 근거:
- [3.0.5 소개](https://document.pleos.ai/api-reference/connect-sdk-pleos/NaviHelper/intro)
- [초기화](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/NaviHelper/initialization-and-release)
- [목적지와 콜백](https://document.pleos.ai/api-reference/connect-sdk-pleos/NaviHelper/destinations)
- [권한](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/NaviHelper/declaring-permissions)
- [CarUxRestrictions 개발 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)

### 구현된 범위와 차단 조건

`RecommendationSession`의 선택 일정을 재사용한다. FEASIBLE이 아닌 일정은 출발 준비로 선택하지 못한다.
`NavigationDestinationValidator`는 첫 방문 순서·요청 ID·확인 ID 일치, MATCHED_SELECTED_ID/FOUND,
공급자 검증 출처, 비시연 데이터, 유효 좌표와 첫 이동 구간의 목적지 좌표 일치를 검사한다.
카카오 ID는 원본 식별자로만 유지하고 Pleos POI ID로 변환하지 않는다.

`NavigationDeparture`는 사용자 명시적 승인, UX 제한 해제 확인, 권한과 어댑터 지원 여부가 모두 있어야
요청한다. 제출 후 실패/권한 예외를 포함해 동일 세션에서는 재전송하지 않는다. 승인·요청은 저장/복원하지 않는다.
`FakeNavigationAdapter`는 테스트 전용이며 DEMO_SIMULATED를 표시한다. 실제 어댑터의 대체로 자동 선택하지 않는다.

`PleosNavigationAdapter`는 실제 SDK 초기화·권한 확인·리스너 등록/해제·release 및 관찰 상태 표시만 수행한다.
**실제 추천 경로의 requestRoute 호출은 구현/활성화하지 않았다.** 미검증 POI 매핑을 우회하지 않기 위한 의도적인 차단이다.
출발 준비 화면에서 검증 가능한 첫 장소 좌표/원본 ID 또는 검증 실패 사유, SDK 연결/권한/차단 사유를 표시한다.
출발 승인 버튼은 비활성 상태이다. CarUxRestrictions는 아직 연결하지 않았고 항상 출발 허용으로 해석하지 않는다.
기존 수동 주차 확인은 실제 기어/속도/UX 제한 정보가 아니다. 기존 복잡한 추천 UI의 실제 주행 안전 통합은 후속 과제다.

### Pleos API 34 실측

`emulator-5554`: `product:crp_avd`, `model:Connect_on_x86_64_emulator`, API 34.
설치 패키지에 `ai.pleos.playground.service.navi`, `ai.umos.maps.android.navigation.app`가 있었다.
Debug 앱과 계측 APK를 `adb install -r`로 설치했고 MainActivity 실행 명령도 성공했다.
`dumpsys package com.tripback.app`에서 NAVI_ROUTE/NAVI_ROUTE_SEARCH granted=true 확인.
실제 SDK 연결 계측 로그 (UTC 2026-11-05 12:35:58, KST 21:35:58):

```text
TripbackCapability: SDK 3.0.5: NavigationCapability(supported=false, permissionGranted=true,
message=SDK 연결됨 · 좌표 단독 요청/Pleos POI 매핑 미검증으로 출발 차단);
SDK 콜백 미수신 · 경로 요청 없음
```

- SDK 서비스 연결: 확인. 추천 첫 목적지 전달: 차단. 실제 경로 탐색 성공: 확인 못 함.
- 경로 요청은 보내지 않았으므로 onRouteStarted/onRoutingCompleted/onError 수신 결과 없음.
- 위 콜백의 관찰 메시지를 추천 경로와 연결하거나 안내 시작 성공으로 표현하지 않는다.
- 도착/체류/다음 목적지/귀가 자동 처리 및 실제 차량 제어는 구현하지 않았다.

### 실행한 테스트 및 결과

JBR 25, 설치 SDK와 기존 Gradle 캐시 사용:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='<Android SDK 경로>'
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest compileReleaseKotlin --offline
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w com.tripback.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am instrument -w -e class com.tripback.app.RejectionDiagnosticTest com.tripback.app.test/androidx.test.runner.AndroidJUnitRunner
git diff --check
```

- 단위 테스트 98 성공, 실패/skip 0 (기존 91 + NavigationTests 7).
- FEASIBLE 첫 방문지, UNKNOWN/INFEASIBLE 차단, DEMO/미검증 ID/ID 불일치/좌표 오류,
  승인 누락/UX 미확인, SDK 미지원/권한 누락/예외, 중복 요청 및 실패 후 자동 재시도 차단을 검사했다.
- Debug APK/계측 APK 빌드, Release Kotlin 컴파일 성공. 기존 LocalLifecycleOwner deprecation 경고가 있다.
- 전체 계측 최초 실행: 10건 중 기존 RejectionDiagnosticTest 2건의 ActivityScenario 종료 NPE 발생.
  해당 클래스 단독 재실행 2건 성공. 전체 재실행 `OK (10 tests)` 확인:
  실제 9건 성공, opt-in keyless loopback 1건은 사전 조건 미충족으로 skipped.
  종료 오류는 수정하지 않았으며 이번 재실행에서는 재현되지 않았다.
- 기존 RecommendationFlowTest의 화면 전이/복원/미연결 버튼 검증 포함.
- 백엔드 테스트는 이번에 실행하지 않았다. 사용자가 보고한 259 성공을 이번 실행 결과로 주장하지 않는다.
- git diff --check 통과. APK 위치: `android/TripbackApp/app/build/outputs/apk/debug/app-debug.apk`.
- Release APK 실행, 실제 차량, 실제 추천 경로 및 도착 이벤트 통합 성공을 주장하지 않는다.

### 다음 작업

1. Pleos에 좌표 단독 요청의 poiId/poiSubId/poiProvider 규약을 공식 확인하거나,
   카카오 장소와 동일 장소임을 검증할 수 있는 Pleos POI 매핑을 확보한다. 빈 ID를 시험적으로 보내지 않는다.
2. 설치 AAOS car API와 공식 가이드에 맞춰 CarUxRestrictions의 초기 상태/변경/연결 해제와 화면 제한을 연결한다.
3. 동일 목적지를 표시하고 사용자 승인 후 실제 requestRoute를 전송한다. 목적지 조회와 경로 이벤트를 대조하여
   요청 제출/탐색 시작/탐색 완료/안내 상태를 구분한다. 이벤트에 요청 ID가 없으므로 외부 경로 변경과의 상관 검증도 필요하다.
4. onDestinationArrived의 목적지 상관 검증 후 체류 타이머와 다음 방문지 인덱스를 연결한다.
   매번 귀가 마감/필수 방문지/안전 여유를 재검증하고, 다음 경로 및 재계획에도 사용자 승인을 요구한다.

## 2026-11-05 여행 진행 및 CarUxRestrictions 추가 검증

이 절은 위 이전 기록의 "CarUxRestrictions 미연결" 상태를 갱신한다.
설치 android-37.0/optional/android.car.jar에서 Car.createCar(Context,Handler,long,CarServiceLifecycleListener),
CAR_UX_RESTRICTION_SERVICE, getCurrentCarUxRestrictions, registerListener/unregisterListener,
isRequiresDistractionOptimization/getActiveRestrictions를 javap로 대조했다.
공식 [Pleos 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide) 및
[AAOS API](https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictionsManager)와 일치한다.

Gradle useLibrary("android.car")와 선택 uses-library(required=false)를 추가했다. 새 차량 제어 권한이나 DO 선언은 없다.
API 34 emulator-5554에서 실제 초기 상태 known=true, requiresOptimization=false, flags=0 및 리스너 등록 성공.
상태 미확인·제한·서비스 해제 시 입력·일정 편집·복잡한 선택을 숨기고 주차 체크박스로 해제하지 않는다.
제한 변경은 주입 상태 계측으로 검증했다. 실제 주행 신호 변화에 따른 콜백 실측은 미완료다.
car_service help는 user 빌드 SecurityException으로 거절되어 우회하지 않았다.

NaviHelper는 실제 연결 및 권한만 재확인했고 추천 경로 시작은 여전히 비활성이다.
여행 진행·체류·귀가·변경 승인·요약은 별도 DEMO_SIMULATED 수동 이벤트로 구현했다.
실제 도착·GPS/주행·최신 ETA/서버 재검증과 혼동하지 않도록 UNKNOWN 및 출처를 표시한다.
단위 114 성공, 최종 계측 14 성공 + opt-in 1 skipped, Debug/계측 APK 빌드와 Release Kotlin 컴파일 성공.
상세 변경·실행 명령·실패 기록·화면 경로·남은 범위: [여행 진행 보고](../android/TRIP_PROGRESS.md).
공식 예제 POI의 실제 requestRoute 검증은 [Debug 계획](navi-debug-test-plan.md)에 작성했으며 사용자 승인 대기다.
