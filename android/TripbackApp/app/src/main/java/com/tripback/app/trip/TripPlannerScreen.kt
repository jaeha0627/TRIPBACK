package com.tripback.app.trip

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.content.pm.ApplicationInfo
import androidx.compose.ui.unit.dp
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TripPlannerScreen(sdkContent: @Composable () -> Unit) {
    val debug = LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    var screen by rememberSaveable { mutableStateOf("INPUT") }
    var input by rememberSaveable { mutableStateOf(TripInput()) }
    var schedule by rememberSaveable { mutableStateOf(TripSchedule()) }
    var results by rememberSaveable { mutableStateOf<TripPlanResults?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var requestJob by remember { mutableStateOf<Job?>(null) }
    val gate = remember { PlanRequestGate() }
    val api = remember { TripPlanApi() }
    val scope = rememberCoroutineScope()

    fun invalidateRequest() {
        gate.invalidate()
        requestJob?.cancel()
        requestJob = null
        loading = false
        error = null
    }

    fun navigate(target: String) { invalidateRequest(); screen = target }

    BackHandler(enabled = screen != "INPUT") { navigate("INPUT") }
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { navigate("INPUT") }, enabled = screen != "INPUT") { Text("여행 조건") }
                Button(onClick = { navigate("SDK") }, enabled = screen != "SDK") { Text("SDK 테스트") }
            }
            if (debug) Button(onClick = { navigate("VALIDATION") }, enabled = screen != "VALIDATION",
                modifier = Modifier.padding(horizontal = 16.dp)) { Text("왕복 검증 (개발)") }
            when (screen) {
                "VALIDATION" -> TripValidationScreen(Modifier.weight(1f))
                "SDK" -> Box(Modifier.weight(1f)) { sdkContent() }
                "RESULTS" -> results?.let { current ->
                    TripPlanResultsScreen(current, onSelect = { results = current.select(it) },
                        onBack = { navigate("INPUT") }, modifier = Modifier.weight(1f))
                }
                else -> TripInputScreen(
                    input = input,
                    onChange = { invalidateRequest(); input = it; results = null; schedule = schedule.copy(confirmed = false) },
                    schedule = schedule,
                    onScheduleChange = { invalidateRequest(); schedule = it; results = null },
                    loading = loading,
                    requestError = error,
                    onShowPlans = {
                        val deviceZone = ZoneId.systemDefault().id
                        if (schedule.zoneId != deviceZone) {
                            invalidateRequest()
                            schedule = schedule.copy(zoneId = deviceZone, confirmed = false)
                            error = "기기 시간대가 바뀌었습니다. 날짜와 시간대를 다시 확인해 주세요."
                        } else if (schedule.validationErrors(input).isEmpty()) {
                            val token = gate.begin()
                            if (token != null) {
                                val inputSnapshot = input
                                val scheduleSnapshot = schedule
                                loading = true
                                error = null
                                requestJob = scope.launch {
                                    try {
                                        val received = withContext(Dispatchers.IO) { api.request(inputSnapshot, scheduleSnapshot) }
                                        if (gate.complete(token)) {
                                            loading = false
                                            results = received
                                            screen = "RESULTS"
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        if (gate.complete(token)) {
                                            loading = false
                                            error = when (failure) {
                                                is TripHttpException -> "서버 요청 실패: HTTP ${failure.statusCode}"
                                                is TripResponseException -> "서버 응답 파싱 실패: JSON 구조 또는 DEMO 상태를 확인해 주세요."
                                                is SocketTimeoutException -> "서버 연결 또는 읽기 시간이 초과되었습니다."
                                                is IOException -> "서버 연결 실패: 개발 서버와 네트워크를 확인해 주세요."
                                                else -> "요청을 처리하지 못했습니다. 입력 날짜·시간·조건을 확인해 주세요."
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    onLocalDemo = {
                        if (schedule.validationErrors(input).isEmpty()) {
                            invalidateRequest()
                            results = demoTripPlanResults(input).copy(schedule = schedule)
                            screen = "RESULTS"
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
