# Tripback Android App

Tripback 차량용 Android 앱 개발

- Language: Kotlin
- Platform: Android / Pleos Connect
- Features: 차량용 UI, 내비게이션 SDK 연동, 여행 일정 표시

## Android 왕복 검증 REST 연동 (개발)

Debug 앱의 기존 여행 조건/SDK 테스트 메뉴 아래 **왕복 검증 (개발)** 진입을 추가했습니다. 기존 DEMO `/trips/plan`과 다른 요청·결과 모델과 화면을 사용합니다. MainActivity/NaviHelper SDK 호출 방식, 백엔드 및 DEMO 계약은 변경하지 않았습니다.

### 실행 환경

1. 백엔드 실행 PowerShell에 `TRIPBACK_ENABLE_TRIP_VALIDATION_API=true`를 설정하고 서버를 시작합니다. 예: `$env:TRIPBACK_ENABLE_TRIP_VALIDATION_API = 'true'` 후 backend에서 `.\gradlew.bat bootRun`.
2. 실제 조회가 필요한 경우 카카오 REST 키는 백엔드 프로세스 환경변수 `TRIPBACK_KAKAO_LOCAL_REST_API_KEY`, `TRIPBACK_KAKAO_MOBILITY_REST_API_KEY`에만 안전하게 설정합니다. 키를 채팅·Android·설정 파일·Git·로그에 넣지 않습니다. 키가 없으면 서버는 정상 UNKNOWN/NOT_CONFIGURED 결과를 반환할 수 있습니다.
3. 에뮬레이터에서 호스트 PC의 `http://10.0.2.2:8080/api/v1/trips/validate`로 연결합니다. 호스트 서버의 포트/방화벽 접근이 필요합니다. 이 주소는 실기기용 주소가 아닙니다.
4. Debug 앱에서 주차 상태를 확인하고 출발 위도/경도, 날짜·시간대·출발/같은 날 귀가 시각, 안전 여유, 검색어, 사용자가 선택한 카카오 ID, 체류 분을 입력합니다. 날짜와 시간대를 명시적으로 확인해야 합니다. 좌표나 장소는 고정 예시로 자동 대입하지 않습니다.
5. 서버의 검색은 첫 페이지 15개 후보에서 정확한 ID를 확인합니다. 선택 ID가 그 페이지에 없으면 대체하지 않고 실패 사유를 표시합니다.

현재 backend API에는 인증·호출량 제한이 없으므로 공개 배포하지 않습니다. 로컬/비공개 개발 환경에서만 사용합니다. 요청당 장소 검색 최대 5회·자동차 경로 조회 최대 6회가 가능하므로 반복 호출 시 쿼터를 유의해야 합니다.

### 입력·결과 및 신뢰 범위

- 화면 입력은 현재 방문지 1개입니다. API 요청 모델과 결과 파서는 1~5개 방문지 및 귀가 포함 N+1개 구간을 지원합니다.
- `ValidationForm`은 기존 TripSchedule의 날짜·시간대·확인 상태를 재사용합니다. 기존 timestamps()는 DEMO의 2시간 초과 조건에 묶여 있어 그대로 호출하지 않고, 검증 화면에서 유효 날짜/HH:mm/ZoneId, DST 중복·누락 시각, 같은 날 출발 이후 마감을 검사합니다. DEMO의 2시간 조건은 변경하지 않았습니다.
- 출발 좌표는 사용자 입력이며 GPS 현재 위치가 아닙니다. 주차 확인도 사용자 확인이며 CarUxRestrictions/차량 주행 상태 자동 연동은 아직 없습니다. 주행 중 입력·복잡한 편집에 사용하지 않습니다.
- 결과에 FEASIBLE은 **현재 ETA 기준 가능**, INFEASIBLE은 마감/안전 여유 부족, UNKNOWN은 확인 불가로 표시합니다. null 귀가 시각과 여유는 미확인으로 유지합니다.
- 장소 이름/주소/카카오 ID, 장소·경로별 출처와 조회 시각, 모든 index/순서, 구간별 초 ETA, 총 왕복 이동/체류 분, 귀가 예상·여유·충족 여부와 실패 코드를 표시합니다.
- VERIFIED_LIVE / VERIFIED_CACHED / DEMO_SIMULATED를 개별적으로 표시합니다. ALL_PROVIDER_DATA_DECLARED_VALIDATED는 전체 실시간 LIVE가 아니며 CACHED가 포함될 수 있습니다. 장소 ID 일치는 영업·예약·주차·방문 가능성 검증이 아닙니다(NOT_VERIFIED).
- 현재 출발 기준 ETA의 수학적 계산이며 미래 날짜나 방문 후 출발 시점의 교통, 실제 귀가를 보장하지 않습니다. 출발지 실재/차량 접근성도 검증하지 않습니다. 카카오 ID는 Pleos POI ID가 아니며 내비게이션으로 전송하지 않습니다.

