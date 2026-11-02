# Tripback (트립백)

**AI 기반 차량 연동형 왕복 드라이브 플래너 · Pleos Connect 공모전 출품 프로젝트**

> 출발부터 방문지 체류, 돌아오는 길까지 하나의 일정으로 연결합니다. 귀가시간이 위험해지면 선택 방문지의 단축·제외를 제안하고 운전자의 승인 후 계획을 갱신합니다.

## 개발 현황 (2026-11-02 기준)
- ✅ [차량화면 HTML v5 프로토타입](prototype/Tripback_차량화면_시안_v5.html) — 가상 장소/시간/주행 이벤트를 사용한 **시연용 목업**
- ✅ 앱·서버 역할과 API 초안 및 개발 계획 문서
- ⏳ Android Studio Kotlin 실제 앱 — 개발 예정/실행 확인 전
- ⏳ Spring Boot·실제 LLM·관광정보/길찾기 API — 연동 확인 전
- ⏳ Pleos NaviHelper/Vehicle SDK — 실제 SDK 호출 확인 전
- ⏳ Android 릴리스 APK — 빌드 및 실행 확인 전
- 🎨 Figma 디자인 — 별도 작업 중

**중요:** HTML v5가 동작해도 실제 AI, 실시간 교통, GPS/차량 제어, 실제 문자 전송이 구현된 것은 아닙니다. 실연동과 시뮬레이션은 앱과 시연 영상에서 명확히 구분합니다.

## 핵심 제품 흐름
1. 여행 목적·동행자·기간/시간·귀가 마감·차량 종류·관심사 입력
2. 서버에서 조건을 정제하고 검증된 장소 후보로 AI에 구조화된 코스 생성 요청
3. 시간·좌표·필수 장소·안전 여유를 검증한 두 가지 코스(가능한 범위 내) 제안
4. 사용자 선택 → Pleos NaviHelper 길안내 시작
5. 도착·체류·지연·에너지 상황에 맞춰 귀환 예상시간 재평가
6. 변경안 제안 → **사용자 승인 후** 다음 경로 갱신 → 귀가 및 여행 기록

## 구현 기술
| 담당 | 스택 | 주요 업무 |
|---|---|---|
| Android/Pleos | Android Studio, Kotlin, Jetpack Compose(권장), Pleos Connect | UI, 경로 요청, 차량 상태 및 주행 이벤트 |
| Backend/AI | Java 17/21, Spring Boot 3.x, LLM API, POI/ETA 공급자 | 구조화 추천, 장소 검증, 결정적 일정·귀가시간 엔진 |
| 협업 | GitHub, Codex, Figma | API 계약·코드 리뷰·테스트·시연 |

## 디렉터리
```text
TRIPBACK/
├── prototype/                 # 차량용 HTML v5 (행동·화면 참고, 시뮬레이션)
├── android/                   # Kotlin/Pleos 프로젝트 추가 예정
├── backend/                   # Java/Spring Boot 프로젝트 추가 예정
├── docs/                      # 개발계획·API·SDK·QA·시연 문서
├── AGENTS.md                  # Codex 작업 규칙
├── .gitignore                 # 빌드 산출물/비밀정보 제외
└── README.md
```

## 실행 방법
**현재 검증된 시연:** 저장소에서 `prototype/Tripback_차량화면_시안_v5.html`을 내려받아 PC 브라우저에서 엽니다. 장소·교통·차량/AI 동작은 가상 데이터 기반입니다.

**Android 앱 실행:** 현재 실제 Kotlin 프로젝트/Gradle Wrapper가 등록되지 않아 실행 방법을 제공할 수 없습니다. 추후 `android/`에 프로젝트가 올라오면 SDK 버전, Pleos AVD, 빌드 명령과 설치 확인 결과를 이 문서에 기록합니다.

**Spring Boot 실행:** 실제 서버 프로젝트가 올라오면 Java 버전, 환경변수 구성, `/api/v1/health` 검증 명령을 기록합니다.

**APK:** 실제 릴리스 APK를 빌드하고 Pleos 에뮬레이터에서 설치/실행 검증을 끝낸 경우에만 `apk/` 경로와 파일명을 이곳에 추가합니다. 현재 다운로드 가능한 실행 APK는 없습니다.

## 개발 문서
- [개발계획](docs/Tripback_Development_Plan.md)
- [앱↔백엔드 API 계약 초안](docs/api-contract.md)
- [SDK 가용성 검증표](docs/sdk-capability-matrix.md)
- [시연 시나리오](docs/demo-script.md)
- [검증 테스트 항목](docs/test-cases.md)
- [Codex 지침](AGENTS.md)

## 개발 규칙
- 최종 기획서 v2, 차량화면 HTML v5를 제품 범위의 기준으로 사용합니다.
- 기능별 작업 브랜치에서 개발하고 PR 리뷰 후 통합합니다.
- API 키, 연락처, 서명된 지원서, keystore, `.env` 파일을 커밋하지 않습니다.
- 코드가 SDK를 실제 호출해 성공한 경우에만 'Pleos 연동 완료'로 표기합니다.

## 공식 링크
- [공모전 홈페이지](https://appdevchallenge.auto-hmg.io/)
- [공식 제출 안내](https://appdevchallenge.auto-hmg.io/guide/submission)
- [Pleos 공식 개발 문서](https://document.pleos.ai/)
