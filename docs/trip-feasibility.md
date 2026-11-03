# 독립 귀가시간·안전 여유 계산 엔진

`com.tripback.backend.trip.feasibility`의 순수 Java 모듈입니다. Java 25 / 기존 Spring Boot 4.1.2 환경에서 실행되며 새 의존성, REST 엔드포인트, Spring bean, 지도/AI/DB/네트워크 연결은 없습니다.
`TripPlanService`와 **미연동**입니다. 기존 plan API의 `predictedReturnAt`, `travelMinutes`, `isFeasible`, `remainingMarginMin`은 계속 null입니다.

## 입력과 구간
- `TripFeasibilityInput`: OffsetDateTime 출발/귀가 마감, 0 이상 long 분 단위 안전 여유, 방문 순서의 체류시간 목록, TravelLeg 목록.
- 문자열 진입점 `fromIso8601`은 UTC 오프셋이 있는 ISO 8601 날짜·시간만 허용합니다. 자정 넘김 및 서로 다른 오프셋을 지원합니다. 마감이 출발보다 이르면 잘못된 입력입니다.
- 독립 엔진에는 기존 API의 2시간/15·30·45분 입력 제약을 적용하지 않습니다. 이 모듈은 일정의 실제 총량과 지정 안전 여유만 계산합니다.
- 방문지가 N개이면 구간 번호는 **0~N, 총 N+1개**입니다. 0은 출발지→첫 방문지, 1~N-1은 방문지 사이, N은 마지막 방문지→출발지입니다. 입력 목록의 물리적 순서 대신 명시적 index로 누락을 찾습니다.
- 방문지가 없더라도 0번 귀가 구간은 명시해야 합니다. 값이 0인 ETA와 ETA 누락은 다릅니다.
- 출발지/방문지 실제 식별·좌표·필수 방문지 집합은 외부에서 검증해야 합니다. 엔진은 받은 방문지를 삭제/축소하거나 누락 구간의 시간을 생성하지 않습니다.

## 계산 규칙과 정밀도
1. 전체 이동분 = **귀가 구간을 포함한** 모든 구간 ETA 합.
2. 전체 체류분 = 모든 방문지 체류시간 합.
3. 예상 귀가 = 출발 Instant + (전체 이동분 + 전체 체류분). 표시용 OffsetDateTime은 출발 오프셋을 유지합니다.
4. 남은 여유 = 마감 Instant - 예상 귀가 Instant. `remainingMargin`은 나노초 정밀도의 Duration입니다.
5. `returnDeadlineMet`는 여유 >= 0, `safetyBufferSatisfied`는 여유 >= 안전 여유입니다. **정확히 같은 경우 충족**합니다.
6. `feasibility`의 FEASIBLE/INFEASIBLE은 안전 여유를 포함한 **수학적 판정**입니다. 마감 전 귀가라도 안전 여유가 부족하면 INFEASIBLE입니다. 실제 경로 검증 성공을 뜻하지 않습니다.
7. 충족 판정에는 반올림이 없습니다. 초/나노초를 포함한 Duration을 비교합니다.
8. 표시용 `remainingMarginMin`은 **음의 무한대 방향 내림**입니다. +59.999초→0분, -1나노초→-1분, -60초→-1분, -60.001초→-2분입니다. 0분 표시를 판정에 사용하면 안 됩니다.
9. 분 합은 `Math.addExact`, 초 변환은 `Math.multiplyExact`로 검사하며 날짜·시간 표현 범위도 검사합니다. 초과 시 IllegalArgumentException입니다. 임의 상한으로 자르거나 래핑하지 않습니다.

## UNKNOWN과 잘못된 입력
- 필수 index 누락 또는 minutes=null인 구간이 하나라도 있으면 **UNKNOWN**입니다. 전자는 MISSING_REQUIRED_LEG, 후자는 MISSING_ETA로 구분합니다.
- 구간 수가 N+1보다 적으면 LEG_COUNT_MISMATCH도 기록합니다. 귀가 구간만 빠진 경우에도 UNKNOWN이며 누락 index를 반환합니다.
- UNKNOWN의 전체 이동분·예상 귀가·정확한/분 단위 여유·마감 충족·안전 여유 충족은 모두 null입니다. 알려진 전체 체류분과 제공된 출처 메타데이터는 유지합니다. 미검증 부분 이동시간 합을 완성된 이동시간으로 노출하지 않습니다.
- 음수, 누락 체류시간, null 구간 객체, 중복/범위 밖 index(과다 구간 포함), 잘못된 날짜·시간, 오버플로는 IllegalArgumentException입니다. UNKNOWN은 ETA 부족을 의미하며 잘못된 입력을 숨기는 용도가 아닙니다.

## ETA 출처와 수학적 계산의 분리
- 각 TravelLeg는 source, dataMode, verification, recordedAt을 가진 EtaEvidence를 보존합니다. 실제 공급자 연결·원본 응답 대조·캐시 신선도 검사는 아직 없습니다.
- 테스트 숫자는 DEMO_SIMULATED / UNVERIFIED입니다. 계산에 성공해도 VERIFIED_LIVE/CACHED로 승격하지 않습니다.
- VERIFIED_LIVE/CACHED 표시는 호출자가 VERIFIED와 기록 시각을 함께 선언해야 합니다. DEMO_SIMULATED에 VERIFIED 표시는 거부합니다.
- `routeDataStatus`: INCOMPLETE / UNVERIFIED / ALL_ETAS_DECLARED_VERIFIED. 마지막 상태도 **호출자가 모든 ETA를 검증됐다고 선언했다는 뜻**이며 엔진 자체의 실측 검증이 아닙니다. 한 구간이라도 DEMO이면 UNVERIFIED입니다.
- 평가에 사용한 원래 구간/출처 목록은 결과의 evaluatedLegs에 보존하며 입력과 결과 목록은 불변 스냅샷입니다.

실제 여행의 귀가 가능성 보장은 아직 제공하지 않습니다. 실경로·POI·필수 방문지·교통/ETA 출처 검증을 연결하고 별도로 통합 검증하기 전에는 기존 API에 계산값을 넣으면 안 됩니다.
