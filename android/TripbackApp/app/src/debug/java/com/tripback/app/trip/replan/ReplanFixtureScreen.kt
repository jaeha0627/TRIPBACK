package com.tripback.app.trip.replan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/** Independent fixture-owned journey; no reference to the user's current DEMO trip is accepted. */
@Composable
fun ReplanFixtureScreen(onExit: () -> Unit) {
    var selected by remember { mutableStateOf(ReplanFixtureCase.NORMAL) }
    key(selected) { FixtureCasePanel(selected, { selected = it }, onExit) }
}

@Composable
private fun FixtureCasePanel(case: ReplanFixtureCase, onCase: (ReplanFixtureCase) -> Unit, onExit: () -> Unit) {
    val scope = rememberCoroutineScope()
    val fixture = remember { ReplanUiFixture.create(case) }
    val repository = remember { FakeReplanUiRepository(fixture.clock) }
    val controller = remember { ReplanDebugController(scope, fixture.journey, fixture.request, fixture.raw,
        fixture.plan.planId, repository, fixture.clock) }
    val state by controller.state.collectAsState()
    val total by controller.totalStayMinutes.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var scenario by remember { mutableStateOf(ReplanUiScenario.NORMAL) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) controller.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.close() }
    }
    LaunchedEffect(controller) { while (true) { tick++; delay(1000) } }
    @Suppress("UNUSED_VARIABLE") val refresh = listOf(state.phase, total, tick)
    val close = { controller.dismiss(); onExit() }
    BackHandler(onBack = close)
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).widthIn(max = 1120.dp).fillMaxHeight(.94f), color = Color.White,
            shape = RectangleShape, border = BorderStroke(1.dp, Color(0xFFD9DEE3))) {
            ReplanDebugPanel(controller.view(), controller::changeTotal, { controller.register(true) }, { controller.preview() },
                controller::stop, close, scenario, { scenario = it; controller.scenario(it) }, fixtureHeader = {
                    Text("테스트 Fixture · 실제 서버 검증 계획 아님", color = Color(0xFF002C5F), modifier = Modifier.testTag("fixtureOnlyWarning"))
                    Text("일반 DEMO와 완전히 분리된 가상 여행입니다. 실제 서버·AI·카카오·NaviHelper 호출이 없습니다.")
                    Text("시작 시각 10:00 · 체류 30분 / 경과 10분 · 총 50분으로 바꿔 비교하세요.")
                    Text("원본 계약의 검증 메타데이터는 파서 시험용 표본입니다. 표시되는 ETA·결과는 FAKE/미검증입니다.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReplanFixtureCase.entries.forEach { value -> OutlinedButton(onClick = { if (case != value) onCase(value) },
                            shape = RectangleShape, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("fixtureCase${value.name}")) {
                            Text((if (case == value) "✓ " else "") + value.label)
                        } }
                    }
                    Text("사례를 바꾸면 기존 Fixture 참조·Preview를 폐기합니다. 새 등록은 직접 확인해야 합니다.")
                })
        }
    }
}
