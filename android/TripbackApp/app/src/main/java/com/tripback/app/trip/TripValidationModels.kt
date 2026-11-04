package com.tripback.app.trip

import java.io.Serializable
import java.time.*

data class ValidationPoint(val latitude: Double, val longitude: Double) {
    init { require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0) }
}
data class ValidationPlaceId(val provider: String, val value: String) {
    init { require(provider == "KAKAO_LOCAL" && value.isNotBlank() && value == value.trim() && value.length <= 200) }
}
data class ValidationVisit(val keyword: String, val selectedId: ValidationPlaceId, val stayMinutes: Long) {
    init {
        require(keyword.isNotBlank() && keyword == keyword.trim() && keyword.length <= 200 && stayMinutes >= 0)
        Math.multiplyExact(stayMinutes, 60)
    }
}
data class ValidationRequest(val origin: ValidationPoint, val departAt: OffsetDateTime, val returnDeadline: OffsetDateTime,
    val safetyBufferMin: Int, val visits: List<ValidationVisit>) {
    init {
        require(returnDeadline.toInstant().isAfter(departAt.toInstant()) && safetyBufferMin in setOf(15, 30, 45))
        require(visits.size in 1..5 && visits.map { it.selectedId }.distinct().size == visits.size)
        val total = visits.fold(0L) { sum, visit -> Math.addExact(sum, visit.stayMinutes) }
        departAt.toInstant().plusSeconds(Math.multiplyExact(total, 60)).atOffset(departAt.offset)
    }
}

/** Uses TripSchedule's date, zone and explicit confirmation without imposing DEMO's >2-hour rule. */
data class ValidationForm(val latitude: String = "", val longitude: String = "", val schedule: TripSchedule = TripSchedule(),
    val departTime: String = "08:00", val deadlineTime: String = "18:00", val safetyBufferMin: Int = 30,
    val keyword: String = "", val placeId: String = "", val stayMinutes: String = "30", val parkedConfirmed: Boolean = false) : Serializable {
    fun request(): ValidationRequest {
        require(parkedConfirmed && schedule.confirmed) { "주차 상태와 날짜·시간대를 확인해 주세요." }
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(schedule.date))
        val date = LocalDate.parse(schedule.date)
        val zone = ZoneId.of(schedule.zoneId)
        fun timestamp(time: String): OffsetDateTime {
            require(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(time))
            val local = LocalDateTime.of(date, LocalTime.parse(time))
            require(zone.rules.getValidOffsets(local).size == 1)
            return local.atZone(zone).toOffsetDateTime()
        }
        require(Regex("[0-9]+").matches(stayMinutes))
        return ValidationRequest(ValidationPoint(latitude.toDouble(), longitude.toDouble()), timestamp(departTime), timestamp(deadlineTime),
            safetyBufferMin, listOf(ValidationVisit(keyword, ValidationPlaceId("KAKAO_LOCAL", placeId), stayMinutes.toLong())))
    }
}

enum class ValidationFeasibility { FEASIBLE, INFEASIBLE, UNKNOWN }
enum class ValidationCoverage { INCOMPLETE, CONTAINS_DEMO, ALL_PROVIDER_DATA_DECLARED_VALIDATED }
enum class ValidationDataMode { VERIFIED_LIVE, VERIFIED_CACHED, DEMO_SIMULATED }
data class ValidationSource(val provider: String, val dataMode: ValidationDataMode, val verification: String, val retrievedAt: Instant)
data class ValidationFailure(val code: String, val httpStatus: Int?)
data class ValidationConfirmedPlace(val id: ValidationPlaceId, val name: String, val address: String,
    val coordinates: ValidationPoint, val data: ValidationSource)
data class ValidationPlaceResult(val index: Int, val requestedId: ValidationPlaceId, val stayMinutes: Long,
    val confirmedPlace: ValidationConfirmedPlace?, val identityStatus: String, val visitStatus: String,
    val searchStatus: String?, val attemptedAt: Instant?, val reason: String?, val failure: ValidationFailure?)
data class ValidationLegResult(val index: Int, val origin: ValidationPoint?, val destination: ValidationPoint?, val durationSeconds: Long?,
    val status: String, val data: ValidationSource?, val attemptedAt: Instant?, val reason: String?, val failure: ValidationFailure?)
data class ValidationResult(val request: ValidationRequest, val feasibility: ValidationFeasibility, val dataCoverage: ValidationCoverage,
    val dataModes: Set<ValidationDataMode>, val visits: List<ValidationPlaceResult>, val legs: List<ValidationLegResult>,
    val totalTravelMinutes: Long?, val totalStayMinutes: Long?, val predictedReturnAt: OffsetDateTime?, val remainingMarginMin: Long?,
    val returnDeadlineMet: Boolean?, val safetyBufferSatisfied: Boolean?, val missingEtaLegIndexes: List<Int>?,
    val routeDataStatus: String?, val unknownReasons: List<String>, val calculationFailure: String?, val notice: String)
