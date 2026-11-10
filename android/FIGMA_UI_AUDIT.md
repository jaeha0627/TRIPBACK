# Android AI 추천 UI Figma 검수

## 비교 조건

- 브랜치: feature/android-ai-recommendation-ui. 기존 미커밋 구현을 보존했다.
- 로컬 SCREEN_INDEX.md 및 PNG 23장을 직접 확인하고 HTML v5의 입력 → 생성 → 비교 → 상세 → 선택 흐름을 대조했다.
- 기준 PNG 1280×720 중 왼쪽 차량 클러스터 200px를 제외한 앱 영역은 1080×720이다.
- Pleos API 34 emulator-5554(2560×1440, density 160)에서 Compose 테스트 콘텐츠를 1080×720dp로 제한했다. 최종 5개 캡처는 이 앱 영역이며 전체 디스플레이 캡처가 아니다. 팝업은 별도 윈도우만 캡처했다.
- 기존 캡처는 1682×1210으로 조건이 달랐다. before-audit에 보존했으며 원본과 픽셀 차이율을 계산하지 않았다. 이번 최종 캡처와 원본 앱 영역을 같은 크기로 비교했다.
- 데이터는 천안 테스트 조건, 가상 방문지 2개, 당일 09:30 출발/18:00 귀가, 안전 여유 30분이다. 원본의 장소·시간·방문지 수와 다르므로 데이터와 콘텐츠 높이까지 동일하다고 판단하지 않았다.

## 화면별 비교

| 화면 / 원본 | 일치도와 근거 | 발견한 차이 / 처리 |
|---|---|---|
| 입력 / 기본 PNG, _03 | 부분 일치. 흰 배경, navy CTA, 약 2:1 좌우 열, 두 줄 제목을 유지 | 제목 구성 요소마다 부모 간격이 중복 적용되던 여백 제거. 선택 칩에 teal 테두리와 48dp 높이 적용. 음성 입력 대신 수동 필드·개발 안내, 이력 대신 Fake 설정이므로 완전 일치는 아님 |
| 로딩 / _04 | 부분 일치. 상단 중앙 로딩, 제목·조건·안내 순서 | 수직 중앙에서 상단 64dp 여백 배치로 변경. 원본의 사각 로더 대신 기존 원형 로더 유지. 실제 검색·에너지 검증 진행 표시를 복제하지 않음 |
| 결과 / _05 | 구조 일치, 콘텐츠 부분 일치. 2열 카드·navy CTA·teal 강조 | 카드 동일 높이와 버튼 정렬을 보장하고 긴 제목과 DEMO 배지 충돌 방지. 원본과 달리 귀가 정보를 카드 위에 배치해 검증 결과를 먼저 확인. 알찬형·여유형과 허위 방문 시각을 만들지 않음. 0/1안 원본은 없어 기능 검증으로 분리 |
| 상세 / _06 | 부분 일치. 약 2:1 열, 왼쪽 회색 귀가 안내, 오른쪽 일정, 고정 하단 선택 버튼 | 제목 아래 과도한 여백 제거. 배터리·휴게소 제안 대신 미검증 AI 설명·방문 안내 유지. 일정의 도착 절대시각과 출발 행은 현 UI에 없으며 임의 생성하지 않음 |
| 출발 준비 / 직접 대응 원본 없음 | 직접 일치도 판정 불가. _06의 선택 흐름과 HTML의 출발 전 확인을 참고 | 공통 색상·패널·버튼을 사용. 실제 내비 시작 비활성. 확인되지 않은 차량 상태나 자동 출발을 표시하지 않음 |

## 수정 범위

- app/src/main/java/com/tripback/app/trip/recommendation/RecommendationScreens.kt
  - 제목/설명 간격 그룹화, 단계 표시 3등분 중앙 정렬.
  - 최대 폭 제한을 fillMaxSize보다 먼저 적용하여 넓은 창에서 정상 작동.
  - 선택 칩·뒤로·기존 기능·보조 버튼 최소 48dp, 주요 CTA 50dp 유지.
  - 칩 선택 테두리, 비교 카드 높이, 긴 제목 줄바꿈/말줄임, 사각 취소 버튼.
  - 로딩 상단 배치와 스크롤, 하단 검토 버튼 테스트 태그.
