package com.tripback.app.trip.progress

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow

/** Uses installed android.car optional platform library, no vehicle control permission or DO. */
class CarDrivingSafetyMonitor(private val context: Context) : DrivingSafetyMonitor {
    override val state = MutableStateFlow(DrivingSafety())
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var generation = 0
    private fun accept(value: CarUxRestrictions?) {
        state.value = if(value == null) DrivingSafety(message="차량 UX 상태 미확인 · 조작 차단")
        else DrivingSafety(true,value.isRequiresDistractionOptimization,value.activeRestrictions,
            "VERIFIED_LIVE · CarUxRestrictions flags=${value.activeRestrictions}")
    }
    override fun start() {
        if(car != null) return
        val token=++generation
        state.value=DrivingSafety()
        if(!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            state.value=DrivingSafety(message="차량 플랫폼 미지원 · 조작 차단");return
        }
        try {
            car=Car.createCar(context.applicationContext,Handler(Looper.getMainLooper()),Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected,ready ->
                if(token == generation) {
                    if(!ready) {
                        manager=null;state.value=DrivingSafety(message="차량 서비스 연결 해제 · 조작 차단")
                    } else try {
                        manager=connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                        manager?.registerListener { restrictions -> if(token == generation) accept(restrictions) }
                        accept(manager?.currentCarUxRestrictions)
                    } catch (_: Exception) { state.value=DrivingSafety(message="차량 UX 조회/권한 오류 · 조작 차단") }
                }
            }
        } catch (_: LinkageError) { state.value=DrivingSafety(message="차량 API 미지원 · 조작 차단") }
        catch (_: Exception) { state.value=DrivingSafety(message="차량 서비스 미지원/권한 오류 · 조작 차단") }
    }
    override fun close() {
        ++generation
        state.value=DrivingSafety(message="차량 UX 연결 중단 · 조작 차단")
        try { manager?.unregisterListener() } catch (_: Exception) { }
        manager=null
        try { car?.disconnect() } catch (_: Exception) { }
        car=null
    }
}
