package com.tripback.app.trip.progress

import kotlinx.coroutines.flow.StateFlow

data class DrivingSafety(val known: Boolean = false, val requiresOptimization: Boolean = true,
    val restrictions: Int = -1, val message: String = "차량 UX 제한 상태 확인 중 · 조작 차단") {
    // This app declares no DO. All complex UI is blocked unless positively unrestricted.
    val canInteract get() = known && !requiresOptimization && restrictions == 0
}
interface DrivingSafetyMonitor : AutoCloseable {
    val state: StateFlow<DrivingSafety>
    fun start()
}

fun drivingSafetyMonitor(context: android.content.Context): DrivingSafetyMonitor {
    fun unavailable() = object : DrivingSafetyMonitor {
        override val state = kotlinx.coroutines.flow.MutableStateFlow(DrivingSafety(message="차량 API 미지원 · 조작 차단"))
        override fun start() { }
        override fun close() { }
    }
    if(!context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)) return unavailable()
    return try { CarDrivingSafetyMonitor(context) } catch (_: LinkageError) { unavailable() }
}