- app/src/androidTest/java/com/tripback/app/RecommendationFlowTest.kt
  - 동일 비교 영역, 팝업 캡처, 2열 CTA 정렬, 취소 터치 높이, 하단 스크롤 도달 검증과 추가 캡처.
- android/FIGMA_UI_AUDIT.md: 이 보고서.

DTO·JSON 파서·Repository·상태 전이·Fake 데이터·기존 DEMO/NaviHelper 화면 코드는 이번에 변경하지 않았다.

## 검증

실행 명령:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest --offline
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w com.tripback.app.test/androidx.test.runner.AndroidJUnitRunner
git diff --check
```

- 단위 테스트: 73 성공, 실패 0, 오류 0, SKIPPED 0.
- Kotlin 컴파일 및 앱/계측 Debug APK: 성공. 기존 LocalLifecycleOwner deprecation 경고는 남아 있다.
- 에뮬레이터 계측: 6 성공(기존 1 + 추천 화면 5). 새 테스트 메서드 추가 없이 기존 5건의 검증을 강화했다.
- 검증 대상: 첫 5개 전환, 0/1/2안, UNKNOWN/null 귀가 정보, 잘못된 JSON, 취소, 복구 후 내비 비활성, 2열 CTA 높이 정렬, 하단 버튼 접근.
- 최초 실행에서 추가 비교 선택자의 인코딩 오류로 1건 실패했으며 testTag 선택자로 수정 후 전체 재실행했다.
- connectedDebugAndroidTest는 이번에 실행하지 않았다. 이전 환경의 오프라인 UTP 의존성 문제를 우회한 직접 ADB instrumentation 결과다.
- 실제 도로 안내, Vehicle SDK, 운전 상태 제어, 실제 API 또는 HTTP 연결은 테스트하지 않았다.

## 캡처

앱 프로젝트 기준 app/build/outputs/recommendation-evidence/:

- before-audit/: 이전 input/loading/results/detail/ready 캡처 원본.
- figma-audit/: input.png, loading.png, results.png, detail.png, ready.png.
- figma-audit/: input-bottom.png, results-bottom.png, ready-bottom.png, scenario-menu.png, one.png, zero.png, unknown.png, error.png.

스크롤 화면은 초기 캡처의 아래쪽 일부가 뷰포트 밖에 있다. 고정 버튼과 콘텐츠는 분리되어 있으며 결과 상세 CTA 및 출발 준비 검토 버튼에 실제 스크롤로 도달하는 것을 테스트했다. 전체 내용이 첫 화면에 보인다는 의미는 아니다.

## 남은 디자인/연동 과제

필수 수정한 항목은 제목 여백·선택 대비·터치 영역·넓은 화면 최대 폭이다. 선택적으로 원본 폰트/아이콘 자산 확보 후 정밀 조정, 개발용 필드와 안내의 제품용 정리, 실제 계약의 방문 시각 기반 타임라인이 필요하다. 원본 폰트 파일이나 Figma 노드/토큰은 제공되지 않아 픽셀 완전 일치를 주장하지 않는다.

Fake 고지, 실패 코스 비선택, null ETA 미확인, AI 설명 미검증, 실제 내비 비활성을 유지한다. 주차 확인은 기존 개발용 수동 확인이며 실제 주행 안전 제어가 아니다.

HTTP 연동 개발에 착수할 수 있으나 이번 결과는 실서버 검증 완료를 의미하지 않는다. 다음 단계에서 DTO 실응답 대조, 에뮬레이터 주소/접근 제어, 타임아웃·취소, 출처 표시를 검증해야 한다. 실제 API 실행은 별도 승인 범위다.

원본 백엔드 보호 대상 24개 파일은 SHA256 불일치 0개다. commit/push/PR 및 외부 API 호출은 수행하지 않았다.