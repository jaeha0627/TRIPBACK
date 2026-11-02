package com.tripback.app

import android.os.Bundle
import android.util.Log
import ai.pleos.playground.navi.helper.NaviHelper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TripbackAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ConnectionStatus(
                        status = connectionStatus,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
        try {
            naviHelper = NaviHelper(applicationContext)
            naviHelper?.initialize(
                onConnected = { updateConnectionStatus("연결 성공") },
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
        runOnUiThread {
            if (!closing && !isFinishing && !isDestroyed) {
                connectionStatus = status
            }
        }
    }

    override fun onDestroy() {
        closing = true
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
