package com.tripback.app.trip.replan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tripback.app.trip.progress.*
import com.tripback.app.trip.recommendation.RecommendationRequest
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter

private val PreviewNavy = Color(0xFF002C5F)
private val PreviewTeal = Color(0xFF007FA8)
private val PreviewLine = Color(0xFFD9DEE3)

/** Default UI wiring deliberately constructs only the in-process Fake. No HTTP toggle is exposed. */
@Composable
fun JourneyReplanDebug(journey: TripJourney, request: RecommendationRequest?, raw: String?, selectedPlanId: String?) {
    val scope = rememberCoroutineScope()
    val clock = remember { Clock.systemUTC() }
    val repository = remember(journey) { FakeReplanUiRepository(clock) }
    val controller = remember(journey, request, raw, selectedPlanId) {
        ReplanDebugController(scope, journey, request, raw, selectedPlanId, repository, clock)
    }
    val progress by journey.state.collectAsState()
    val state by controller.state.collectAsState()
    val total by controller.totalStayMinutes.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var open by remember(controller) { mutableStateOf(false) }
    var fixtureOpen by remember(controller) { mutableStateOf(false) }
    var scenario by remember(controller) { mutableStateOf(ReplanUiScenario.NORMAL) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) controller.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.close() }
    }
    LaunchedEffect(controller, progress.phase, open) {
        while (open) { tick++; delay(1000) }
    }
    @Suppress("UNUSED_VARIABLE") val refresh = listOf(progress.revision, state.phase, total, tick)
    val view = controller.view()
    OutlinedButton(onClick = { open = true }, shape = RectangleShape, modifier = Modifier.heightIn(min = 48.dp).testTag("openReplanPreview")) {
        Text("재계획 Preview · Debug / FAKE")
    }
    OutlinedButton(onClick = { fixtureOpen = true }, shape = RectangleShape,
        modifier = Modifier.heightIn(min = 48.dp).testTag("openReplanFixture")) {
        Text("검증용 Fixture 시나리오 · Debug")
    }
    if (fixtureOpen) ReplanFixtureScreen(onExit = { fixtureOpen = false })
    if (open) Dialog(onDismissRequest = { controller.dismiss(); open = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).widthIn(max = 1120.dp).fillMaxHeight(.94f),
            shape = RectangleShape, color = Color.White, border = BorderStroke(1.dp, PreviewLine)) {
            ReplanDebugPanel(view, controller::changeTotal, { controller.register(true) }, { controller.preview() },
                controller::stop, { controller.dismiss(); open = false }, scenario,
                { scenario = it; controller.scenario(it) })
        }
    }
}

