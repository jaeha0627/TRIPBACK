package com.tripback.app.trip.replan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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

/** HTTP is selected only by the originating recommendation repository; construction never sends requests. */
@Composable
fun JourneyReplanDebug(journey: TripJourney, request: RecommendationRequest?, raw: String?, selectedPlanId: String?, useHttp: Boolean = false) {
    val scope = rememberCoroutineScope()
    val clock = remember { Clock.systemUTC() }
    val repository = remember(journey, useHttp) { replanRepository(useHttp, clock) }
    val controller = remember(journey, request, raw, selectedPlanId, repository) {
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
        Text(if (useHttp) "재계획 Preview · Debug / HTTP 개발" else "재계획 Preview · Debug / FAKE")
    }
    OutlinedButton(onClick = { fixtureOpen = true }, shape = RectangleShape,
        modifier = Modifier.heightIn(min = 48.dp).testTag("openReplanFixture")) {
        Text("검증용 Fixture 시나리오 · Debug")
    }
    if (fixtureOpen) ReplanFixtureScreen(onExit = { fixtureOpen = false })
    if (open) Dialog(onDismissRequest = { controller.dismiss(); open = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 1080.dp).fillMaxWidth(.94f).fillMaxHeight(.94f),
            shape = RectangleShape, color = Color.White, border = BorderStroke(1.dp, PreviewLine)) {
            ReplanDebugPanel(view, controller::changeTotal, { controller.register(true) }, { controller.preview() },
                controller::stop, { controller.dismiss(); open = false }, scenario,
                { scenario = it; controller.scenario(it) }, useHttp = useHttp)
        }
    }
}

