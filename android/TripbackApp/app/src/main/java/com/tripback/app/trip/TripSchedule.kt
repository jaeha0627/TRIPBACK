package com.tripback.app.trip

import java.io.Serializable
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

data class TripSchedule(
    val date: String = LocalDate.now().toString(),
    val zoneId: String = ZoneId.systemDefault().id,
    val confirmed: Boolean = false
) : Serializable {
    fun validationErrors(input: TripInput): List<String> = buildList {
        addAll(input.validationErrors())
        val day = runCatching {
            require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(date))
            LocalDate.parse(date)
        }.getOrNull()
        val zone = runCatching { ZoneId.of(zoneId) }.getOrNull()
        if (day == null) add("여행 날짜를 YYYY-MM-DD 형식의 유효한 날짜로 선택해 주세요.")
        if (zone == null) add("기기 시간대를 확인해 주세요.")
        if (isEmpty() && day != null && zone != null) {
            val start = LocalDateTime.of(day, LocalTime.parse(input.departTime))
            val end = LocalDateTime.of(day, LocalTime.parse(input.returnDeadline))
            if (zone.rules.getValidOffsets(start).size != 1 || zone.rules.getValidOffsets(end).size != 1) {
                add("시간대 전환으로 존재하지 않거나 중복되는 시각입니다. 다른 출발·귀가 시각을 선택해 주세요.")
            } else if (Duration.between(start.atZone(zone).toInstant(), end.atZone(zone).toInstant()) <= Duration.ofHours(2)) {
                add("시간대 적용 후에도 귀가 마감이 출발보다 2시간 넘게 뒤여야 합니다.")
            }
        }
        if (!confirmed) add("여행 날짜와 기기 시간대를 확인해 주세요.")
    }

    fun timestamps(input: TripInput): Pair<OffsetDateTime, OffsetDateTime> {
        require(validationErrors(input).isEmpty())
        val day = LocalDate.parse(date)
        val zone = ZoneId.of(zoneId)
        return LocalDateTime.of(day, LocalTime.parse(input.departTime)).atZone(zone).toOffsetDateTime() to
            LocalDateTime.of(day, LocalTime.parse(input.returnDeadline)).atZone(zone).toOffsetDateTime()
    }
}

// Main-thread request ownership. Invalidating prevents late responses from updating another screen/input.
class PlanRequestGate {
    private var generation = 0L
    private var active: Long? = null

    fun begin(): Long? {
        if (active != null) return null
        return (++generation).also { active = it }
    }

    fun complete(token: Long): Boolean {
        if (active != token) return false
        active = null
        return true
    }

    fun invalidate() { generation++; active = null }
}
