package com.tripback.app.trip.recommendation

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.RouteStartInfo
import kotlinx.coroutines.flow.MutableStateFlow

/** SDK 3.0.5 verified. Coordinate-only route semantics and Pleos POI mapping remain unverified.
 * Deliberately never constructs RouteInfo with a fabricated/blank/Kakao poiId.
 */
class PleosNavigationAdapter(context: Context) : NavigationAdapter, AutoCloseable {
    private val context = context.applicationContext
    override val capability = MutableStateFlow(NavigationCapability(false, false, "SDK 연결 확인 전"))
    val observation = MutableStateFlow("SDK 콜백 미수신 · 경로 요청 없음")
    private val main = Handler(Looper.getMainLooper())
    private var helper: NaviHelper? = null
    private var closed = false
    private var registered = false
    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = observe("onRouteStarted 수신 · 추천 경로 성공 판정 아님")
        override fun onRoutingCompleted() = observe("onRoutingCompleted 수신 · 추천 목적지와 연결 미검증")
        override fun onDestinationChanged() = observe("onDestinationChanged 수신 · 설정/제거 구분 미확인")
        override fun onError(errorCode: NaviErrorCode) = observe("onError: $errorCode")
    }
    private fun observe(message: String) { main.post { if(!closed) observation.value = message } }
    fun connect() {
        if (closed || helper != null) return
        val granted = context.checkSelfPermission("pleos.car.permission.NAVI_ROUTE_SEARCH") == PackageManager.PERMISSION_GRANTED
        capability.value = NavigationCapability(false, granted,
            if(granted) "SDK 연결 중 · 좌표 단독 요청/Pleos POI 매핑 미검증" else "NAVI_ROUTE_SEARCH 권한 없음")
        if(!granted) return
        try {
            helper = NaviHelper(context).also { sdk -> sdk.initialize(
                onConnected = { main.post {
                    if(!closed) try {
                        sdk.addListener(listener); registered = true
                        capability.value = NavigationCapability(false, true, "SDK 연결됨 · 좌표 단독 요청/Pleos POI 매핑 미검증으로 출발 차단")
                    } catch (_: Exception) { capability.value = NavigationCapability(false, true, "SDK 리스너 등록 실패") }
                } },
                onFailed = { main.post { if(!closed) capability.value = NavigationCapability(false, true, "SDK 서비스 연결 실패") } }
            ) }
        } catch (_: SecurityException) {
            capability.value = NavigationCapability(false, false, "SDK 권한 오류")
        } catch (_: Exception) {
            capability.value = NavigationCapability(false, granted, "SDK 초기화 실패/미지원")
        }
    }
    override fun request(destination: NavigationDestination, event: (NavigationState) -> Unit) {
        event(NavigationState(NavigationStatus.BLOCKED, capability.value.message))
    }
    override fun close() {
        closed = true
        try { if(registered) helper?.removeListener(listener) } catch (_: Exception) { }
        try { helper?.release() } catch (_: Exception) { }
        registered = false; helper = null
        capability.value = NavigationCapability(false, false, "SDK 연결 종료")
    }
}
