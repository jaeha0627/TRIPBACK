package com.tripback.app.trip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TripPlanResultsScreen(
    results: TripPlanResults,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("여행 코스 예시 2가지", style = MaterialTheme.typography.headlineMedium)
        Text(if (results.source == PlanSource.SERVER_DEMO) "서버에서 받은 시연 코스" else "로컬 시연 코스 · 서버 응답 아님")
        Text("${results.dataMode.name} · 실제 AI 추천 연결 전")
        results.tripId?.let { Text("tripId: $it") }
        Text(results.validationStatus)
        Text(results.notice)
        results.schedule?.let { Text("여행 날짜: ${it.date} · 시간대: ${it.zoneId}") }
        Text("입력과 무관한 고정 시연 코스입니다. 모든 장소는 가상이며 실제 방문 장소가 아닙니다.")
        Text("선택한 여행 조건", style = MaterialTheme.typography.titleLarge)
        Text(results.input.summary())
        Text("예산·식사·걷기·주차·편의시설·우천 선호는 이번 시연 추천에 미반영입니다.")
        Text("서버 경로·시간 검증 전", style = MaterialTheme.typography.titleLarge)
        Text("예상 귀가시간·이동시간·귀가 가능성은 미확인입니다. 체류시간도 시연 값이며, 선택해도 실제 안내는 시작하지 않습니다.")
        results.alternatives.forEachIndexed { index, plan ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("코스 ${if (index == 0) "A" else "B"} · ${plan.title}", style = MaterialTheme.typography.titleLarge)
                    Text(plan.dataMode.name)
                    Text(plan.description)
                    plan.stops.forEachIndexed { stopIndex, stop ->
                        Text("${stopIndex + 1}. ${stop.name} · ${stop.category} · 체류 ${stop.stayMinutes}분 (시연)")
                    }
                    Text("마지막 방문 후 출발지로 귀가 · 경로·시간 미검증")
                    Button(
                        onClick = { onSelect(plan.planId) },
                        enabled = results.selectedPlanId != plan.planId,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(if (results.selectedPlanId == plan.planId) "${plan.title} 선택됨" else "${plan.title} 선택 · 상세 일정 보기") }
                }
            }
        }
        val selected = results.selectedPlan
        if (selected == null) {
            Text("코스 A 또는 B를 선택하면 상세 일정이 표시됩니다.")
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("선택한 ${selected.title} · 상세 일정", style = MaterialTheme.typography.titleLarge)
                    Text("DEMO_SIMULATED · 출발 ${results.input.departTime}은 사용자 입력이며 도착시각은 계산하지 않았습니다.")
                    Text("출발지 (검색·확정 전)")
                    selected.stops.forEachIndexed { index, stop ->
                        Text("${index + 1}. ${stop.name}", style = MaterialTheme.typography.titleMedium)
                        Text("${stop.category} · 체류 ${stop.stayMinutes}분 (시연) · ${stop.dataMode.name}")
                        Text(stop.description)
                    }
                    Text("출발지로 귀가 · 마감 ${results.input.returnDeadline}은 사용자 희망 조건이며 달성 여부는 미확인")
                    Text("이동 구간·도착/출발시각·안전 여유 충족 여부: 서버 경로·시간 검증 전")
                }
            }
        }
        Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("여행 조건으로 돌아가기") }
    }
}
