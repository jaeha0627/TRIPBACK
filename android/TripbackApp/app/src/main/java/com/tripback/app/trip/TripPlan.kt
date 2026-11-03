package com.tripback.app.trip

import java.io.Serializable

enum class PlanDataMode { DEMO_SIMULATED }

// Local presentation models, not finalized server DTOs. IDs identify UI items only.
// No placeId/coordinates: these fictional stops must never be passed to navigation.
data class TripStop(
    val stopId: String,
    val name: String,
    val category: String,
    val stayMinutes: Int,
    val description: String,
    val dataMode: PlanDataMode = PlanDataMode.DEMO_SIMULATED
) : Serializable

data class TripPlan(
    val planId: String,
    val title: String,
    val description: String,
    val stops: List<TripStop>,
    val dataMode: PlanDataMode = PlanDataMode.DEMO_SIMULATED,
    // Unknown until deterministic server route/time checks; never infer from stay times.
    val predictedReturnAt: String? = null,
    val travelMinutes: Int? = null,
    val isFeasible: Boolean? = null
) : Serializable

data class TripPlanResults(
    val input: TripInput,
    val alternatives: List<TripPlan>,
    val selectedPlanId: String? = null
) : Serializable {
    val selectedPlan: TripPlan? get() = alternatives.find { it.planId == selectedPlanId }

    fun select(planId: String): TripPlanResults =
        if (alternatives.any { it.planId == planId }) copy(selectedPlanId = planId) else this
}

fun demoTripPlanResults(input: TripInput): TripPlanResults {
    require(input.validationErrors().isEmpty()) { "Valid trip input is required" }
    // Fixed examples independent of input. This is neither AI optimization nor feasibility validation.
    return TripPlanResults(input, listOf(
        TripPlan("demo-relaxed", "여유형", "방문 수를 줄인 고정 시연 예시입니다. 시간 여유가 검증된 코스는 아닙니다.", listOf(
            TripStop("relaxed-1", "시연 체험 공간", "체험", 60, "가상의 체험 장소에서 머무는 일정 예시"),
            TripStop("relaxed-2", "시연 식사 공간", "식사", 60, "가상의 식사 장소에서 쉬어가는 일정 예시"),
            TripStop("relaxed-3", "시연 휴식 공간", "휴식", 45, "가상의 휴식 장소에서 마무리하는 일정 예시")
        )),
        TripPlan("demo-full", "알찬형", "활동 종류를 늘린 고정 시연 예시입니다. 입력 조건 충족 여부는 검증하지 않았습니다.", listOf(
            TripStop("full-1", "시연 산책 공간", "산책·자연", 40, "가상의 산책 장소를 둘러보는 일정 예시"),
            TripStop("full-2", "시연 문화 공간", "전시·문화", 50, "가상의 문화 장소를 방문하는 일정 예시"),
            TripStop("full-3", "시연 식사 공간", "식사", 50, "가상의 식사 장소를 방문하는 일정 예시"),
            TripStop("full-4", "시연 체험 공간", "체험", 60, "가상의 체험 장소를 방문하는 일정 예시"),
            TripStop("full-5", "시연 카페 공간", "카페", 30, "가상의 카페 장소에서 마무리하는 일정 예시")
        ))
    ))
}
