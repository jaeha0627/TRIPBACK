package com.tripback.app

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.constants.*
import ai.pleos.playground.navi.data.*
import java.time.Instant

/** Reuses MainActivity's SDK screen. Debug only; no current-location/history queries. */
class DebugNaviTest(private val activity: ComponentActivity) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = activity.getSharedPreferences("debug_navi_trial", 0)
    private var consumed = prefs.getBoolean("consumed", false)
    private val trial = RouteTrial()
    private var helper: NaviHelper? = null
    private val sdkContext = DebugNaviContext(activity.applicationContext, main) {
        if (!closed) {
        connected = false; invalidateConsent(); queryPurpose = null; busy = false
        trial.unknown(); if (consumed && trial.result != "NOT_REQUESTED") status = trial.result
        try { helper?.release() } catch (_: Exception) { }
        record("BINDER_DISCONNECTED · verdict=${currentVerdict()} · released · no reconnect/retry")
        }
    }
    private var car: Car? = null
    private var closed = false
    private var connected = false
    private var registered = false
    private var initialized = false
    private var busy by mutableStateOf(false)
    private var consent by mutableStateOf(false)
    private var status by mutableStateOf(if (consumed) {
        val cached = prefs.getString("result", "UNKNOWN")
        "${if (cached == "SUCCESS" || cached == "FAILURE") cached else "UNKNOWN"} · VERIFIED_CACHED · 이전 시험 · 재요청 차단"
    } else "NOT_REQUESTED")
    private var snapshot by mutableStateOf("현재 목적지/경로 UNKNOWN")
    private var diagnosticStatus by mutableStateOf("읽기 전용 진단 대기")
    private val destinationDiagnosticUi = DestinationDiagnosticUiState()
    private var navigationStatus by mutableStateOf("UNKNOWN")
    private var readSequence = 0
    private var readContext = "UNSOLICITED_EVENT"
    private var events by mutableStateOf(prefs.getString("events", "")!!.lines().filter { it.isNotEmpty() })
    private var destination: DestinationInfo? = null // In memory only; no arbitrary destination logging.
    private var route: RouteStateInfo? = null
    private var snapshotKey: String? = null
    private var approvedKey: String? = null
    private var queryAt = 0L
    private var queryPurpose: String? = null
    private var gotDestination = false
    private var gotRoute = false
    private var postCompletedQuery = false
    private var sequence = events.lastOrNull()?.substringBefore(" ")?.toIntOrNull() ?: 0
    private var resultDeadline = 0L

    private fun currentVerdict(): String = if (trial.result == "NOT_REQUESTED" && consumed) {
        when (prefs.getString("result", "UNKNOWN")) { "SUCCESS" -> "SUCCESS"; "FAILURE" -> "FAILURE"; else -> "UNKNOWN" }
    } else trial.result

    fun created(restored: Boolean) {
        record("${if (restored) "ACTIVITY_RECREATED" else "ACTIVITY_CREATED"} · retainedRequestLock=$consumed · verdict=${currentVerdict()}")
    }

    private fun updateSnapshot() {
        val d = destinationDiagnosticUi.diagnostic
        snapshot = "VERIFIED_LIVE · 목적지=${d?.summary ?: "UNKNOWN"}" +
            (if (d != null && !d.strictIdentityMatch) " · 전체 일치 조건 미충족" else "") +
            " / NavigationStatus=$navigationStatus / mode=${route?.naviMode ?: "UNKNOWN"} / isRouting=${route?.isRouting ?: "UNKNOWN"}"
    }

    private fun record(message: String) {
        events = (events + "${++sequence} ${Instant.now()} $message").takeLast(160)
        val outcome = if (trial.result == "NOT_REQUESTED") prefs.getString("result", "UNKNOWN") else trial.result
        prefs.edit().putString("events", events.joinToString("\n")).putString("result", outcome).apply()
    }
    private fun dispatch(action: () -> Unit) { main.post { if (!closed) {
        if (trial.result == "PENDING" && SystemClock.elapsedRealtime() >= resultDeadline) {
            trial.unknown(); status = trial.result; record("TRIAL_TIMEOUT · 30초 결과 기한 · UNKNOWN · 늦은 이벤트는 판정 제외")
        }
        action()
        if (trial.result != "NOT_REQUESTED") {
            prefs.edit().putString("result", trial.result).apply()
        }
    } } }
    private fun permissions() = listOf("pleos.car.permission.NAVI_ROUTE", "pleos.car.permission.NAVI_ROUTE_SEARCH", Car.PERMISSION_POWERTRAIN)
        .all { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    // Re-read live restrictions and P gear immediately before queries and the sole route call.
    private fun safeNow(): Boolean = try {
        val c = car
        if (!activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) ||
            c == null || !c.isConnected || !permissions()) false
        else {
            val ux = (c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager)?.currentCarUxRestrictions
            val gear = (c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager)
                ?.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0)
            ux != null && !ux.isRequiresDistractionOptimization && ux.activeRestrictions == 0 &&
                gear != null && gear.status == CarPropertyValue.STATUS_AVAILABLE && gear.value == VehicleGear.GEAR_PARK
        }
    } catch (_: Exception) { false } catch (_: LinkageError) { false }

    private fun initialize() {
        if (initialized) return
        initialized = true
        try {
            car = Car.createCar(activity.applicationContext, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { _, ready ->
                dispatch { if (!ready) { invalidateConsent(); record("Car disconnected · 요청 차단") } }
            }
            helper = NaviHelper(sdkContext).also { sdk ->
                sdk.initialize(onConnected = { dispatch {
                    try { sdk.addListener(listener); registered = true; connected = true; record("SDK initial connection VERIFIED_LIVE") }
                    catch (_: Exception) { connected = false; record("SDK listener unavailable") }
                } }, onFailed = { dispatch { connected = false; consent = false; record("SDK connection UNKNOWN") } })
            }
        } catch (_: Exception) { record("SDK/Car initialization UNKNOWN") }
        catch (_: LinkageError) { record("Car API unavailable · 요청 차단") }
    }

    private fun query(purpose: String) {
        if (queryPurpose != null) return
        consent = false
        if (!connected || !registered || !sdkContext.live || !safeNow()) {
            invalidateConsent(); diagnosticStatus = "READ_BLOCKED · SDK/권한/P 기어/CarUxRestrictions 확인 필요"
            record("$diagnosticStatus · purpose=$purpose · verdictUnchanged=${currentVerdict()}"); return
        }
        if (purpose == "inspect") { snapshotKey = null; approvedKey = null }
        busy = true; queryPurpose = purpose; queryAt = SystemClock.elapsedRealtime()
        gotDestination = false; gotRoute = false
        readContext = "READ_${++readSequence}_$purpose"
        diagnosticStatus = "$readContext · 조회 중"
        record("getDestinationInfo + getRouteStateInfo · $DESTINATION_DIAGNOSTIC_UI_VERSION · $readContext · verdict=${currentVerdict()}")
        try { helper!!.getDestinationInfo(); helper!!.getRouteStateInfo() }
        catch (_: Exception) {
            queryPurpose = null; busy = false; invalidateConsent()
            diagnosticStatus = "READ_CALL_FAILED · $readContext"
            record("$diagnosticStatus · verdictUnchanged=${currentVerdict()}")
        }
        val token = queryAt
        main.postDelayed({
            if (!closed && queryPurpose != null && queryAt == token) {
                queryPurpose = null; busy = false; consent = false
                diagnosticStatus = "READ_TIMEOUT · $readContext · 5초 내 두 응답 미확인 · destinationReceived=$gotDestination routeReceived=$gotRoute"
                snapshotKey = null; approvedKey = null
                record("$diagnosticStatus · verdictUnchanged=${currentVerdict()}")
            }
        }, 5000)
    }

    private fun invalidateConsent() { consent = false; snapshotKey = null; approvedKey = null }

    private fun key(): String? {
        val d = destination ?: return null
        val r = route ?: return null
        // Private identity comparison, excluding changing ETA/distance.
        return listOf(d.poiId, d.poiSubId, d.latitude, d.longitude, r.isRouting, r.naviMode, r.termsAgreement).joinToString("|")
    }
    private fun received() {
        if (!gotDestination || !gotRoute || queryPurpose == null) return
        val purpose = queryPurpose!!
        queryPurpose = null; busy = false
        val k = key(); snapshotKey = if (consumed) null else k
        diagnosticStatus = "$readContext · 두 응답 수신 · requestId 상관관계 미제공"
        record("$diagnosticStatus · verdict=${currentVerdict()}")
        if (purpose == "preflight") {
            if (!canRequestTrial(consumed, approvedKey != null, k != null && k == approvedKey, SystemClock.elapsedRealtime() - queryAt, connected && registered && sdkContext.live, safeNow(), route?.termsAgreement == true)) {
                status = "BLOCKED · 상태 변경/안전 미확인 · 다시 조회하고 확인하세요"; record(status)
            } else requestTestRoute()
        } else if (purpose == "evidence") {
            val d = destination!!
            trial.event("destination", matchesSample(d.latitude, d.longitude, d.poiId, d.poiSubId))
            trial.event("routing", route!!.isRouting)
            status = trial.result; record("evidence result=${trial.result}")
        } else if (purpose == "post_trial_diagnostic") {
            record("POST_TRIAL_DIAGNOSTIC · 기존 판정 유지=${currentVerdict()} · 경로 재요청 없음")
        } else if (!consumed) status = "READY · 조회 응답 VERIFIED_LIVE · 사용자 확인 필요"
    }
    private fun requestTestRoute() {
        if (consumed || !connected || !registered || !sdkContext.live || !safeNow()) { status = "BLOCKED"; return }
        // Durable latch precedes invocation: recreation/process death cannot issue a duplicate.
        if (!prefs.edit().putBoolean("consumed", true).putString("result", "UNKNOWN").commit()) { status = "BLOCKED · 요청 잠금 저장 실패"; return }
        consumed = true; consent = false; trial.begin(); status = trial.result
        if (!sdkContext.live || !safeNow()) {
            trial.unknown(); status = trial.result; record("UNKNOWN · final live guard blocked · requestRoute not called"); return
        }
        resultDeadline = SystemClock.elapsedRealtime() + 30000
        record("requestRoute invocation · VERIFIED_CACHED official 42dot sample")
        try {
            helper!!.requestRoute(RouteInfo(
                latitude = 37.48544722,
                longitude = 127.03636666,
                poiName = "42dot",
                poiId = "36khp37hh8gqcm5nqhe5",
                address = "서울시 강남구 남부순환로 2621",
                poiSubId = "0"
            ))
            record("requestRoute returned Unit · 성공 증거 아님")
        } catch (_: Exception) { trial.event("failure"); status = trial.result; record("requestRoute call FAILURE · details suppressed") }
        main.postDelayed({ if (!closed && trial.result == "PENDING") {
            trial.unknown(); status = trial.result; record("TRIAL_TIMEOUT · UNKNOWN · 30초 결과 기한 · 자동 재시도 없음")
        } }, 30000)
    }
    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = dispatch {
            record("onRouteStarted"); trial.event("started"); if (consumed && trial.result != "NOT_REQUESTED") status = trial.result
        }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onRouteEnded() = dispatch {
            record("onRouteEnded · LEGACY_OBSERVATION_ONLY · verdictUnchanged=${currentVerdict()}")
        }
        override fun onRoutingCompleted() = dispatch {
            record("onRoutingCompleted"); trial.event("completed")
            if (trial.result == "PENDING" && trial.completed && !postCompletedQuery) {
                postCompletedQuery = true
                if (safeNow()) query("evidence") else record("안내 앱 전환/안전 미확인 · 복귀 후 상태 조회 가능 · 경로 재요청 없음")
            }
        }
        override fun onDestinationChanged() = dispatch {
            record("onDestinationChanged (set/remove unknown)")
            invalidateConsent(); trial.event("destination", false)
            if (queryPurpose == "preflight") { queryPurpose = null; busy = false; status = "BLOCKED · 목적지 변경 · 다시 확인 필요" }
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = dispatch {
            destination = destinationInfo
            val comparison = destinationDiagnosticUi.accept(destinationInfo)
            updateSnapshot()
            record("onDestinationInfo · $DESTINATION_DIAGNOSTIC_UI_VERSION · ${if (queryPurpose != null) readContext else "UNSOLICITED_EVENT"} · ${comparison.comparisonLine()} · verdict=${currentVerdict()}")
            if (!comparison.strictIdentityMatch) trial.event("destination", false)
            if (consent && key() != snapshotKey) consent = false
            if (queryPurpose != null) { gotDestination = true; received() }
        }
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = dispatch {
            route = routeStateInfo
            updateSnapshot()
            record("onRouteStateInfo · ${if (queryPurpose != null) readContext else "UNSOLICITED_EVENT"} · isRouting=${routeStateInfo.isRouting} mode=${routeStateInfo.naviMode}")
            if (!routeStateInfo.isRouting) trial.event("routing", false)
            if (consent && key() != snapshotKey) consent = false
            if (queryPurpose != null) { gotRoute = true; received() }
        }
        override fun onNavigationStatus(status: NavigationStatus) = dispatch {
            navigationStatus = status.name
            updateSnapshot()
            record("onNavigationStatus=$status"); trial.event("guidance", status == NavigationStatus.ROUTE_DRIVE)
            if (consumed && trial.result != "NOT_REQUESTED") this@DebugNaviTest.status = trial.result
        }
        override fun onRouteCancelled() = dispatch {
            record("onRouteCancelled"); consent = false; trial.event("failure"); if (consumed && trial.result != "NOT_REQUESTED") status = trial.result
        }
        override fun onError(errorCode: NaviErrorCode) = dispatch {
            record("onError=$errorCode"); invalidateConsent(); trial.event("failure")
            queryPurpose = null; busy = false; if (trial.result != "NOT_REQUESTED") status = trial.result else if (!consumed) status = "BLOCKED · SDK error"
        }
    }
    @Composable fun Content() {
        LaunchedEffect(Unit) { initialize() }
        DisposableEffect(Unit) {
            record("SDK_SCREEN_ENTER · $DESTINATION_DIAGNOSTIC_UI_VERSION · verdict=${currentVerdict()}")
            onDispose { pause() }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("DEBUG · NaviHelper 3.0.5 · 단일 목적지 시험")
            Text("VERIFIED_CACHED · 공식 42dot / 서울시 강남구 남부순환로 2621 / POI 36khp37hh8gqcm5nqhe5 (subId 0)")
            Text("위도(latitude) 37.48544722 / 경도(longitude) 127.03636666")
            Text(snapshot)
            DestinationDiagnosticPanel(destinationDiagnosticUi)
            Text(diagnosticStatus)
            Text("종합 결과는 ID/Sub ID/기본 좌표 기준입니다. 이름·display 좌표는 참고 항목이며 이름 또는 정규화 일치만으로 성공하지 않습니다.")
            Text("좌표 진단: ABSENT=없음, INVALID=유효하지 않음, WITHIN_TOLERANCE=허용치 내, OUTSIDE_TOLERANCE=허용치 밖. FIELDS_DIFFER는 실제 다른 목적지의 확정이 아닙니다.")
            Text("기존 내비 목적지와 경로를 대체할 수 있습니다. 다른 목적지 이름·좌표는 숨깁니다. 이번 시험은 경유지 없이 42dot만 요청합니다.")
            Button(enabled = !busy, onClick = { query(manualDiagnosticPurpose(consumed, trial.result)) }) { Text("현재 목적지·경로 조회 (경로 요청 아님)") }
            Row {
                Checkbox(checked = consent, enabled = !consumed && !busy && snapshotKey != null, onCheckedChange = {
                    consent = it; approvedKey = if (it) snapshotKey else null
                })
                Text("42dot 목적지와 현재 경로 상태, 기존 경로 대체 가능성을 직접 확인했습니다")
            }
            Button(enabled = !consumed && !busy && consent, onClick = { query("preflight") }) { Text("확인한 42dot 경로 1회 요청") }
            Text("결과: $status")
            Text("SUCCESS: 탐색 시작/완료 + 새 조회의 목적지 일치·활성 경로 + ROUTE_DRIVE 증거. SDK 이벤트에는 요청 ID가 없어 외부 내비 조작과의 인과관계는 수동 확인하세요.")
            Text("요청 잠금은 화면/프로세스 재생성 후에도 유지됩니다. 자동 재시도 없음. 30초 내 증거 부족 또는 Activity 재생성은 UNKNOWN입니다. 안내 앱으로 전환되어도 관찰은 계속합니다.")
            Text("UNKNOWN 이후 조회는 별도 진단이며 기존 판정을 변경하지 않습니다. 원본 ID·이름·주소·좌표·차이·해시는 기록하지 않습니다.")
            Text("이벤트 이력 · 이전 버전의 저장 로그 포함 · 새 진단은 $DESTINATION_DIAGNOSTIC_UI_VERSION 표식으로 구분")
            events.forEach { Text(it) }
        }
    }
    fun pause(reason: String = "SDK_SCREEN_DISPOSE") {
        val cancelledPurpose = queryPurpose ?: "NONE"
        consent = false; snapshotKey = null; approvedKey = null; queryPurpose = null; busy = false
        if (consumed && trial.result != "NOT_REQUESTED") status = trial.result
        record("$reason · cancelledRead=$cancelledPurpose · verdict=${currentVerdict()} · no retry")
    }
    fun close(reason: String = "ACTIVITY_DESTROY") {
        trial.unknown()
        if (consumed && trial.result != "NOT_REQUESTED") status = trial.result
        record("$reason · verdict=${currentVerdict()} · no retry")
        closed = true; main.removeCallbacksAndMessages(null)
        try { if (registered) helper?.removeListener(listener) } catch (_: Exception) { }
        try { helper?.release() } catch (_: Exception) { }
        try { car?.disconnect() } catch (_: Exception) { } catch (_: LinkageError) { }
        helper = null; car = null; connected = false
    }
}
