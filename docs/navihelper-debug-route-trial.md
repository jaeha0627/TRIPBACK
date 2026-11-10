# NaviHelper 3.0.5 Debug 실제 경로 시험 준비

> 최신 진단 보완 내용은 문서 끝의 「목적지 불일치 진단 개선」을 적용한다. 아래 최초 준비 단계의 기록은 당시 결과이며, 이번 보완에서 과거 UNKNOWN 판정이나 성공 조건을 변경하지 않았다.

작업 기준: `feature/android-ai-recommendation-ui`, HEAD `3d8b07f9d6befffac40ea8ba6ad8dbfcaad509f1`.
이번 작업은 준비 및 로컬 검증만 수행했다. 실제 경로 안내 결과는 **미확인**이다.
Backend, 추천 어댑터, DEMO 진행, prototype, 기본 Manifest 및 Gradle 의존성은 변경하지 않았다.

## 변경 파일

- `android/TripbackApp/app/src/main/java/com/tripback/app/MainActivity.kt`: 기존 SDK 화면 슬롯을 variant 구현에 연결. 시험 코드와 기존 단일/경유지 요청 버튼을 Debug 소스셋의 단일 목적지 시험으로 통합. Release에서 시험 SDK를 초기화하지 않는다.
- `android/TripbackApp/app/src/debug/java/com/tripback/app/DebugNaviTest.kt`: 공식 목적지 표시, 명시적 확인, 새 조회 기반 요청 직전 검사, 1회 요청 잠금, 콜백 관찰 및 시간순 기록.
- `android/TripbackApp/app/src/debug/java/com/tripback/app/DebugNaviContext.kt`: Android ServiceConnection/Binder 생존 감시. 연결 단절 후 SDK 해제 및 재연결 차단.
- `android/TripbackApp/app/src/debug/java/com/tripback/app/RouteTrial.kt`: 보수적 결과 판정, 좌표/POI 일치 검사, 요청 전제조건 검사.
- `android/TripbackApp/app/src/debug/AndroidManifest.xml`: Debug에만 `android.car.permission.CAR_POWERTRAIN` 읽기 권한 추가.
- `android/TripbackApp/app/src/release/java/com/tripback/app/DebugNaviTest.kt`: 버튼/SDK 호출이 없는 빈 구현.
- `android/TripbackApp/app/src/testDebug/java/com/tripback/app/RouteTrialTest.kt`: 신규 9개 단위 테스트.
- 이 문서.

## 확인한 SDK와 공식 자료

