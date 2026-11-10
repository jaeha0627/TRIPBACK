package com.tripback.app
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
/** Release has no SDK test controls, listener, initialization or route request code. */
class DebugNaviTest(@Suppress("UNUSED_PARAMETER") activity: ComponentActivity) {
    @Composable fun Content() { }
    fun created(@Suppress("UNUSED_PARAMETER") restored: Boolean) { }
    fun pause(@Suppress("UNUSED_PARAMETER") reason: String = "") { }
    fun close(@Suppress("UNUSED_PARAMETER") reason: String = "") { }
}
