# Tripback 개발 실행 계획
기준일: 2026-11-02 | 기준: 공모전 제출 기획서 v2 + prototype/Tripback_차량화면_시안_v5.html

## 목표
Tripback은 '가는 길 → 머무는 시간 → 오는 길' 전체를 관리하는 차량 연동형 AI 왕복 드라이브 플래너이다. Figma 시안은 다른 작업 흐름에서 제작 중이며, 실제 Android 앱 화면 설계의 후속 기준으로 합친다.

## 검증된 현재 상태 / 미확인 상태
- 있음: Private GitHub 저장소, prototype HTML v5, android/backend/docs 초기 폴더, 기획서, Android Studio 설치.
- 별도 확인 필요: Android 실제 Kotlin 프로젝트, Pleos 에뮬레이터 설치/부팅, 차량 SDK 호출, 서버와 AI의 실제 연결, 서명된 release APK.
- HTML v5는 규칙 기반 가상 장소/시간/차량 이벤트 시연이다. 실제 SDK/LLM 연동이라고 표현하지 않는다.

## 아키텍처
Android Kotlin UI + 앱 상태
  ↳ NavigationAdapter: Pleos NaviHelper 또는 명시된 DemoAdapter
  ↳ VehicleAdapter: 지원되는 차량 데이터 또는 명시된 DemoAdapter
  ↳ TripApiClient: HTTPS JSON
      ↳ Java Spring Boot
        ↳ 입력값 정규화·제약 조건 검증
        ↳ 후보 장소 검색(실제 공급자/검증된 캐시/시연 fixture 구분)
        ↳ AI 추천 요청 → 구조화 JSON 응답
        ↳ DeterministicScheduleEngine: 귀환 ETA/필수 방문지/안전 여유 검증
        ↳ 재계산 제안 및 사용자 승인 반영

## 환경 및 초기 결정
- Android Studio + Kotlin, minSdk 28 이상; Pleos 전용 Android API 34 에뮬레이터 사용 여부를 우선 검증.
- Jetpack Compose 네이티브 UI를 우선 검토. 기존 HTML은 행동 및 디자인 참고용.
- Java 17 또는 21 + Spring Boot 3.x (서버 권장안; Pleos 의무사항 아님).
- Codex: 저장소 루트 AGENTS.md 읽기 → 작은 태스크 단위 구현 → 빌드/테스트 → diff 리뷰 → PR.
- SDK는 NaviHelper 및 사용 가능한 Vehicle State부터. STT/TTS/FusedLocation 등은 권한·가용성 확인 전 필수 개발 의존성에 두지 않는다.

## API 협업 범위
- GET /api/v1/health : 백엔드 상태, dataMode.
- POST /api/v1/trips/plan : 여행 입력 -> 검증된 여행 코스 2개.
- POST /api/v1/trips/{tripId}/replan : 실제/시연 지연 정보 -> 일정 변경 *제안*.
- POST /api/v1/trips/{tripId}/approve : 사용자가 선택한 변경안 확정.
구체적인 JSON은 docs/api-contract.md를 기준으로 공동 확정한다.

## 두 사람의 책임
- Android/Pleos 담당: Kotlin 프로젝트, Figma 화면 재현, NaviHelper 경로 요청, 차량 이벤트 가용성 확인, APK/에뮬레이터 실제 시연.
- 대표자/PM·Backend 담당: Spring Boot REST API, LLM 응답 스키마, 장소/이동시간 검증, 귀가시간 계산, 기획서·제출 총괄.
- 공동: API 계약, 데모 데이터 표시 규칙, QA, 실제 기기/에뮬레이터 영상, GitHub README와 심사용 접근 확인.

## 권장 일정
| 기간 | Android/Pleos | Backend/PM | 완료 조건 |
| --- | --- | --- | --- |
| 11/02~04 | Git/Gradle, Pleos AVD 실행 | Spring /health, JSON 계약 | 앱↔서버 /health 요청 성공 |
| 11/05~08 | 입력·일정 카드·선택 화면 | AI 구조화 코스와 검증 | 코스 2안 실제 API로 수신 |
| 11/09~12 | NaviHelper, 도착/체류 상태 | ETA/귀환 재계산·승인 API | 지연→변경 제안→승인→경로 갱신 |
| 11/13~14 | 영상·빌드 검증 | 기획서·제출자료 검수 | 가산점 자료 진위 구분 및 준비 |
| 11/15 | 제출 점검 | 대표자 이메일 제출 | 공식 접수 완료 |
| 11/16~12/02 | SDK 안정화, release APK | 통합 테스트·PT | 실제 동작하는 앱 시연 |

## 우선순위와 범위
P0: Pleos 에뮬레이터 빌드/실행, 서버 API 연결.
P1: v5 여행 조건 입력 → AI 코스 2안 → 왕복 시간표 → 내비 안내 시작.
P2: 실제 SDK 이벤트(지원 시) → 체류시간·교통 지연 → 귀환 여유 판단 → 사용자 승인 후 수정.
P3: 귀가/여행 기록, 시연, 실행 가능한 release APK.
후속 개선: 전국 장소 품질, EV 에너지 데이터 정확도, STT/TTS, 가족 SMS, 정밀 주차정보. v5의 장기적 기능 범위는 유지하되, 검증 전에는 실기능으로 표현하지 않는다.

## 완료 조건
1. API 및 LLM 응답을 서버에서 JSON 스키마로 검증.
2. 임의 존재하지 않는 장소/좌표를 실제 위치로 내비에 넘기지 않음.
3. 예측 귀가시각+안전여유가 마감보다 늦으면 경고 또는 재계산.
4. 필수 장소는 자동 삭제 불가, 사용자 승인 전 경로 변경 불가.
5. SDK 미지원·네트워크 단절·잘못된 JSON 발생 시 fallback.
6. 실제 Pleos 에뮬레이터에서 설치·실행·SDK 로그를 확인.
7. APK는 빌드하고 구동 검증한 경우에만 apk/에 release로 등록.

## 심사 및 제출
- 1차 기준: 차량특화성/창의성/기술 활용성/사업성 (세부 배점 미공개).
- 제출: 공식 접수양식·개인정보동의 필수, 시연 영상·GitHub+APK 선택/가산.
- 1차 접수 2026-11-15 23:59 KST; 2차 PT 전 실제 동작 앱 필요.
- Private 저장소의 심사용 계정 초대 필요(공식 공지 확인).
- signed application 원본/전화번호/이메일/인증키는 저장소에 올리지 않는다.

공식 제출 안내: https://appdevchallenge.auto-hmg.io/guide/submission
심사: https://appdevchallenge.auto-hmg.io/guide/judging-awards
공식 SDK: https://document.pleos.ai/
