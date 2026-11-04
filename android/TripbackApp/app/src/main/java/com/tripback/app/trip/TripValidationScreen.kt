package com.tripback.app.trip

import android.content.pm.ApplicationInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.time.ZoneId

@Composable
fun TripValidationScreen(modifier: Modifier = Modifier) {
    val debug = LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    if (!debug) { Text("왕복 검증은 debug 개발 환경 전용입니다."); return }
    var form by rememberSaveable { mutableStateOf(ValidationForm()) }
    var inputError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val session = remember { TripValidationSession(scope, TripValidationApi(allowLocalHttp = true)::request) }
    val state by session.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, session) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) session.cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); session.cancel() }
    }
    BackHandler(enabled = state.result != null || state.loading) { session.cancel() }
    fun change(next: ValidationForm) { session.cancel(); inputError = null; form = next }
    val result = state.result
    if (result != null) {
        TripValidationResultsScreen(result, onBack = { session.cancel() }, modifier = modifier)
        return
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("왕복 검증 (개발)", style = MaterialTheme.typography.headlineMedium)
        Text("사용자가 입력한 출발 좌표입니다. GPS 현재 위치가 아닙니다.")
        Text("주차 상태 전용 · 차량 주행 제한 상태 자동 확인은 아직 연결되지 않았습니다.")
        ValidationField("출발지 위도", form.latitude) { change(form.copy(latitude = it)) }
        ValidationField("출발지 경도", form.longitude) { change(form.copy(longitude = it)) }
        ValidationField("여행 날짜 (YYYY-MM-DD)", form.schedule.date) {
            change(form.copy(schedule = form.schedule.copy(date = it, confirmed = false)))
        }
        ValidationField("시간대 (예: Asia/Seoul)", form.schedule.zoneId) {
            change(form.copy(schedule = form.schedule.copy(zoneId = it, confirmed = false)))
        }
        ValidationField("출발시간 (HH:mm)", form.departTime) {
            change(form.copy(departTime = it, schedule = form.schedule.copy(confirmed = false)))
        }
        ValidationField("귀가 마감시간 (HH:mm, 같은 날)", form.deadlineTime) {
            change(form.copy(deadlineTime = it, schedule = form.schedule.copy(confirmed = false)))
        }
        Text("안전 여유시간")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 45).forEach { minutes -> FilterChip(selected = form.safetyBufferMin == minutes,
                onClick = { change(form.copy(safetyBufferMin = minutes)) }, label = { Text("${minutes}분") }) }
        }
        Text("방문지 1개 · 선택 ID와 일치하는 검색 결과만 사용합니다.")
        ValidationField("카카오 장소 검색어", form.keyword) { change(form.copy(keyword = it)) }
        ValidationField("사용자가 선택한 카카오 장소 ID", form.placeId) { change(form.copy(placeId = it)) }
        ValidationField("체류시간 (0 이상 정수 분)", form.stayMinutes) { change(form.copy(stayMinutes = it)) }
        Row { Checkbox(checked = form.schedule.confirmed, onCheckedChange = {
            change(form.copy(schedule = form.schedule.copy(confirmed = it)))
        }); Text("입력한 여행 날짜·시간대·시각을 확인했습니다.") }
        Row { Checkbox(checked = form.parkedConfirmed, onCheckedChange = { change(form.copy(parkedConfirmed = it)) });
            Text("주차 상태에서 개발 검증을 실행합니다.") }
        Text("현재 ETA 기반 수학적 계산이며 실제 귀가·미래 교통·영업·주차 가능성을 보장하지 않습니다.")
        inputError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.loading) {
            CircularProgressIndicator()
            Text("서버 검증 중 · 입력을 변경하면 현재 요청을 취소합니다.")
            Button(onClick = session::cancel) { Text("요청 취소") }
        }
        Button(enabled = !state.loading, onClick = {
            try { session.submit(form.request()); inputError = null }
            catch (_: Exception) { inputError = "좌표, 날짜·시간대 확인, 같은 날의 출발 이후 마감, 검색어·선택 ID, 정수 체류시간과 주차 확인을 점검해 주세요." }
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text("서버에서 왕복 검증") }
    }
}

@Composable
private fun ValidationField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}
