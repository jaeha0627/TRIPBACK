package com.tripback.app.trip.recommendation

import com.tripback.app.trip.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Coordinates retain their source identity; a Kakao ID is NEVER a Pleos POI ID. */
data class NavigationDestination(val sourceId: ValidationPlaceId, val name: String,
    val address: String, val coordinates: ValidationPoint, val source: ValidationSource)

enum class NavigationStatus { IDLE, BLOCKED, REQUESTED, SEARCHING, ROUTING_COMPLETED, FAILED }
data class NavigationState(val status: NavigationStatus = NavigationStatus.IDLE,
    val message: String = "출발 승인 전 · 경로 요청 없음")
data class NavigationCapability(val supported: Boolean, val permissionGranted: Boolean,
    val message: String)
interface NavigationAdapter {
    val capability: StateFlow<NavigationCapability>
    /** Return means submitted only. Completion requires an event, never this return value. */
    fun request(destination: NavigationDestination, event: (NavigationState) -> Unit)
}
object UnsupportedNavigationAdapter : NavigationAdapter {
    override val capability = MutableStateFlow(NavigationCapability(false, false, "내비게이션 SDK 미지원"))
    override fun request(destination: NavigationDestination, event: (NavigationState) -> Unit) {
        event(NavigationState(NavigationStatus.BLOCKED, capability.value.message))
    }
}
/** Test-only adapter. Never substitutes for a production request or reports a live event. */
class FakeNavigationAdapter : NavigationAdapter {
    override val capability = MutableStateFlow(NavigationCapability(true, true, "DEMO_SIMULATED · 테스트 어댑터"))
    var requests = 0
        private set
    override fun request(destination: NavigationDestination, event: (NavigationState) -> Unit) {
        requests++
        event(NavigationState(NavigationStatus.REQUESTED, "DEMO_SIMULATED · 실제 경로 요청 없음"))
    }
}
object NavigationDestinationValidator {
    fun first(plan: RecommendationAlternative): NavigationDestination {
        require(plan.feasibility == ValidationFeasibility.FEASIBLE &&
            plan.validation.feasibility == ValidationFeasibility.FEASIBLE) { "FEASIBLE 일정만 출발할 수 있습니다." }
        require(plan.validation.dataCoverage == ValidationCoverage.ALL_PROVIDER_DATA_DECLARED_VALIDATED &&
            plan.dataModes.isNotEmpty() && ValidationDataMode.DEMO_SIMULATED !in plan.dataModes &&
            ValidationDataMode.DEMO_SIMULATED !in plan.validation.dataModes) { "시연·미검증 일정은 실제 목적지가 아닙니다." }
        val visit = plan.orderedVisits.firstOrNull() ?: error("첫 방문지가 없습니다.")
        val result = plan.validation.visits.firstOrNull() ?: error("장소 검증이 없습니다.")
        val place = result.confirmedPlace ?: error("장소가 확인되지 않았습니다.")
        require(visit.index == 0 && result.index == 0 && visit.id == result.requestedId &&
            visit.id == place.id && result.identityStatus == "MATCHED_SELECTED_ID" &&
            result.searchStatus == "FOUND" && result.failure == null && result.reason == null) { "첫 방문지 ID 검증에 실패했습니다." }
        require(place.data.provider == "KAKAO_LOCAL" && place.data.verification == "VALIDATED_PROVIDER_DATA" &&
            place.data.dataMode in setOf(ValidationDataMode.VERIFIED_LIVE, ValidationDataMode.VERIFIED_CACHED)) { "검증된 장소 출처가 필요합니다." }
        val c = place.coordinates
        require(c.latitude.isFinite() && c.longitude.isFinite() && c.latitude in -90.0..90.0 &&
            c.longitude in -180.0..180.0 && !(c.latitude == 0.0 && c.longitude == 0.0)) { "목적지 좌표가 올바르지 않습니다." }
        require(plan.validation.legs.firstOrNull()?.destination == c) { "검증 경로의 첫 목적지 좌표가 다릅니다." }
        return NavigationDestination(place.id, place.name, place.address, c, place.data)
    }
}
/** One attempt per session, including failures: uncorrelated SDK callbacks must not start another leg. */
class NavigationDeparture(private val adapter: NavigationAdapter) {
    private val mutable = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = mutable
    private var attempted = false
    fun approve(plan: RecommendationAlternative?, explicitApproval: Boolean, uxUnrestricted: Boolean) {
        if (attempted) return
        try {
            require(plan != null) { "선택한 일정이 없습니다." }
            val destination = NavigationDestinationValidator.first(plan)
            require(explicitApproval) { "사용자의 출발 승인이 필요합니다." }
            require(uxUnrestricted) { "CarUxRestrictions 미확인 또는 주행 제한 중입니다." }
            val capability = adapter.capability.value
            require(capability.permissionGranted) { "내비게이션 권한이 없습니다." }
            require(capability.supported) { capability.message }
            attempted = true
            mutable.value = NavigationState(NavigationStatus.REQUESTED, "요청 제출 중 · 경로 성공 아님")
            adapter.request(destination) { mutable.value = it }
        } catch (_: SecurityException) {
            mutable.value = NavigationState(NavigationStatus.FAILED, "내비게이션 권한 오류")
        } catch (failure: Exception) {
            mutable.value = NavigationState(if(attempted) NavigationStatus.FAILED else NavigationStatus.BLOCKED,
                if(attempted) "경로 요청 실패 · 자동 재시도하지 않습니다." else failure.message ?: "출발 조건을 확인해 주세요.")
        }
    }
}