/** Debug-only presentation. Callbacks retain explicit registration and read-only Preview semantics. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReplanDebugPanel(view: ReplanDebugView, onTotal: (Long) -> Unit, onRegister: () -> Unit,
    onPreview: () -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit,
    scenario: ReplanUiScenario, onScenario: (ReplanUiScenario) -> Unit, fixtureHeader: @Composable () -> Unit = {}, useHttp: Boolean = false) {
    var confirmation by remember(useHttp) { mutableStateOf(false) }
    var confirmed by remember(useHttp) { mutableStateOf(false) }
    com.tripback.app.trip.recommendation.HomeStyle {
    MaterialTheme(colorScheme=lightColorScheme(primary=PreviewNavy,secondary=PreviewTeal,background=Color.White,surface=Color.White,onSurface=Color(0xFF14191F))) {
    Box(Modifier.fillMaxSize().background(Color.White),contentAlignment=Alignment.TopCenter) {
    Column(Modifier.widthIn(max=1080.dp).fillMaxSize().testTag("replanDebugPanel")) {
        Row(Modifier.fillMaxWidth().heightIn(min=57.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("⇄",Modifier.background(PreviewNavy).padding(5.dp),color=Color.White,fontSize=14.sp)
            Text("일정 수정 · Preview",Modifier.weight(1f).padding(start=10.dp),color=PreviewNavy,fontSize=20.sp,fontWeight=FontWeight.Medium)
            TextButton(onClick=onDismiss,modifier=Modifier.heightIn(min=48.dp).testTag("closeReplanPreview")) { Text("닫기 ×") }
        }
        HorizontalDivider(color=PreviewLine)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide=maxWidth>=840.dp
            val content:@Composable ColumnScope.()->Unit = {
                Text(if(useHttp) "PREVIEW_ONLY · HTTP 개발 · CLIENT_REPORTED_NOT_VERIFIED" else "PREVIEW_ONLY · FAKE Repository · 외부 HTTP/Provider 호출 0회",
                    color=PreviewTeal,fontSize=12.sp,lineHeight=18.sp,modifier=Modifier.testTag("previewOnly"))
                if(useHttp) Text("실제 차량 GPS·운행·도착 미검증 · 클라이언트 수동 관측",fontSize=13.sp,modifier=Modifier.testTag("httpObservationWarning"))
                Text("실제 차량 진행 상태 미검증 · 서버 일정 확정 아님",fontSize=13.sp,modifier=Modifier.testTag("notServerApproved"))
                fixtureHeader()
                PreviewCard {
                    Text("현재 방문지",fontSize=12.sp,color=Color.Gray)
                    Text(view.currentName,fontSize=28.sp,lineHeight=36.sp,color=PreviewNavy)
                    Text("선택 코스: ${view.title}",fontSize=13.sp)
                    Text(if(useHttp) "체류 경과 · 클라이언트 수동 관측" else "체류 경과 · 수동 DEMO 관측",fontSize=12.sp,color=PreviewTeal)
                    Text(view.elapsedSeconds?.let(::durationText) ?: "UNKNOWN",fontSize=44.sp,lineHeight=54.sp,color=PreviewNavy,modifier=Modifier.testTag("previewElapsed"))
                    Text("기존 총 체류 ${view.originalStayMinutes}분 · 현재 단계 ${view.phase}",fontSize=14.sp)
                    Column(Modifier.fillMaxWidth().background(Color(0xFFF3F5F7)).padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("변경하려는 총 체류 ${view.requestedTotalMinutes}분",color=PreviewNavy,fontSize=20.sp,modifier=Modifier.testTag("requestedTotal"))
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick={onTotal((view.requestedTotalMinutes-15).coerceAtLeast(0))},shape=RectangleShape,modifier=Modifier.heightIn(min=48.dp).testTag("totalMinus15")) { Text("−15분") }
                            OutlinedButton(onClick={onTotal((view.requestedTotalMinutes+15).coerceAtMost(1440))},shape=RectangleShape,modifier=Modifier.heightIn(min=48.dp).testTag("totalPlus15")) { Text("+15분") }
                            OutlinedButton(onClick={onTotal(50)},shape=RectangleShape,modifier=Modifier.heightIn(min=48.dp).testTag("total50")) { Text("총 50분") }
                        }
                    }
                    Text("추가 시간이 아닌 총 계획시간입니다. 이 조작으로 일정은 바뀌지 않습니다.",fontSize=13.sp,lineHeight=20.sp)
                }
        PreviewCard {
            Text(if (useHttp) "서버 검증 계획 등록 상태 · HTTP 개발" else "서버 검증 계획 등록 상태 · FAKE 흐름", color = PreviewNavy, fontWeight = FontWeight.Medium)
            Text(view.requestState.registrationStatus?.let { if (useHttp) "$it · Preview용 임시 참조" else "$it · 서버 등록 아님" } ?: (if (view.requestState.registrationUnconfirmed) "서버 등록 여부 미확인" else "미등록"), modifier = Modifier.testTag("registrationStatus"))
            Text("계획 참조: ${when { view.requestState.phase == ReplanRequestPhase.EXPIRED -> "만료됨"; view.requestState.expiresAt != null -> "30분 TTL · " + timeText(view.requestState.expiresAt) + " 만료"; else -> "사용 가능한 참조 없음" }}")
            Text("ETA 유효기간 15분 · 만료·실패 시 자동 재등록/재시도 없음")
            view.etaNotice?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("previewEtaExpired")) }
            view.blockedReason?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("replanBlocked")) }
            view.requestState.diagnostic?.let {
                Text(it.name, modifier = Modifier.testTag("replanDiagnostic"))
            }
            view.requestState.error?.let { Text("${errorText(it)} · ${it.name}", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("replanError")) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { confirmation = true; confirmed = false }, enabled = view.canRegister,
                    shape = RectangleShape, colors = ButtonDefaults.buttonColors(containerColor = PreviewNavy),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("registerReplan")) { Text(if (useHttp) "HTTP 계획 등록 확인 (개발)" else "Fake 계획 등록 확인") }
                OutlinedButton(onClick = onPreview, enabled = view.canPreview, shape = RectangleShape,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("requestReplanPreview")) { Text("변경 전후 Preview") }
            }
            if (view.requestState.phase == ReplanRequestPhase.LOADING) {
                Text(if (useHttp) "HTTP 요청 처리 중 · 일정 유지" else "Fake 요청 처리 중 · 일정 유지", modifier = Modifier.testTag("replanLoading"))
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("cancelReplanRequest")) { Text("요청 취소") }
            }
        }
        view.requestState.preview?.let { result ->
            Text(if (useHttp) "변경 전후 비교 · HTTP 읽기 전용 Preview" else "변경 전후 비교 · FAKE 가정 계산", color = PreviewNavy, fontSize = 22.sp)
            Text("귀가 마감 ${timeText(view.deadline.toInstant())} · 안전 여유 ${view.bufferMinutes}분",fontSize=13.sp)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if(maxWidth>=640.dp) Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    PreviewCard(Modifier.weight(1f).testTag("beforeComparison")) { ProjectionText("변경 전",result.before,"before",useHttp) }
                    PreviewCard(Modifier.weight(1f).testTag("afterComparison")) { ProjectionText("변경 후",result.after,"after",useHttp) }
                } else Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    PreviewCard(Modifier.testTag("beforeComparison")) { ProjectionText("변경 전",result.before,"before",useHttp) }
                    PreviewCard(Modifier.testTag("afterComparison")) { ProjectionText("변경 후",result.after,"after",useHttp) }
                }
            }
            Text("기준 revision ${result.baseRevision} · 계산 시각 ${timeText(result.calculatedAt)}")
            Text("실제 차량 진행 상태 미검증 · 서버 일정 확정 아님 · 이 결과로 일정을 적용하지 않습니다.")
            result.after.remainingLegs.forEach { eta ->
                Text("구간 ${eta.originalLegIndex}: ${eta.minutes?.let { "${it}분" } ?: "UNKNOWN"}\n" +
                    "출처 ${eta.source ?: "UNKNOWN"} · ${eta.dataMode ?: "UNKNOWN"} / ${eta.verification ?: "UNKNOWN"}\n" +
                    "조회 ${timeText(eta.retrievedAt)} · 캐시 ${if (eta.cached) "예" else "아니오"}",
                    fontSize = 13.sp, modifier = Modifier.testTag("previewEta${eta.originalLegIndex}"))
            }
            OutlinedButton(onClick = onDismiss, shape = RectangleShape, modifier = Modifier.heightIn(min = 48.dp).testTag("rejectReplanPreview")) {
                Text("변경안 거절 · 원래 일정 유지")
            }
        }
        if (!useHttp) {
            Text("Fake 오류·만료 시나리오 · 선택만으로 요청하지 않습니다", color = PreviewTeal)
            ReplanUiScenario.entries.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { value -> OutlinedButton(onClick = { onScenario(value) }, shape = RectangleShape,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("scenario${value.name}")) {
                    Text((if (scenario == value) "✓ " else "") + value.label, fontSize = 12.sp)
                } }
            } }
        }

            }
            if(wide) Row(Modifier.fillMaxSize().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(688f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)
                Column(Modifier.weight(340f).verticalScroll(rememberScrollState())) { PreviewItinerary(view) }
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                content()
                PreviewItinerary(view)
            }
        }
        HorizontalDivider(color=PreviewLine)
        Box(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),contentAlignment=Alignment.Center) {
            OutlinedButton(onClick=onDismiss,shape=RectangleShape,modifier=Modifier.widthIn(max=520.dp).fillMaxWidth().heightIn(min=50.dp).testTag("keepOriginalPlan")) {
                Text("원본 일정 유지 · 닫기")
            }
        }
    }
    }
    if (confirmation) AlertDialog(onDismissRequest = { confirmation = false }, title = { Text("개발용 등록 요청 확인") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (useHttp) "개발용 /validate 등록은 실제 카카오 장소·경로 조회 및 비용·쿼터를 발생시킬 수 있습니다. 승인 시 1회 요청합니다." else "현재는 FAKE Repository만 사용하며 외부 호출과 실제 서버 등록은 없습니다.")
            Text("자동 등록·재시도는 없습니다. Preview는 서버 일정 확정이 아닙니다.")
            Row { Checkbox(checked = confirmed, onCheckedChange = { confirmed = it }, modifier = Modifier.testTag("confirmFakeRegistration"))
                Text(if (useHttp) "선택 코스와 실제 카카오 조회 비용을 확인하고 1회 등록에 동의합니다" else "선택 코스와 개발용 Fake 등록 흐름을 직접 확인했습니다") }
        } },
        confirmButton = { TextButton(onClick = { if (confirmed && view.canRegister) { confirmation = false; confirmed = false; onRegister() } }, enabled = confirmed && view.canRegister,
            modifier = Modifier.heightIn(min = 48.dp).testTag("submitFakeRegistration")) { Text(if (useHttp) "승인하고 HTTP 1회 등록" else "확인하고 Fake 등록") } },
        dismissButton = { TextButton(onClick = { confirmation = false }) { Text("취소") } })
}
}

}

@Composable private fun PreviewItinerary(view:ReplanDebugView) {
    PreviewCard(Modifier.testTag("previewItinerary")) {
        Text("오늘의 일정 · ${view.title}",fontSize=13.sp,color=PreviewNavy,fontWeight=FontWeight.Medium)
        Text("원래 순서·방문지 유지",fontSize=12.sp,color=Color.Gray)
        view.visits.forEachIndexed { i,(name,minutes) ->
            Column(Modifier.fillMaxWidth().background(if(i==view.currentIndex) Color(0xFFF3F5F7) else Color.White)
                .border(1.dp,if(i==view.currentIndex) PreviewTeal else PreviewLine).padding(10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                if(i==view.currentIndex) Text("현재 방문지",fontSize=11.sp,color=PreviewTeal)
                Text("${i+1}. $name",fontSize=14.sp,fontWeight=FontWeight.Medium)
                Text("머무는 시간 ${minutes}분 · 원래 순서 유지",fontSize=12.sp)
            }
        }
        Text("귀가 마감 ${timeText(view.deadline.toInstant())}",fontSize=13.sp)
        Text("안전 버퍼 ${view.bufferMinutes}분 · 귀가 지점 유지",fontSize=13.sp)
        Text("PREVIEW_ONLY · 이 화면에서 원본 일정을 적용하지 않습니다.",fontSize=12.sp,color=PreviewTeal)
    }
}

@Composable private fun PreviewCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().border(1.dp, PreviewLine).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}
@Composable private fun ProjectionText(title: String, value: ReplanProjection, tag: String, useHttp: Boolean) {
    Text(title, color = PreviewNavy)
    Text(timeText(value.predictedReturnAt?.toInstant()), color = PreviewNavy, fontSize = 24.sp, modifier = Modifier.testTag("${tag}Return"))
    Text((if (useHttp) "HTTP · " else "FAKE · ") + value.feasibility, color = if (value.feasibility.name == "FEASIBLE") PreviewTeal else MaterialTheme.colorScheme.error,
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
    ReplanError.INTERNAL_ERROR -> "서버 오류입니다. 원래 일정을 유지합니다"
    else -> "Preview를 확인할 수 없습니다. 원래 일정을 유지합니다"
}

/** Selection only: neither constructor performs I/O. Fixture screen constructs its own Fake. */
internal fun replanRepository(useHttp: Boolean, clock: Clock): DebugReplanRepository =
    if (useHttp) DebugReplanHttp(clock = clock) else FakeReplanUiRepository(clock)