/** Pure presentation boundary, also usable by compiled Compose UI tests without MainActivity or SDK. */
@Composable
fun ReplanDebugPanel(view: ReplanDebugView, onTotal: (Long) -> Unit, onRegister: () -> Unit,
    onPreview: () -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit,
    scenario: ReplanUiScenario, onScenario: (ReplanUiScenario) -> Unit, fixtureHeader: @Composable () -> Unit = {}) {
    var confirmation by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("replanDebugPanel"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("일정 변경 미리보기", color = PreviewNavy, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                Text("머무는 시간 → 오는 길", color = PreviewTeal) }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("closeReplanPreview")) { Text("닫기 ×") }
        }
        Text("PREVIEW_ONLY · FAKE Repository · 외부 HTTP/Provider 호출 0회", color = PreviewTeal, modifier = Modifier.testTag("previewOnly"))
        Text("실제 차량 진행 상태 미검증 · 서버 일정 확정 아님", modifier = Modifier.testTag("notServerApproved"))
        fixtureHeader()
        BoxWithConstraints {
            val content: @Composable () -> Unit = {
                PreviewCard {
                    Text(view.currentName, fontSize = 26.sp, color = PreviewNavy)
                    Text("선택 코스: ${view.title}")
                    Text("체류 경과 · 수동 DEMO 관측")
                    Text(view.elapsedSeconds?.let(::durationText) ?: "UNKNOWN", fontSize = 44.sp, color = PreviewNavy, modifier = Modifier.testTag("previewElapsed"))
                    Text("기존 총 체류 ${view.originalStayMinutes}분 · 현재 단계 ${view.phase}")
                    Text("변경하려는 총 체류 ${view.requestedTotalMinutes}분", color = PreviewNavy, fontSize = 20.sp, modifier = Modifier.testTag("requestedTotal"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onTotal((view.requestedTotalMinutes - 15).coerceAtLeast(0)) },
                            shape = RectangleShape, modifier = Modifier.heightIn(min = 48.dp).testTag("totalMinus15")) { Text("−15분") }
                        OutlinedButton(onClick = { onTotal((view.requestedTotalMinutes + 15).coerceAtMost(1440)) },
                            shape = RectangleShape, modifier = Modifier.heightIn(min = 48.dp).testTag("totalPlus15")) { Text("+15분") }
                        OutlinedButton(onClick = { onTotal(50) }, shape = RectangleShape,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("total50")) { Text("총 50분") }
                    }
                    Text("추가 시간이 아닌 총 계획시간입니다. 이 조작으로 일정은 바뀌지 않습니다.")
                }
            }
            val itinerary: @Composable () -> Unit = {
                PreviewCard {
                    Text("오늘의 일정 · ${view.title}", color = PreviewNavy, fontWeight = FontWeight.Medium)
                    view.visits.forEachIndexed { i, (name, minutes) ->
                        Text("${i + 1}. $name", color = if (i == view.currentIndex) PreviewTeal else Color.DarkGray)
                        Text("머무는 시간 ${minutes}분 · 원래 순서 유지", fontSize = 13.sp)
                        HorizontalDivider(color = PreviewLine)
                    }
                    Text("귀가 마감 ${timeText(view.deadline.toInstant())}")
                    Text("안전 버퍼 ${view.bufferMinutes}분 · 귀가 지점 유지")
                }
            }
            if (maxWidth >= 800.dp) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(.66f)) { content() }; Column(Modifier.weight(.34f)) { itinerary() }
            } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { content(); itinerary() }
        }
        PreviewCard {
            Text("서버 검증 계획 등록 상태 · FAKE 흐름", color = PreviewNavy, fontWeight = FontWeight.Medium)
            Text(view.requestState.registrationStatus?.let { "$it · 서버 등록 아님" } ?: "미등록", modifier = Modifier.testTag("registrationStatus"))
            Text("계획 참조: ${when { view.requestState.phase == ReplanRequestPhase.EXPIRED -> "만료됨"; view.requestState.expiresAt != null -> "30분 TTL · " + timeText(view.requestState.expiresAt) + " 만료"; else -> "사용 가능한 참조 없음" }}")
            Text("ETA 유효기간 15분 · 만료·실패 시 자동 재등록/재시도 없음")
            view.etaNotice?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("previewEtaExpired")) }
            view.blockedReason?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("replanBlocked")) }
            view.requestState.error?.let { Text("${errorText(it)} · ${it.name}", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("replanError")) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { confirmation = true; confirmed = false }, enabled = view.canRegister,
                    shape = RectangleShape, colors = ButtonDefaults.buttonColors(containerColor = PreviewNavy),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("registerReplan")) { Text("Fake 계획 등록 확인") }
                OutlinedButton(onClick = onPreview, enabled = view.canPreview, shape = RectangleShape,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("requestReplanPreview")) { Text("변경 전후 Preview") }
            }
            if (view.requestState.phase == ReplanRequestPhase.LOADING) {
                Text("Fake 요청 처리 중 · 일정 유지", modifier = Modifier.testTag("replanLoading"))
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("cancelReplanRequest")) { Text("요청 취소") }
            }
        }
        view.requestState.preview?.let { result ->
            Text("변경 전후 비교 · FAKE 가정 계산", color = PreviewNavy, fontSize = 22.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PreviewCard(Modifier.weight(1f)) { ProjectionText("변경 전", result.before, "before") }
                PreviewCard(Modifier.weight(1f)) { ProjectionText("변경 후", result.after, "after") }
            }
            Text("기준 revision ${result.baseRevision} · 계산 시각 ${timeText(result.calculatedAt)}")
            Text("실제 차량 진행 상태 미검증 · 서버 일정 확정 아님 · 이 결과로 일정을 적용하지 않습니다.")
            result.after.remainingLegs.forEach { eta ->
                Text("구간 ${eta.originalLegIndex}: ${eta.minutes?.let { "${it}분" } ?: "UNKNOWN"}\n" +
                    "출처 ${eta.source ?: "UNKNOWN"} · ${eta.dataMode ?: "UNKNOWN"} / ${eta.verification ?: "UNKNOWN"}\n" +
                    "조회 ${timeText(eta.retrievedAt)} · 캐시 ${if (eta.cached) "예" else "아니오 (FAKE)"}",
                    fontSize = 13.sp, modifier = Modifier.testTag("previewEta${eta.originalLegIndex}"))
            }
            OutlinedButton(onClick = onDismiss, shape = RectangleShape, modifier = Modifier.heightIn(min = 48.dp).testTag("rejectReplanPreview")) {
                Text("변경안 거절 · 원래 일정 유지")
            }
        }
        Text("Fake 오류·만료 시나리오 · 선택만으로 요청하지 않습니다", color = PreviewTeal)
        ReplanUiScenario.entries.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { value -> OutlinedButton(onClick = { onScenario(value) }, shape = RectangleShape,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("scenario${value.name}")) {
                Text((if (scenario == value) "✓ " else "") + value.label, fontSize = 12.sp)
            } }
        } }
    }
    if (confirmation) AlertDialog(onDismissRequest = { confirmation = false }, title = { Text("개발용 등록 요청 확인") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("현재는 FAKE Repository만 사용하며 외부 호출과 실제 서버 등록은 없습니다.")
            Text("향후 실제 /validate 연결 시 카카오 장소·도로 조회 비용이나 쿼터가 발생할 수 있습니다. 자동 등록하지 않습니다.")
            Row { Checkbox(checked = confirmed, onCheckedChange = { confirmed = it }, modifier = Modifier.testTag("confirmFakeRegistration"))
                Text("선택 코스와 개발용 Fake 등록 흐름을 직접 확인했습니다") }
        } },
        confirmButton = { TextButton(onClick = { confirmation = false; onRegister() }, enabled = confirmed,
            modifier = Modifier.heightIn(min = 48.dp).testTag("submitFakeRegistration")) { Text("확인하고 Fake 등록") } },
        dismissButton = { TextButton(onClick = { confirmation = false }) { Text("취소") } })
}

