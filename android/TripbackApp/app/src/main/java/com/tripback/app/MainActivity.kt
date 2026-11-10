package com.tripback.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.tripback.app.ui.theme.TripbackAppTheme
import com.tripback.app.trip.TripPlannerScreen

class MainActivity : ComponentActivity() {
    private val safety by lazy { com.tripback.app.trip.progress.drivingSafetyMonitor(applicationContext) }
    private lateinit var sdkTest: DebugNaviTest
    override fun onStart() { super.onStart(); safety.start() }
    override fun onStop() { sdkTest.pause("ACTIVITY_STOP"); safety.close(); super.onStop() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sdkTest = DebugNaviTest(this)
        sdkTest.created(savedInstanceState != null)
        enableEdgeToEdge()
        setContent {
            TripbackAppTheme {
                val drivingSafety by safety.state.collectAsState()
                com.tripback.app.trip.progress.DrivingSafetyGate(drivingSafety) {
                    TripPlannerScreen { sdkTest.Content() }
                }
            }
        }
    }
    override fun onDestroy() { sdkTest.close(if (isChangingConfigurations) "ACTIVITY_RECREATION" else "ACTIVITY_DESTROY"); super.onDestroy() }
}