### 통신 및 상태 처리

- TripValidationApi의 네트워크는 Dispatchers.IO에서 실행합니다. UTF-8 JSON, 연결 5초/읽기 65초/전체 요청 70초 제한, 최대 1MiB 응답, 리다이렉트 차단, JSON Content-Type 및 비압축 응답 검사, 잘못된 UTF-8 거부를 적용합니다. 65초는 백엔드 순차 Provider 조회의 최대 처리 시간을 고려한 개발 기본값입니다.
- 일반 HTTP는 명시적인 로컬 debug 허용과 로컬 주소에만 제한합니다. UI는 debuggable 앱에만 표시하고 기존 debug `10.0.2.2` 네트워크 예외를 재사용합니다. main/release의 cleartext 차단을 확대하지 않았습니다.
- 400은 입력 점검, 404는 API 비활성/경로 미등록, 500은 서버 내부 오류, 연결 실패·타임아웃·잘못된 JSON은 별도 안내입니다. 원본 오류 본문/예외 메시지는 UI나 로그로 전달하지 않습니다. DEMO로 자동 전환하지 않습니다.
- TripValidationSession은 기존 PlanRequestGate를 사용해 중복 요청을 막고 입력 변경·취소·화면 이탈·앱 ON_STOP 시 요청을 무효화합니다. 코루틴 취소 시 HTTP 연결도 종료합니다. 늦은 응답은 현재 화면/입력을 덮어쓸 수 없습니다. 새 요청/실패 시 이전 결과를 성공 상태로 남기지 않습니다.
- 자동 재시도는 없으며 UI 요청/결과 상태는 메모리에만 유지합니다. 입력은 화면 복원을 위해 rememberSaveable을 사용합니다. 서버 API 키는 앱에 존재하지 않습니다.

### 변경 파일

`TripbackApp/app/src/main/java/com/tripback/app/trip/`:
- TripPlannerScreen.kt: 기존 메뉴 유지, debug 검증 메뉴 추가
- TripValidationModels.kt: 전용 요청/입력/결과 모델
- TripValidationJson.kt: 정확한 요청 JSON 및 엄격한 결과 검증
- TripValidationApi.kt: 취소 가능한 IO HTTP 클라이언트와 안전한 오류 안내
- TripValidationSession.kt: 로딩·중복 방지·취소·늦은 응답 차단
- TripValidationScreen.kt / TripValidationResultsScreen.kt: 입력·결과 화면

`TripbackApp/app/src/test/java/com/tripback/app/trip/`:
- TripValidationApiTest.kt: 요청/파싱/로컬 HTTP/인코딩/타임아웃/크기/취소/출처 메타데이터 검증
- TripValidationSessionTest.kt: 중복 요청·입력/화면 이탈 후 늦은 응답·이전 성공 상태 제거

### 실제 수행한 검증 (2026-11-03)

Android Gradle 프로젝트에서 Java 25.0.3으로 실행:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

- BUILD SUCCESSFUL. 신규 26건(클라이언트/입력·파서 23 + 상태 관리 3)을 포함해 총 51건: 성공 51, 실패 0, 오류 0, SKIPPED 0.
- 기존 Android 단위 테스트 25건(DEMO API·여행 조건/코스 등) 통과. 기존 NaviHelper 관련 파일은 수정하지 않았습니다. 별도 NaviHelper SDK 자동 테스트는 현재 저장소에 없으며 실제 SDK 회귀 실행을 수행했다고 주장하지 않습니다.
- Debug APK 생성: `TripbackApp/app/build/outputs/apk/debug/app-debug.apk`.
- Fake HTTP 서버/가상 데이터는 DEMO로 유지합니다. LIVE/CACHED 구분은 별도의 메타데이터 라벨 테스트로 검증하며 가상 장소/ETA에 LIVE 라벨을 붙이지 않습니다.
- 실제 카카오 호출·에뮬레이터/실기기 UI·실제 backend 연결·NaviHelper 통합 테스트는 수행하지 않았습니다. APK 빌드와 JVM 통신 테스트 성공이 실제 앱 연동 성공을 뜻하지 않습니다.
- `git diff --check` 및 신규 파일 공백 검사 통과.

남은 범위: 다중 방문지 편집 UI, 장소 검색/선택 UI, GPS 출발지, 차량 주행 제한 자동 연동, 실제 에뮬레이터/서버 통합 검증, 운영 HTTPS/인증·호출량 제한, AI 추천과 사용자 승인 후 내비게이션 전달입니다.