@Composable private fun PreviewCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().border(1.dp, PreviewLine).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}
@Composable private fun ProjectionText(title: String, value: ReplanProjection, tag: String) {
    Text(title, color = PreviewNavy)
    Text(timeText(value.predictedReturnAt?.toInstant()), color = PreviewNavy, fontSize = 24.sp, modifier = Modifier.testTag("${tag}Return"))
    Text("FAKE · ${value.feasibility}", color = if (value.feasibility.name == "FEASIBLE") PreviewTeal else MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("${tag}Feasibility"))
    Text("귀가 마감 ${truth(value.returnDeadlineMet)} · 안전 버퍼 ${truth(value.safetyBufferSatisfied)}")
    Text("마감 여유 ${value.remainingMarginMinutes?.let { "${it}분" } ?: "UNKNOWN"}")
    Text("ETA 가정 계산 ${value.arithmeticFeasibility}")
    Text(value.reasons.joinToString(" · "), fontSize = 12.sp)
}
private fun truth(value: Boolean?) = when (value) { true -> "충족"; false -> "부족"; null -> "UNKNOWN" }
private fun durationText(seconds: Long) = "%02d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
private fun timeText(at: Instant?) = at?.atZone(ZoneId.of("Asia/Seoul"))?.format(DateTimeFormatter.ofPattern("MM/dd HH:mm:ss")) ?: "UNKNOWN"
private fun errorText(error: ReplanError) = when (error) {
    ReplanError.PLAN_NOT_FOUND_OR_EXPIRED -> "참조가 만료됐습니다. 자동 재등록하지 않습니다"
    ReplanError.PLAN_REVISION_MISMATCH -> "기준 revision이 다릅니다. 원래 일정을 유지합니다"
    ReplanError.INTERNAL_ERROR -> "Fake 서버 오류입니다. 원래 일정을 유지합니다"
    else -> "Preview를 확인할 수 없습니다. 원래 일정을 유지합니다"
}
