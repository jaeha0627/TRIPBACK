package com.tripback.app.trip

import java.io.Serializable

// Local form state, not an API DTO. Dates, timezone and verified origin are still required.
data class TripInput(
    val region: String = "서해안",
    val departTime: String = "08:30",
    val returnDeadline: String = "18:30",
    val safetyBufferMin: Int = 30,
    val companionType: String = "FAMILY",
    val childAgeGroup: String? = "ELEMENTARY",
    val interests: Set<String> = setOf("EXPERIENCE", "FOOD", "NATURE", "CAFE"),
    val vehicleType: String = "EV",
    val maximumOneWayMinutes: Int = 120,
    val budgetPerPersonKrw: Int? = null,
    val mealPreferences: Set<String> = emptySet(),
    val walkingPreference: String = "NORMAL",
    val parkingPreferences: Set<String> = emptySet(),
    val amenities: Set<String> = emptySet(),
    val preferIndoorWhenRaining: Boolean = false
) : Serializable

object TripChoices {
    val regions = listOf("서해안", "동해안", "남해안", "호수·산", "도심 근교")
    val companions = linkedMapOf("FAMILY" to "아이와", "COUPLE" to "둘이서", "FRIENDS" to "친구와", "PARENTS" to "부모님과", "SOLO" to "혼자")
    val childAges = linkedMapOf("INFANT" to "영유아", "ELEMENTARY" to "초등학생", "TEEN" to "청소년")
    val interests = linkedMapOf("EXPERIENCE" to "체험", "FOOD" to "맛집", "NATURE" to "산책·자연", "CAFE" to "카페", "CULTURE" to "전시·문화", "SHOPPING" to "시장·쇼핑", "SPA" to "온천·휴식", "PHOTO" to "사진 명소")
    val vehicles = linkedMapOf("EV" to "전기차", "ICE" to "휘발유·경유", "HEV" to "하이브리드", "LPG" to "LPG", "FCEV" to "수소차")
    val meals = linkedMapOf("KOREAN" to "한식", "SEAFOOD" to "해산물", "WESTERN" to "양식", "VEGETARIAN" to "채식", "KIDS" to "아이 메뉴")
    val walking = linkedMapOf("LOW" to "적게", "NORMAL" to "보통", "HIGH" to "많이")
    val parking = linkedMapOf("FREE" to "무료 주차", "WIDE" to "넓은 주차장", "NEAR" to "입구에서 가까운 곳")
    val amenities = linkedMapOf("ACCESSIBLE" to "유모차·휠체어", "NURSING_PLAYROOM" to "수유실·놀이방", "PET" to "반려견 동반 가능")
}

fun TripInput.validationErrors(): List<String> = buildList {
    fun minutes(value: String): Int? {
        if (!Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(value)) return null
        return value.take(2).toInt() * 60 + value.takeLast(2).toInt()
    }
    val depart = minutes(departTime)
    val deadline = minutes(returnDeadline)
    if (depart == null) add("출발시간을 HH:mm 형식(00:00~23:59)으로 입력해 주세요.")
    if (deadline == null) add("귀가 마감시간을 HH:mm 형식(00:00~23:59)으로 입력해 주세요.")
    if (depart != null && deadline != null) {
        if (deadline <= depart) add("같은 날 귀가하는 일정만 지원합니다. 자정을 넘기는 일정은 입력할 수 없습니다.")
        else if (deadline - depart <= 120) add("귀가 마감은 출발보다 2시간 넘게 뒤여야 합니다.")
    }
}

fun TripInput.summary(): String {
    fun labels(values: Set<String>, choices: Map<String, String>) =
        values.joinToString(" · ") { choices[it] ?: it }.ifEmpty { "선택 없음" }
    return listOf(
        "지역: $region",
        "시간: $departTime 출발 → $returnDeadline 귀가 마감 (같은 날)",
        "안전 여유: ${safetyBufferMin}분",
        "동행: ${TripChoices.companions[companionType]}" +
            if (companionType == "FAMILY") " / ${TripChoices.childAges[childAgeGroup]}" else "",
        "관심 활동: ${labels(interests, TripChoices.interests)}",
        "차량: ${TripChoices.vehicles[vehicleType]} (사용자 선택)",
        "편도 최대 이동: ${maximumOneWayMinutes}분",
        "1인 예산(식비·체험비): ${budgetPerPersonKrw?.let { "${it}원 이하" } ?: "상관없음"}",
        "식사: ${labels(mealPreferences, TripChoices.meals)}",
        "걷기: ${TripChoices.walking[walkingPreference]}",
        "주차: ${labels(parkingPreferences, TripChoices.parking)}",
        "편의시설: ${labels(amenities, TripChoices.amenities)}",
        "우천 시 실내 선호: ${if (preferIndoorWhenRaining) "예" else "아니요"}"
    ).joinToString("\n")
}
