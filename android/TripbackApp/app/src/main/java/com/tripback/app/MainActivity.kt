package com.tripback.app

import android.os.Bundle
import android.util.Log
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tripback.app.ui.theme.TripbackAppTheme

class MainActivity : ComponentActivity() {
    private var naviHelper: NaviHelper? = null
    private var connectionStatus by mutableStateOf("내비게이션 연결 중")
    private var connected by mutableStateOf(false)
    private var requestStatus by mutableStateOf("경로 요청 전")
    private var routeStartedStatus by mutableStateOf("탐색 시작: 미수신")
    private var routingCompletedStatus by mutableStateOf("탐색 완료: 미수신")
    private var destinationChangedStatus by mutableStateOf("목적지 변경: 미수신")
    private var routeErrorStatus by mutableStateOf("SDK 오류: 미수신")
    private var closing = false
    private var listenerRegistered = false
    private val naviEventListener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            Log.d("TripbackNavi", "onRouteStarted: $routeStartInfo")
            updateUi { routeStartedStatus = "탐색 시작: SDK 콜백 수신" }
        }

        override fun onRoutingCompleted() {
            Log.d("TripbackNavi", "onRoutingCompleted")
            updateUi { routingCompletedStatus = "탐색 완료: SDK 콜백 수신" }
        }

        override fun onDestinationChanged() {
            Log.d("TripbackNavi", "onDestinationChanged")
            updateUi { destinationChangedStatus = "목적지 변경: SDK 콜백 수신 (설정 또는 제거)" }
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.e("TripbackNavi", "onError: $errorCode")
            updateUi { routeErrorStatus = "SDK 오류: $errorCode" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TripbackAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding)) {
                        ConnectionStatus(status = connectionStatus)
                        Text("시연용 테스트 목적지: 42dot")
                        Text("위도 37.48544722 / 경도 127.03636666 (공식 문서 예시)")
                        Button(enabled = connected, onClick = { requestTestRoute() }) {
                            Text("테스트 목적지 안내 시작")
                        }
                        Text(requestStatus)
                        Text("아래는 내비게이션 서비스의 실제 이벤트 수신 상태입니다.")
                        Text(routeStartedStatus)
                        Text(routingCompletedStatus)
                        Text(destinationChangedStatus)
                        Text(routeErrorStatus)
                    }
                }
            }
        }
        try {
            naviHelper = NaviHelper(applicationContext)
            naviHelper?.initialize(
                onConnected = {
                    updateUi {
                        try {
                            naviHelper?.addListener(naviEventListener)
                            listenerRegistered = true
                            connected = true
                            connectionStatus = "연결 성공"
                        } catch (error: Exception) {
                            Log.e("TripbackNavi", "Listener registration failed", error)
                            connected = false
                            connectionStatus = "연결 실패 (리스너 등록 실패)"
                        }
                    }
                },
                onFailed = { error ->
                    Log.e("TripbackNavi", "NaviHelper service connection failed", error)
                    updateConnectionStatus("연결 실패")
                }
            )
        } catch (error: Exception) {
            Log.e("TripbackNavi", "NaviHelper initialization failed", error)
            updateConnectionStatus("연결 실패")
        }
    }

    private fun updateConnectionStatus(status: String) {
        updateUi {
            connected = false
            connectionStatus = status
        }
    }

    private fun updateUi(action: () -> Unit) {
        runOnUiThread {
            if (!closing && !isFinishing && !isDestroyed) {
                action()
            }
        }
    }

    private fun requestTestRoute() {
        if (!connected || closing || isFinishing || isDestroyed) return
        val helper = naviHelper ?: return
        routeStartedStatus = "탐색 시작: 미수신"
        routingCompletedStatus = "탐색 완료: 미수신"
        destinationChangedStatus = "목적지 변경: 미수신"
        routeErrorStatus = "SDK 오류: 미수신"
        requestStatus = "경로 요청 호출 (성공 판정 아님) / 결과는 아래 SDK 이벤트에서 확인"
        try {
            helper.requestRoute(
                RouteInfo(
                    latitude = 37.48544722,
                    longitude = 127.03636666,
                    poiName = "42dot",
                    poiId = "36khp37hh8gqcm5nqhe5",
                    address = "서울시 강남구 남부순환로 2621",
                    poiSubId = "0"
                )
            )
            Log.d("TripbackNavi", "requestRoute invoked for demo destination 42dot; result requires SDK callbacks")
        } catch (error: Exception) {
            Log.e("TripbackNavi", "requestRoute invocation failed", error)
            requestStatus = "경로 요청 호출 실패: ${error.javaClass.simpleName}"
        }
    }

    override fun onDestroy() {
        closing = true
        connected = false
        try {
            if (listenerRegistered) naviHelper?.removeListener(naviEventListener)
        } catch (error: Exception) {
            Log.e("TripbackNavi", "Listener removal failed", error)
        } finally {
            listenerRegistered = false
        }
        try {
            naviHelper?.release()
        } catch (error: Exception) {
            Log.e("TripbackNavi", "NaviHelper release failed", error)
        } finally {
            naviHelper = null
            super.onDestroy()
        }
    }
}

@Composable
fun ConnectionStatus(status: String, modifier: Modifier = Modifier) {
    Text(
        text = status,
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun ConnectionStatusPreview() {
    TripbackAppTheme {
        ConnectionStatus("내비게이션 연결 중")
    }
}
