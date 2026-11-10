package com.tripback.app.trip.recommendation

import com.tripback.app.trip.*
import java.io.Serializable
import java.time.*

data class RecommendationRequest(
    val origin: ValidationPoint, val targetAreaKeyword: String, val targetCenter: ValidationPoint,
    val departAt: OffsetDateTime, val returnDeadline: OffsetDateTime, val safetyBufferMin: Int,
    val companionType: String, val interests: List<String>, val vehicleType: String, val maximumOneWayMinutes: Int,
    val childAgeGroup: String? = null, val budgetPerPersonKrw: Long? = null,
    val mealPreferences: List<String>? = null, val walkingPreference: String? = null,
    val parkingPreferences: List<String>? = null, val amenities: List<String>? = null,
    val preferIndoorWhenRaining: Boolean? = null, val mustVisitPlaceIds: List<ValidationPlaceId> = emptyList()
) {
    init {
        require(targetAreaKeyword.isNotBlank() && targetAreaKeyword == targetAreaKeyword.trim() && targetAreaKeyword.length <= 100)
        require(targetAreaKeyword.none(Char::isISOControl))
        require(returnDeadline.toInstant().isAfter(departAt.toInstant()) && safetyBufferMin in setOf(15,30,45))
        require(companionType in TripChoices.companions && vehicleType in TripChoices.vehicles && maximumOneWayMinutes in 1..180)
        fun choices(values: List<String>?, allowed: Set<String>) {
            require(values == null || (values.size <= allowed.size && values.distinct().size == values.size && values.all { it in allowed }))
        }
        choices(interests, TripChoices.interests.keys)
        require(companionType != "FAMILY" || childAgeGroup != null)
        require(childAgeGroup == null || childAgeGroup in TripChoices.childAges)
        require(budgetPerPersonKrw == null || budgetPerPersonKrw in 0..100_000_000)
        choices(mealPreferences, TripChoices.meals.keys); choices(parkingPreferences, TripChoices.parking.keys)
        choices(amenities, TripChoices.amenities.keys)
        require(walkingPreference == null || walkingPreference in TripChoices.walking)
        require(mustVisitPlaceIds.size <= 5 && mustVisitPlaceIds.distinct().size == mustVisitPlaceIds.size)
    }
}

data class AiProviderStatus(val provider: String, val status: String, val invocation: String)
data class RecommendationCalls(val placeSearches: Int, val roadRoutes: Int, val modelGenerations: Int)
data class OrderedVisit(val index: Int, val id: ValidationPlaceId, val originalKeyword: String, val stayMinutes: Long)
data class RecommendationAlternative(val planId: String, val title: String, val recommendationReason: String,
    val orderedVisits: List<OrderedVisit>, val validation: ValidationResult, val feasibility: ValidationFeasibility,
    val dataModes: Set<ValidationDataMode>, val notices: List<String>)
data class RejectedAlternative(val index: Int, val reason: String, val proposal: RecommendationAlternative?)
data class RecommendationCandidate(val originalKeyword: String, val id: ValidationPlaceId, val name: String,
    val address: String, val coordinates: ValidationPoint, val data: ValidationSource,
    val searchAttemptedAt: Instant, val visitStatus: String)
data class RecommendationSearch(val keyword: String, val status: String, val reason: String?,
    val failureCode: String?, val httpStatus: Int?, val attemptedAt: Instant?)
data class ExcludedCandidate(val keyword: String, val id: ValidationPlaceId?, val reason: String)
data class RecommendationResponse(val recommendationStatus: String, val aiProviderStatus: AiProviderStatus,
    val alternatives: List<RecommendationAlternative>, val rejectedAlternatives: List<RejectedAlternative>,
    val candidates: List<RecommendationCandidate>, val searches: List<RecommendationSearch>,
    val excludedCandidates: List<ExcludedCandidate>, val calls: RecommendationCalls,
    val conditionStatus: Map<String,String>, val limitations: List<String>)

enum class FakeScenario(val label: String) {
    TWO("정상 2안"), ONE("정상 1안"), ZERO("추천 0안"), INFEASIBLE("귀가 불가"),
    UNKNOWN("ETA 미확인 · null"), UNCONFIGURED("AI 미설정"), MODEL_ERROR("모델 오류"),
    TIMEOUT("모델 타임아웃"), BAD_JSON("잘못된 JSON"), SOURCES("출처 표시 안내")
}

/** This fixture form contains no user's location. Values are explicitly editable test coordinates. */
data class RecommendationForm(
    val area: String = "천안 독립기념관", val originLat: String = "36.8", val originLon: String = "127.15",
    val centerLat: String = "36.783581", val centerLon: String = "127.222969",
    val date: String = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString(),
    val depart: String = "09:30", val deadline: String = "18:00", val buffer: Int = 30,
    val companion: String = "SOLO", val child: String = "ELEMENTARY", val interests: List<String> = listOf("CULTURE","FOOD","CAFE"),
    val vehicle: String = "ICE", val oneWay: String = "90", val budget: String = "",
    val meals: List<String> = emptyList(), val walking: String = "NORMAL", val parking: List<String> = emptyList(),
    val amenities: List<String> = emptyList(), val rain: Boolean = false, val requiredIds: String = "",
    val parked: Boolean = false, val scenario: FakeScenario = FakeScenario.TWO
) : Serializable {
    fun request(): RecommendationRequest {
        require(parked) { "주차 상태에서 시연한다는 확인이 필요합니다." }
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(date))
        val day = LocalDate.parse(date)
        fun time(value: String): OffsetDateTime {
            require(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(value))
            return LocalDateTime.of(day, LocalTime.parse(value)).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime()
        }
        require(Regex("[0-9]+").matches(oneWay) && (budget.isEmpty() || Regex("[0-9]+").matches(budget)))
        val required = if (requiredIds.isBlank()) emptyList() else requiredIds.split(",").map { ValidationPlaceId("KAKAO_LOCAL", it.trim()) }
        return RecommendationRequest(ValidationPoint(originLat.toDouble(),originLon.toDouble()), area,
            ValidationPoint(centerLat.toDouble(),centerLon.toDouble()),time(depart),time(deadline),buffer,companion,
            interests,vehicle,oneWay.toInt(),if(companion=="FAMILY") child else null,budget.takeIf { it.isNotEmpty() }?.toLong(),
            meals,walking,parking,amenities,rain,required)
    }
}
