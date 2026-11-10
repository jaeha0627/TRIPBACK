# NaviHelper 공식 예제 POI 수동 Debug 검증 계획

상태: **계획 작성 완료 / 실제 requestRoute 미실행 / 사용자 승인 대기**.
추천 장소 내비 시작은 계속 비활성이다. 이 계획은 카카오→Pleos 매핑 검증을 대신하지 않는다.
기존 SDK 테스트 메뉴의 버튼도 이번 작업에서는 누르지 않았다.

## 실행 전 사용자에게 확인할 구체적인 범위

Pleos API 34 에뮬레이터 한 대에서 공식 예제 42dot 목적지로 단일 requestRoute를 한 번 전송한다.
실제 추천안·카카오 ID·Fake ID는 사용하지 않는다. 기존 경로가 있다면 대체될 수 있으므로
진행 중인 경로 유무를 먼저 확인하고, 대체가 발생할 경우 그 범위까지 별도로 승인받는다.
외부 OpenAI·카카오 공급자 호출은 하지 않는다. 실제 차량에서는 실행하지 않는다.

## 공식 근거와 입력

[공식 목적지 예제](https://document.pleos.ai/api-reference/connect-sdk-pleos/NaviHelper/destinations),
[초기화](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/NaviHelper/initialization-and-release),
[권한](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/NaviHelper/declaring-permissions).
설치 NaviHelper 3.0.5 + navi-data 3.0.4 대조 완료. 실행 직전 최신 문서와 같은 값인지 다시 확인한다.

```kotlin
RouteInfo(
    latitude = 37.48544722,
    longitude = 127.03636666,
    poiName = "42dot",
    poiId = "36khp37hh8gqcm5nqhe5",
    address = "서울시 강남구 남부순환로 2621",
    poiSubId = "0"
)
```

공식 예제의 식별자·좌표이며 현재 장소/진입점의 유효성을 현장 인증한 데이터는 아니다.

## 승인 후 실행 순서

1. Debug APK 여부, 에뮬레이터 API/빌드/패키지, 기존 목적지 상태를 기록한다.
2. 실제 CarUxRestrictions 초기 조회가 성공하고 DO 요구 false, activeRestrictions=0인지 확인한다.
   미확인/제한 중에는 버튼을 잠근다. DO를 추가하거나 제한을 우회하지 않는다.
3. NAVI_ROUTE_SEARCH와 조회용 NAVI_ROUTE 권한을 확인한다.
4. initialize(onConnected,onFailed) 연결 성공 후 리스너를 등록한다.
5. 화면에 위 목적지의 이름·좌표·Pleos ID와 경로 대체 가능성을 표시한다.
6. 사용자 명시 승인 후 한 번만 requestRoute(RouteInfo)를 호출한다. 회전/재생성/자동화로 재전송하지 않는다.
7. onRouteStarted, onRoutingCompleted, onDestinationChanged, onError를 순서·시각과 함께 기록한다.
8. getDestinationInfo()/onDestinationInfo로 요청 목적지와 일치하는지 확인한다.
   requestRoute 반환이나 onDestinationChanged만으로 성공 판정하지 않는다.
9. 30초 내 콜백이 없으면 결과 미확인으로 기록한다. 자동 재시도하지 않는다.
10. 실제 내비 화면/안내 상태와 목적지 일치까지 관찰된 경우에만 그 범위의 성공을 보고한다.
    도착 콜백이 없으면 도착 검증 미완료로 남긴다. 종료 시 listener 제거와 release를 수행한다.

실패·권한 오류·콜백 미수신·Navi Service만 연결되고 지도 앱이 응답하지 않는 경우를 구분한다.
경로 취소도 사용자 경로를 바꾸는 동작이므로 승인한 정리 범위 안에서만 수행한다.
로그에는 키·요청 헤더·개인 위치를 넣지 않는다.

## Pleos 공식 지원팀 확인 질문 (전송하지 않음)

- RouteInfo의 poiId/poiSubId는 어떤 공급자 namespace와 포맷을 요구하는가?
- KAKAO_LOCAL 원본 ID를 공식 Pleos POI ID로 변환하거나 동일 장소를 검색하는 공개 API/SDK가 있는가?
- 좌표 단독 요청을 공식 지원하는가? 지원한다면 poiId/poiSubId/poiProvider에 필요한 정확한 값은 무엇인가?
  빈 문자열·임의 ID를 추측해서 보내지 않는다.
- 좌표는 WGS84이며 차량 진입점과 display 좌표의 의미·우선순위는 무엇인가?
- 에뮬레이터/서비스/지도 앱 버전별 지원 차이, 필요한 권한/앱 등록/서명 조건은 무엇인가?
- 경로 탐색 완료와 실제 안내 시작을 구분하는 공식 콜백·상태 필드는 무엇인가?
- 이벤트에 request ID가 없을 때 여러 앱의 요청·외부 목적지 변경과 안전하게 상관시키는 방법은 무엇인가?
- 목적지 및 경유지 도착 콜백의 ID/좌표 연계와 재연결 시 이벤트 재전달 규약은 무엇인가?

공식 회신·근거와 에뮬레이터 실측을 기록하기 전까지 추천 장소 내비를 활성화하지 않는다.