- 공식 [Destinations](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations), [Event listeners](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/NaviHelper/event-listeners), [3.0.5 릴리즈 노트](https://document.pleos.ai/en/docs/pleos-only/release-note/sdk-release-note).
- 설치된 Gradle 캐시의 `ai.pleos.playground:NaviHelper:3.0.5` sources/AAR 및 전이 의존 `navi-data:3.0.4` sources를 대조했고 해당 바이너리로 컴파일했다.
- `getDestinationInfo(): Unit` → `onDestinationInfo(DestinationInfo)`.
- `getRouteStateInfo(): Unit` → `onRouteStateInfo(RouteStateInfo)`.
- `onNavigationStatus(NavigationStatus)`는 수신 이벤트이다. 공개 상태 조회 getter를 임의로 추가하지 않았다.
- `RouteInfo`는 Kotlin 이름 지정 인자로 `latitude=37.48544722`, `longitude=127.03636666`, `poiName="42dot"`, `poiId="36khp37hh8gqcm5nqhe5"`, `poiSubId="0"`, `address="서울시 강남구 남부순환로 2621"`을 사용한다. 경도/위도 반전과 다른 POI ID를 테스트로 거부한다.
- 공개 SDK에는 현재 연결 상태 getter가 없다. 초기 연결 콜백만으로 연결을 단정하지 않고 Android Binder 생존/ping 및 요청 직전 두 정보 콜백을 함께 요구한다.
- 로컬 `BaseServiceConnector`에 연결이 없을 때 호출을 보관하는 동작이 있다. Debug ContextWrapper는 실제 bindService 연결을 감시하고 단절 시 재연결을 막고 SDK를 해제한다. 비공개 SDK 필드, reflection, 가상 메서드는 사용하지 않는다. 연결 점검과 실제 Binder 전송 사이의 미세한 경합은 실제 환경에서 확인해야 한다.
- 로컬 `android-37.0/optional/android.car.jar`를 javap로 확인했다. 공개 stubs에 CarDrivingStateManager가 없어 임의 호출하지 않고 P 기어와 CarUxRestrictions를 즉시 읽는다.
- [Android VehiclePropertyIds](https://developer.android.com/reference/android/car/VehiclePropertyIds), [VehicleGear](https://developer.android.com/reference/android/car/VehicleGear)에 따라 `GEAR_SELECTION`, `GEAR_PARK`, `CAR_POWERTRAIN` 읽기 권한을 사용한다. 런타임에서 API/권한/기어/UX 상태를 확인할 수 없으면 차단한다. 속도나 개인 위치를 추가 조회하지 않는다.

## 동작 및 판정

1. 기존 Debug SDK 화면에서 수동 조회한다. P 기어, 제한 없는 CarUxRestrictions, foreground RESUMED, 권한 및 실제 Binder 생존 상태가 확인되어야 한다.
2. 5초 이내 목적지와 경로의 두 콜백을 모두 받아야 확인 체크박스를 사용할 수 있다. 목적지 없는 상태에서 콜백을 제공하지 않는 내비 구현은 UNKNOWN으로 차단한다. 무응답을 목적지 없음으로 해석하지 않는다.
3. 공식 42dot과 목적지 일치 여부, 경로 isRouting/mode/termsAgreement를 표시한다. 다른 목적지는 이름/ID/주소/좌표를 숨긴다. 기존 목적지/경로/경유지를 대체할 수 있다는 경고를 사용자가 직접 확인해야 한다.
4. 요청 버튼을 누르면 다시 두 상태를 조회한다. 확인 당시 목적지 식별정보·좌표·경로 상태와 달라지거나, 약관 미동의, 5초 초과, Binder 단절, 안전 상태 미확인은 요청을 차단한다. 목적지 변경 이벤트도 확인을 무효화한다.
5. 전송 전에 앱 private preferences에 1회 잠금을 동기 저장하고 최종 안전/Binder 검사를 한다. 화면/프로세스 재생성이나 연타로 재요청하지 않는다. 잠금 저장 후 최종 검사에서 차단되어도 잠금을 유지한다.
6. 호출 반환은 성공이 아니다. `onRouteStarted` 다음 `onRoutingCompleted`, 완료 후 새 조회의 목적지 일치와 `isRouting=true`, 요청 이후 `ROUTE_DRIVE` 이벤트가 모두 있어야 SUCCESS이다. POI ID/subId와 좌표(약 1m 허용오차)를 함께 비교한다.
7. 호출 예외, onError 또는 경로 취소는 진행 중 시험을 FAILURE로 끝낸다. SDK가 요청 ID를 제공하지 않으므로 오류도 외부 요청에서 발생할 수 있다. 중복 탐색 시작은 인과관계 불명으로 UNKNOWN이다.
8. 30초 내 증거가 부족하면 UNKNOWN이며 늦은 콜백은 결과를 승격하지 않는다. 자동 경로 재시도는 없다. 완료 후 증거 조회 1회는 읽기 작업이다.
9. SDK 화면 이탈/백그라운드는 확인 및 진행 중 사전 조회를 취소한다. 이미 전송한 요청의 콜백 관찰은 30초 기한까지 유지한다. 안내 앱 전환 때문에 자동 증거 조회가 차단되면 복귀 후 수동 상태 조회가 가능하다. Activity 파괴 시 진행 결과는 UNKNOWN으로 저장한다. 복원된 결과/이력은 VERIFIED_CACHED이며 현재 안내 성공으로 새로 판단하지 않는다.
10. 이벤트 순번, UTC 시각, 요청 시각과 호출 반환/실패, 필수 콜백 및 Navigation 상태를 최대 160개까지 화면과 private preferences에 저장한다. 원본 SDK 객체, 현재 위치, 다른 목적지의 세부정보, 예외 메시지, 키·인증정보는 기록하지 않는다.

## 실행한 검증

작업 경로: `C:/dev/TRIPBACK-android-ai-ui/android/TripbackApp`.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat testDebugUnitTest assembleDebug compileReleaseKotlin processReleaseMainManifest --offline
git diff --check
```

- 최종 Gradle 실행: BUILD SUCCESSFUL. 총 단위 테스트 **123개**, 실패/오류 **0개**. 신규 RouteTrialTest **9개** 포함.
- 신규 테스트: 호출 반환/완료만으로 성공 금지, 모든 안내 증거 필요, timeout 후 늦은 콜백 무시, 실패 terminal, 중복 시작 거부, 공식 좌표 순서 및 식별자 검사, 모든 요청 전제조건 검사, 다른 탐색 시작 UNKNOWN, 목적지 변경 시 기존 일치 증거 무효화.
- assembleDebug 성공. 산출물: `app/build/outputs/apk/debug/app-debug.apk`. APK 설치/실행 검증은 수행하지 않았다.
- Release Kotlin 컴파일 및 Manifest 처리 성공. 컴파일된 Release DebugNaviTest를 javap로 검사하여 빈 UI/pause/close만 확인했다. 클래스 상수에 NaviHelper/requestRoute/42dot이 없고 Release 클래스 디렉터리에 DebugNaviContext/RouteTrial이 없다.
- 현재 merged Manifest의 CAR_POWERTRAIN은 Debug에만 존재한다. 기존 추천 화면의 FLAG_DEBUGGABLE 분기와 추천 어댑터 supported=false는 그대로다.
- git diff --check 통과. 신규 파일은 별도로 공백 검사했다.
- 최초 빌드는 JAVA_HOME 미설정으로 시작 실패했고 설치된 JBR 지정 후 통과했다. API 34 호환을 위해 기존 `CarPropertyValue.status`를 사용해 compileSdk 37에서 deprecated 경고 1개가 남는다.
- 장치 instrumentation, 에뮬레이터, 실제 경로 요청, 실제 OpenAI/카카오 호출은 실행하지 않았다. 기존 단위 테스트의 HTTP는 로컬 테스트 서버를 사용한다.

## 첫 수동 에뮬레이터 시험 절차 (이번 작업에서는 실행하지 않음)

1. Android Studio에서 이 작업 폴더와 Debug variant를 선택한다. 공식 [NaviHelper emulator setup](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/NaviHelper/intro)에 따라 Pleos Connect API 34 AVD, Virtual Console 및 내비 앱을 준비한다. 개인 계정·실제 위치 대신 시험용 공개 위치를 사용한다. 42dot과 너무 가까운 출발점은 피한다.
2. 에뮬레이터가 완전히 부팅된 후 Navigation 앱의 약관/필요 초기 설정과 SDK 서비스 제공 여부를 확인한다. Virtual Console에서 정차 및 P 기어를 설정하고 CarUxRestrictions가 제한 없음인지 확인한다. 개인 목적지/필수 경유지가 있는 내비 경로는 시험에 사용하지 않는다.
3. 이후 시험자가 별도로 Debug APK 설치 및 실행을 수행한다. NAVI_ROUTE, NAVI_ROUTE_SEARCH, CAR_POWERTRAIN 권한이 없는 경우 우회하지 말고 차단 결과를 기록한다. Release 설치 확인은 별도의 수동 검증으로 남는다.
4. Tripback의 기존 SDK 화면에서 현재 목적지·경로 조회를 누른다. 이 버튼은 경로를 요청하지 않는다. 두 콜백과 경로 상태를 기록한다. 내비가 목적지 없는 상태에서 응답하지 않으면 UNKNOWN 차단을 기록하고 여기서 중단한다. 무응답을 우회해 전송하지 않는다.
5. 요청 전까지 체크박스가 해제되어 있고 전송 버튼이 비활성인지 확인한다. 조회 후 목적지 변경, 권한 미확인, UX 제한, P 기어 아님, 서비스 단절 중에는 요청이 차단되는지 먼저 확인한다. 이 단계에서는 요청 버튼을 누르지 않는다.
6. 실제 경로 시험을 수행하기로 시험자가 결정한 뒤, 공식 42dot 좌표·ID와 현재 경로 대체 경고를 직접 확인하고 체크한다. 전송 버튼은 **한 번만** 누른다. 그 직후에는 재조회 응답과 안전 검사가 끝날 때까지 기다린다. 다른 내비 앱 조작을 병행하지 않는다.
7. 요청 시각/반환, onRouteStarted, onRoutingCompleted, onDestinationChanged, onDestinationInfo 일치 여부, onRouteStateInfo, onNavigationStatus 및 onError를 시간순으로 확인한다. Navigation 화면에서도 42dot 목적지와 실제 안내 활성화를 직접 확인한다. SDK 증거와 화면 증거를 함께 기록한다. 화면 캡처/영상에 개인 정보가 있으면 공유하지 않는다.
8. 30초 후 SUCCESS/FAILURE/UNKNOWN을 그대로 기록한다. 반환이나 탐색 완료 하나만으로 성공으로 바꾸지 않는다. Activity/프로세스 재생성 후 전송 버튼이 계속 잠기는지 확인하고 새 경로 호출이 없는지 확인한다.
9. UNKNOWN/FAILURE에서도 자동 재시도는 없다. 추가 시험은 별도 결정 후 전용 에뮬레이터의 앱 데이터 초기화가 필요하다. 데이터 초기화는 저장 여행/설정도 지우므로 증거를 먼저 보존하고 개인 데이터가 없는 전용 환경에서만 수행한다. 코드나 UI에는 잠금 해제/자동 반복 버튼을 추가하지 않았다.

## 남은 위험과 미확인

- 실제 경로 탐색·안내 성공, SDK/Car API의 Pleos API 34 런타임 동작, Binder 단절 처리, 타임아웃, 화면/프로세스 재생성, 내비 앱 전환은 에뮬레이터에서 미검증이다.
- 정보/안내 이벤트에는 요청 ID가 없어 새 조회와 이전 또는 외부 이벤트를 완전히 상관시킬 수 없다. 성공 표시는 보수적인 SDK 증거이며 Navigation 화면의 수동 대조가 필요하다. 외부 내비 조작은 병행하지 않는다.
- Binder 연결 확인과 전송은 원자적이지 않다. 앱에서 연결 단절/불명 상태를 차단하지만 SDK 내부/플랫폼의 극단적인 전송 경합은 실제 단절 시험이 필요하다.
- 목적지가 없을 때 정보 콜백이 오지 않는 SDK 구현은 의도적으로 차단된다. 무응답과 목적지 없음의 구분을 임의로 만들지 않았다.
- P 기어 또는 CarUxRestrictions를 제공하지 않거나 권한을 얻을 수 없는 에뮬레이터는 요청할 수 없다. 안전 상태를 시뮬레이션으로 대체하지 않는다.
- 카카오 POI와 Pleos POI 호환성은 계속 미확인이다. 추천 어댑터의 차단을 유지하며 이 시험 결과로 추천 내비를 활성화하지 않는다.
- Release APK 전체 assemble/설치 시험 및 실제 장치 UI 시험은 수행하지 않았다. Release 소스/컴파일 클래스/Manifest 분리까지만 확인했다.

## 목적지 불일치 진단 개선

이번 보완은 오프라인 구현/검증만 수행했다. 아래 실제 시험 결과는 사용자가 제공한 관찰이며 에이전트가 장치를 실행하거나 재검증한 결과가 아니다.

### 사용자 제공 첫 실제 시험 증거

- 공식 42dot 요청 1회 후 Navigation 화면에 목적지, 남은 거리 50m, 약 1분과 안내 종료 버튼 표시.
- onRouteStarted 및 ROUTE_DRIVE 수신, ROUTE_GUIDING과 isRouting=false 관찰.
- onRoutingCompleted 미관찰, 목적지 비교 false, 30초 후 UNKNOWN. 사후 읽기 조회에서도 비교 false.
- 이 결과는 안내 화면 활성화 증거와 SDK 목적지 동일성 미확인을 구분해 기록한다. 이번 보완으로 과거 UNKNOWN을 SUCCESS로 바꾸지 않는다.

### 이번 수정 파일 (기존 미커밋 작업 위에 추가)

1. `app/src/debug/java/com/tripback/app/DestinationDiagnostic.kt`: 입력 원본을 보관하지 않는 진단 결과, 필드별 비교, 안전한 표시 문자열 추가.
2. `app/src/debug/java/com/tripback/app/RouteTrial.kt`: 기존 matchesSample 진입점을 진단의 엄격한 동일성 결과에 연결. 기존 RouteTrial SUCCESS/FAILURE/UNKNOWN 조건은 유지. 수동 조회 목적 분리 함수 추가.
3. `app/src/debug/java/com/tripback/app/DebugNaviTest.kt`: 항목별 진단/종합 표시, 읽기 전용 조회 분리, 구형 완료 이벤트 관찰, 라이프사이클/단절/타임아웃 사유 기록.
4. `app/src/main/java/com/tripback/app/MainActivity.kt`: 생성/복원·정지·구성 변경에 의한 재생성·파괴의 사유만 variant 구현에 전달.
5. `app/src/release/java/com/tripback/app/DebugNaviTest.kt`: 라이프사이클 인터페이스만 빈 구현으로 맞춤. 버튼, 진단 모델, SDK 호출 없음.
6. `app/src/testDebug/java/com/tripback/app/DestinationDiagnosticTest.kt`: 신규 20개 진단/개인정보/조회/판정 유지 테스트.
7. 이 문서.

소스 경로 기준은 `android/TripbackApp`이다. 기존 RouteTrialTest 9개를 포함한 123개 테스트는 삭제/수정하지 않았다. 기존 Debug Manifest와 Binder 감시 구현도 그대로 유지했다.

### 표시와 로그의 의미

- poiId: EXACT / EMPTY / WHITESPACE_ONLY / TRIM_ONLY_MATCH / DIFFERENT.
- poiSubId: EXACT / NULL / EMPTY / DIFFERENT. whitespace-only Sub ID는 필드값상 DIFFERENT이지만 정보가 없으므로 종합 INSUFFICIENT_DATA이다. `00`이나 공백이 붙은 `0`을 동일 ID로 정규화하지 않는다.
- latitude/longitude 및 displayLatitude/displayLongitude: ABSENT / INVALID / WITHIN_TOLERANCE / OUTSIDE_TOLERANCE. 유한성과 위도 ±90°, 경도 ±180° 범위를 검사한다. 허용치는 각 축 0.00001°로 유지한다. 기본 위경도는 SDK 모델상 non-null이며 display 좌표는 nullable이다.
- name: namePresent, nameExact 두 bool만 기록한다. 공식 이름은 정확한 `42dot`이며 trim/case 변환으로 성공시키지 않는다.
- MATCH: 기존과 동일하게 ID/Sub ID/기본 위경도가 모두 엄격히 일치한다. 이름/display 좌표는 참고 항목이며 이 종합의 필수 항목이 아니다. MATCH는 목적지 비교 결과이지 경로 안내 SUCCESS가 아니다.
- INSUFFICIENT_DATA: 필수 식별정보가 null/blank이거나 기본 좌표가 유효하지 않다. 다른 필드 차이가 함께 있어도 이 종합 결과를 우선하고 각 필드 진단은 모두 표시한다.
- FIELDS_DIFFER: 필수 정보는 있지만 전체 엄격 비교를 통과하지 못했다. TRIM_ONLY_MATCH도 여기에 속한다. 실제 다른 목적지라고 확정하는 결과가 아니다.
- 기존 “다른 목적지” 단정 문구를 “전체 일치 조건 미충족”으로 변경했다. 데이터 부족은 실제 다른 목적지의 증거로 취급하지 않는다.
- 원본 반환 ID/Sub ID/이름/주소/좌표, 정확한 좌표 차이와 해시는 출력하지 않는다. 주소는 진단 함수의 입력에도 전달하지 않는다. 진단 객체는 enum과 bool만 보관한다. 기존 화면의 공개 공식 샘플 정보 표시는 유지하며 실제 반환값을 덮어 표시하지 않는다.
- 기존 확인 전후 동일성을 위한 원본 SDK 목적지 및 snapshotKey는 메모리에만 존재하며 UI/로그/preferences로 내보내지 않는다.
- nullable/default 필드는 SDK 객체로 전달된 뒤 원본 JSON의 누락 여부를 복원할 수 없다. 기본값 없는 필수 필드 디코딩 실패는 콜백 부재로 나타날 수 있다. READ_TIMEOUT은 누락·전송 실패·연결 문제의 원인을 확정하지 않는다. raw JSON 가로채기나 SDK reflection은 추가하지 않았다.

### 이벤트와 조회

- 기존 onRouteStarted, onRoutingCompleted, onDestinationChanged, onError 및 Navigation 상태 기록을 유지했다.
- 로컬 NaviHelper 3.0.5 sources와 [공식 릴리즈 노트](https://document.pleos.ai/en/docs/pleos-only/release-note/sdk-release-note)에서 구형 onRouteEnded 지원을 확인했다. LEGACY_OBSERVATION_ONLY로 별도 기록하며 onRoutingCompleted를 수신한 것으로 대신 처리하지 않는다.
- ROUTE_DRIVE, mode=ROUTE_GUIDING, isRouting=false를 독립적으로 화면과 이벤트 이력에 보존한다. isRouting을 임의로 “탐색 중”으로 재정의하지 않았다.
- ACTIVITY_CREATED / ACTIVITY_RECREATED / ACTIVITY_STOP / ACTIVITY_RECREATION / ACTIVITY_DESTROY / SDK_SCREEN_ENTER / SDK_SCREEN_DISPOSE를 구분한다. 복원 플래그는 savedInstanceState 기준이며 복원 번들 없는 새 프로세스 시작은 CREATED로 기록될 수 있다. 요청 잠금과 기존 결과는 별도로 유지한다.
- BINDER_DISCONNECTED, TRIAL_TIMEOUT(30초), READ_TIMEOUT(5초) 사유를 분리한다. READ_TIMEOUT은 목적지/경로 콜백 각각의 수신 여부를 기록한다.
- 수동 읽기 조회는 완료 콜백 여부에 의존하지 않는다. PENDING 중에는 기존 엄격 판정의 증거로 조회하며, NOT_REQUESTED에서는 inspect, 종료/복원된 시험에서는 post_trial_diagnostic으로 분류한다.
- UNKNOWN 후의 조회는 POST_TRIAL_DIAGNOSTIC 및 verdict 유지로 기록한다. 조회 실패·차단·타임아웃은 별도 diagnosticStatus에 표시하며 기존 결과를 덮어쓰지 않는다. 읽기 실패 자체로 진행 시험의 기한을 줄이지 않고 30초 관찰 기한을 유지한다.
- READ 번호는 앱 내부 진단 묶음이다. SDK 요청 ID나 응답 상관관계 보장을 의미하지 않는다. 조회 창 밖의 콜백은 UNSOLICITED_EVENT로 기록한다.
- 안전 검사, SDK/Binder 상태 확인, 사용자 확인 후 1회 전송 잠금과 자동 재요청 금지는 유지했다. UNKNOWN 후 읽기 조회는 잠금을 해제하거나 requestRoute를 호출하지 않는다.

### 이번 검증

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
./android/TripbackApp/gradlew.bat -p android/TripbackApp testDebugUnitTest assembleDebug compileReleaseKotlin processReleaseMainManifest --offline
git diff --check
```

- BUILD SUCCESSFUL. 기존 123개 + 신규 20개 = 단위 테스트 143개, 실패/오류 0.
- 신규 테스트는 모든 ID/Sub ID 분류, null/blank의 정보 부족 처리, 축별 차이·유효성·허용치, display 선택성, 이름의 참고 성격, 진단 문자열에 원본 값 미포함, 완료 콜백 없는 수동 조회, UNKNOWN의 terminal 유지와 엄격 비교 미완화를 검증했다.
- assembleDebug, compileReleaseKotlin, processReleaseMainManifest 통과. APK 설치 및 장치 실행은 수행하지 않았다.
- Release 컴파일 클래스에 DestinationDiagnostic/관련 enum/RouteTrial/DebugNaviContext가 없는지 검사했다. Release DebugNaviTest 상수에는 NaviHelper/requestRoute/42dot/진단 분류가 없다. merged Manifest에서 CAR_POWERTRAIN은 Debug에만 존재한다.
- 추천 어댑터(supported=false), DEMO/안전 소스, 기존 Binder 감시, main Manifest가 작업 시작 시점과 동일한지 내용 대조를 수행했다. 비밀키·서명키는 열람하지 않았다.
- git diff --check와 신규 파일 별도 공백/UTF-8 검사를 수행했다. compileSdk 37의 기존 CarPropertyValue.status deprecated 경고 1개는 남아 있다.

### 다음 수동 확인 (이번 작업에서는 실행하지 않음)

1. 기존 시험 데이터/잠금을 유지한다. 실제 경로를 다시 요청하거나 앱 데이터를 초기화할 필요 없이 안전하게 P 기어·UX 제한 없음 상태에서 기존 SDK 화면의 읽기 전용 조회만 실시한다.
2. 최종 시험 결과 UNKNOWN과 전송 잠금이 유지되는지 확인한다. 진단 결과가 MATCH여도 기존 판정이 SUCCESS로 바뀌면 안 된다.
3. ID와 Sub ID의 분류, 기본 위경도 각각의 분류, nameExact, display 좌표 각각의 존재/유효성/근접 분류를 확인한다. 원본 값이나 정확한 차이를 추가 출력하지 않는다.
4. ROUTE_DRIVE / ROUTE_GUIDING / isRouting을 따로 기록한다. ID 비어 있음, Sub ID null, 기본 좌표 차이, display만 근접 등의 패턴을 구분한다. 이름만 일치하는 경우에는 목적지 동일성을 확정하지 않는다.
5. 다음에 별도로 승인된 실제 시험을 진행한다면 onRouteEnded와 onRoutingCompleted가 각각 관찰되는지 확인한다. 이번 진단의 사후 조회만으로 과거 완료 콜백을 복원할 수는 없다.
6. 화면 전환/복원·Binder 단절·읽기 타임아웃의 로그 사유가 분리되는지, 그 과정에서 새 requestRoute가 호출되지 않는지 확인한다. 개인정보가 없는 전용 환경에서만 시행하며 출력 공유 전 원본 목적지 정보가 포함되지 않았는지 확인한다.

실제 에뮬레이터 UI/라이프사이클/SDK 반환값에 대한 진단 개선의 런타임 검증은 아직 수행하지 않았다. 이번 작업은 원인 식별을 위한 진단 보완이며 SUCCESS 조건을 완화하는 작업이 아니다.

## 진단 UI 갱신 보완 — DEST_DIAG_UI_V2

### 조사 결과와 원인 범위

- 작업 경로의 기존 onDestinationInfo는 diagnostic 함수 → diagnoseDestination → 비교 결과 저장 → record(comparisonLine) → Content 표시로 이미 연결되어 있었다.
- 사용자 제공 `officialSampleMatch=false`와 `getDestinationInfo + getRouteStateInfo (inspect)` 템플릿은 조사 시점 현재 소스와 로컬 Debug APK의 DEX 코드 문자열에서 발견되지 않았다. 현재 소스는 READ 번호와 필드별 comparisonLine을 출력한다.
- 따라서 해당 로그 전체 부재를 Compose 문제 하나로 설명할 수 없다. 설치 APK/실행 대상 차이 또는 preferences에 보존된 이전 이벤트 이력일 가능성이 있다. 장치 조회·설치·조작을 하지 않았으므로 설치된 APK의 동일성과 로그의 실제 생성 시점은 확인하지 않았다.
- 별도로 destinationDiagnostic이 일반 var여서 Compose가 변경을 직접 관찰하지 못하고 snapshot/events 같은 다른 상태 변경에 의존하는 취약점을 확인했다. 이를 최소한의 상태 경계와 별도 패널로 보완했다. 기존 코드도 다른 상태가 바뀌면 다시 그려질 수 있으므로 이것이 이번 사용자 증상의 단독 원인이라고 단정하지 않는다.

### 이번 수정 파일

1. `app/src/debug/java/com/tripback/app/DebugNaviTest.kt`: 응답을 관찰 가능한 진단 상태에 전달하고 필드 비교 결과와 버전 표식을 이벤트에 기록. 같은 상태를 별도 패널에 전달. 이전 저장 이력 포함 안내 추가.
2. `app/src/debug/java/com/tripback/app/DestinationDiagnosticPanel.kt`: DestinationDiagnosticUiState와 표시 패널 추가. mutableStateOf로 직접 관찰 가능하며 enum/bool 결과만 보관/표시. SDK Helper·경로 요청·preferences 접근 없음.
3. `app/src/testDebug/java/com/tripback/app/DestinationDiagnosticUiStateTest.kt`: 다른 UI 상태 변경 없이 콜백 입력으로 관찰 상태가 무효화되는 회귀 테스트와 후속 응답/안전 출력 테스트 2개.
4. `app/src/androidTest/java/com/tripback/app/DestinationDiagnosticPanelTest.kt`: 응답 대기 → 필드 진단 → 두 번째 응답의 화면 갱신과 원본 값 미노출을 검사하는 Compose UI 테스트 1개. MainActivity/NaviHelper를 만들지 않는다.
5. 이 문서.

공통 소스 경로 기준은 `android/TripbackApp`이다. DestinationDiagnostic.kt의 비교 기준, RouteTrial 판정식, 요청 잠금/안전 조건 및 Release 구현은 수정하지 않았다. 기존 미커밋 파일과 앱 데이터/이벤트 이력도 삭제하거나 초기화하지 않았다.

### 표시 경로

`onDestinationInfo` → `DestinationDiagnosticUiState.accept` → `diagnoseDestination` → 관찰 가능한 diagnostic 갱신 → 같은 결과의 comparisonLine 이벤트 기록 및 DestinationDiagnosticPanel 재구성.

화면에는 `DEST_DIAG_UI_V2`, 응답 대기 또는 MATCH/INSUFFICIENT_DATA/FIELDS_DIFFER, POI ID/Sub ID 분류, 위도/경도 분류, 이름의 bool 비교, display 좌표 분류를 각각 표시한다. 원본 반환 POI ID/좌표/주소/이름은 출력하지 않는다. 기존 공개 공식 샘플 설명은 그대로다.

생성된 APK에서 버전 표식과 진단 패널 문자열을 확인했고 이전 officialSampleMatch 로그 템플릿이 없음을 확인했다. 사용자가 다음에 별도로 설치/실행을 확인할 때 표식이 보이지 않으면 실행 APK와 작업 폴더부터 대조해야 한다. 표식이 있고 옛 로그도 보이면 보존된 이력과 새 READ 이벤트의 시각/표식을 구분한다. 이번 작업은 설치를 수행하지 않았다.

### 오프라인 검증

프로젝트 루트에서 실행:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
./android/TripbackApp/gradlew.bat -p android/TripbackApp testDebugUnitTest assembleDebug compileReleaseKotlin compileDebugAndroidTestKotlin --offline
git diff --check
```

- BUILD SUCCESSFUL. 단위 테스트 기존 143개 + 신규 2개 = 145개, 실패/오류 0.
- SnapshotStateObserver 회귀 테스트는 실제 Compose snapshot 관찰 경계를 사용해 다른 상태 변경 없이 진단 상태 갱신을 확인했다.
- Compose UI 테스트 소스의 컴파일 통과. 장치 instrumentation 테스트 실행은 하지 않았다. UI 렌더링의 실제 장치 실행 결과로 보고하지 않는다.
- assembleDebug 및 compileReleaseKotlin 통과. 새 Debug APK: `android/TripbackApp/app/build/outputs/apk/debug/app-debug.apk`.
- Release 컴파일 클래스 디렉터리에 진단 UI/상태/모델과 관련 enum, RouteTrial, DebugNaviContext가 없음 확인.
- git diff --check 및 수정/신규 소스 별도 공백 검사 통과. 기존 CarPropertyValue.status deprecated 경고 1개 유지.
- UNKNOWN 이후 진단 조회/콜백은 기존 terminal 판정 및 durable 1회 잠금을 변경하지 않는다. 새 상태/패널은 판정/잠금 객체에 접근하지 않는다.
- 실제 경로 요청, 에뮬레이터 조작, APK 설치, 앱 데이터 초기화, 외부 API 호출, Git commit/push/merge 및 비밀키 열람은 수행하지 않았다.
