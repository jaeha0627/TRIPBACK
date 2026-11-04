package com.tripback.app.trip

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TripValidationResultsScreen(result: ValidationResult, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(when (result.feasibility) {
            ValidationFeasibility.FEASIBLE -> "현재 ETA 기준 가능"
            ValidationFeasibility.INFEASIBLE -> "귀가 마감 또는 안전 여유시간 부족"
            ValidationFeasibility.UNKNOWN -> "귀가 가능 여부 확인 불가"
        }, style = MaterialTheme.typography.headlineMedium)
        Text("${result.feasibility} · ${result.dataCoverage}")
        Text("출처: ${result.dataModes.joinToString { sourceLabel(it) }.ifEmpty { "확인된 데이터 없음" }}")
        Text("공급자 데이터 검증은 모두 실시간 LIVE라는 뜻이 아닙니다. 실제 귀가 보장이 아니며 현재 출발 ETA는 미래 교통을 검증하지 않습니다.")
        Text("사용자 입력 출발 좌표: ${result.request.origin.latitude}, ${result.request.origin.longitude} · GPS 아님")
        Text("출발 ${result.request.departAt} / 귀가 마감 ${result.request.returnDeadline}")
        Text("안전 여유 ${result.request.safetyBufferMin}분")
        result.visits.forEach { visit ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("방문지 index ${visit.index}: ${visit.confirmedPlace?.name ?: "장소 미확인"}")
                Text("카카오 ID: ${visit.requestedId.provider} / ${visit.requestedId.value}")
                visit.confirmedPlace?.let { place ->
                    Text("확인 ID: ${place.id.value}"); Text("주소: ${place.address.ifEmpty { "제공되지 않음" }}")
                    Text("좌표: ${place.coordinates.latitude}, ${place.coordinates.longitude}")
                    Text(sourceText(place.data))
                }
                Text("체류 ${visit.stayMinutes}분 · ${visit.identityStatus}")
                Text("영업·주차·방문 가능성: ${visit.visitStatus}")
                visit.reason?.let { Text("장소 확인 사유: $it") }
                visit.failure?.let { Text("API 상태: ${it.code} / HTTP ${it.httpStatus ?: "없음"}") }
            } }
        }
        result.legs.forEach { leg ->
            val from = if (leg.index == 0) "출발지" else "방문지 ${leg.index}"
            val to = if (leg.index == result.visits.size) "최초 출발지로 귀가" else "방문지 ${leg.index + 1}"
            Text("구간 index ${leg.index}: $from → $to", style = MaterialTheme.typography.titleMedium)
            Text("자동차 이동시간: ${leg.durationSeconds?.let { "${it}초" } ?: "미확인"} · ${leg.status}")
            leg.data?.let { Text(sourceText(it)) }
            leg.reason?.let { Text("미검증 사유: $it") }
            leg.failure?.let { Text("API 상태: ${it.code} / HTTP ${it.httpStatus ?: "없음"}") }
        }
        Text("총 왕복 이동: ${result.totalTravelMinutes?.let { "${it}분" } ?: "미확인"}")
        Text("총 체류: ${result.totalStayMinutes?.let { "${it}분" } ?: "미확인"}")
        Text("예상 귀가: ${result.predictedReturnAt ?: "미확인"}")
        Text("마감 대비 여유: ${result.remainingMarginMin?.let { "${it}분" } ?: "미확인"}")
        Text("귀가 마감 충족: ${yesNo(result.returnDeadlineMet)} / 안전 여유 충족: ${yesNo(result.safetyBufferSatisfied)}")
        Text("ETA 누락 구간: ${result.missingEtaLegIndexes?.joinToString()?.ifEmpty { "없음" } ?: "계산 불가"}")
        Text("경로 검증 상태: ${result.routeDataStatus ?: "계산 불가"}")
        result.unknownReasons.forEach { Text("계산 미확인 사유: $it") }
        result.calculationFailure?.let { Text("계산 실패: $it") }
        Text(result.notice)
        Text("카카오 ID는 Pleos POI ID가 아닙니다. 내비게이션으로 전달하지 않습니다.")
        Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("검증 입력으로 돌아가기") }
    }
}
private fun yesNo(value: Boolean?) = when (value) { true -> "충족"; false -> "부족"; null -> "미확인" }
private fun sourceLabel(mode: ValidationDataMode) = when (mode) {
    ValidationDataMode.VERIFIED_LIVE -> "VERIFIED_LIVE (실시간 공급자 응답)"
    ValidationDataMode.VERIFIED_CACHED -> "VERIFIED_CACHED (저장된 검증 데이터)"
    ValidationDataMode.DEMO_SIMULATED -> "DEMO_SIMULATED (시연)"
}
private fun sourceText(data: ValidationSource) = "${sourceLabel(data.dataMode)} · ${data.verification} · 조회 ${data.retrievedAt}"
