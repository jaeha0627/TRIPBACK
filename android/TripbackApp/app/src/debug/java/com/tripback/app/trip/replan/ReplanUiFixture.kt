package com.tripback.app.trip.replan

import android.os.SystemClock
import com.tripback.app.trip.*
import com.tripback.app.trip.progress.*
import com.tripback.app.trip.recommendation.*
import kotlinx.serialization.json.*
import java.time.*

internal const val REPLAN_FIXTURE_PLAN_PREFIX = "debug-replan-fixture-"

enum class ReplanFixtureCase(val label: String) {
    NORMAL("정상 · 30 → 50분"), BUFFER_SHORTFALL("안전 버퍼 부족 · INFEASIBLE")
}

/** Dedicated logical fixture clock: starts at 2026-11-07 10:00 KST with ten simulated stay minutes.
 * Monotonic passage still enforces the existing 15-minute ETA / 30-minute reference limits.
 * This is not the vehicle clock, GPS, an arrival detector or a real trip observation.
 */
class ReplanFixtureClock(private val monotonicMillis: () -> Long = { SystemClock.elapsedRealtime() }) : Clock(), JourneyClock {
    private val anchor = monotonicMillis()
    override fun instant(): Instant = START.plusMillis((monotonicMillis() - anchor).coerceAtLeast(0))
    override fun wall(): Long = instant().toEpochMilli()
    override fun elapsed(): Long = 600_000 + (monotonicMillis() - anchor).coerceAtLeast(0)
    override fun getZone(): ZoneId = ZoneId.of("Asia/Seoul")
    override fun withZone(zone: ZoneId): Clock = object : Clock() {
        override fun instant() = this@ReplanFixtureClock.instant()
        override fun getZone() = zone
        override fun withZone(zone: ZoneId) = this@ReplanFixtureClock.withZone(zone)
    }
    companion object { val START: Instant = Instant.parse("2026-11-07T01:00:00Z") }
}

/** Independent synthetic fixture, modeled after Backend StayReplanServiceTests (30/20/10-minute
 * stays and 99/5/7/8-minute legs). Does NOT accept, copy or promote an existing DEMO journey.
 * VERIFIED_CACHED markers in raw are solely strict parser-contract samples, as in offline tests;
 * they are not real server verification. FakeReplanUiRepository renders all ETA evidence DEMO/UNVERIFIED.
 * The plan namespace is rejected by DebugReplanHttp before any network request.
 */
class ReplanUiFixture private constructor(val request: RecommendationRequest, val raw: String,
    val plan: RecommendationAlternative, val journey: TripJourney, val clock: ReplanFixtureClock,
    val case: ReplanFixtureCase) {
    companion object {
        fun create(case: ReplanFixtureCase = ReplanFixtureCase.NORMAL,
            clock: ReplanFixtureClock = ReplanFixtureClock()): ReplanUiFixture {
            val start = ReplanFixtureClock.START.atOffset(ZoneOffset.ofHours(9))
            val deadline = start.plusMinutes(if (case == ReplanFixtureCase.NORMAL) 180 else 105)
            val ids = (0..2).map { ValidationPlaceId("KAKAO_LOCAL", "debug-fixture-place-$it") }
            val request = RecommendationRequest(ValidationPoint(37.0, 127.0), "DEBUG 전용 가상 Fixture",
                ValidationPoint(37.01, 127.01), start.minusHours(2), deadline, 30, "SOLO",
                listOf("CULTURE"), "ICE", 180, mustVisitPlaceIds = listOf(ids.last()))
            val raw = wire(request, ids, case)
            val plan = RecommendationJson.response(raw, request).alternatives.single()
            val restored = JourneyCodec.encode(JourneyProgress(phase = JourneyPhase.STAYING, index = 0,
                stayStartWall = ReplanFixtureClock.START.minusSeconds(600).toEpochMilli(), stayStartElapsed = 0,
                stays = plan.orderedVisits.map { it.stayMinutes * 60 }))
            return ReplanUiFixture(request, raw, plan, TripJourney(plan, request, clock, restored), clock, case)
        }
        private fun wire(r: RecommendationRequest, ids: List<ValidationPlaceId>, case: ReplanFixtureCase): String {
            val stays = listOf(30L, 20L, 10L)
            val travel = listOf(99L, 5L, 7L, 8L)
            val points = (0..2).map { ValidationPoint(37.01 + it * .01, 127.01) }
            fun point(p: ValidationPoint) = buildJsonObject { put("latitude", p.latitude); put("longitude", p.longitude) }
            fun id(i: Int) = buildJsonObject { put("provider", ids[i].provider); put("value", ids[i].value) }
            fun source(provider: String) = buildJsonObject {
                put("provider", provider); put("dataMode", "VERIFIED_CACHED"); put("verification", "VALIDATED_PROVIDER_DATA")
                put("retrievedAt", ReplanFixtureClock.START.toString())
            }
            val places = ids.indices.map { i -> buildJsonObject {
                put("index", i); put("requestedId", id(i)); put("stayMinutes", stays[i])
                putJsonObject("confirmedPlace") {
                    put("id", id(i)); put("name", "Fixture 가상 방문지 ${i + 1}"); put("address", "실제 장소 아님 · TEST FIXTURE")
                    put("coordinates", point(points[i])); put("data", source("KAKAO_LOCAL"))
                }
                put("identityStatus", "MATCHED_SELECTED_ID"); put("visitStatus", "NOT_VERIFIED"); put("searchStatus", "FOUND")
                put("attemptedAt", ReplanFixtureClock.START.toString()); put("reason", JsonNull); put("failure", JsonNull)
            } }
            val returned = r.departAt.plusMinutes(stays.sum() + travel.sum())
            val margin = Duration.between(returned.toInstant(), r.returnDeadline.toInstant()).toMinutes()
            val validation = buildJsonObject {
                put("feasibility", "FEASIBLE"); put("dataCoverage", "ALL_PROVIDER_DATA_DECLARED_VALIDATED")
                put("dataModes", JsonArray(listOf(JsonPrimitive("VERIFIED_CACHED")))); put("visits", JsonArray(places))
                putJsonArray("legs") { (0..3).forEach { i -> add(buildJsonObject {
                    put("index", i); put("origin", point(if (i == 0) r.origin else points[i - 1]))
                    put("destination", point(if (i == 3) r.origin else points[i])); put("durationSeconds", travel[i] * 60)
                    put("status", "FOUND"); put("data", source("KAKAO_MOBILITY"))
                    put("attemptedAt", ReplanFixtureClock.START.toString()); put("reason", JsonNull); put("failure", JsonNull)
                }) } }
                put("totalTravelMinutes", travel.sum()); put("totalStayMinutes", stays.sum()); put("predictedReturnAt", returned.toString())
                put("remainingMarginMin", margin); put("returnDeadlineMet", true); put("safetyBufferSatisfied", true)
                put("missingEtaLegIndexes", JsonArray(emptyList())); put("routeDataStatus", "ALL_ETAS_DECLARED_VERIFIED")
                put("unknownReasons", JsonArray(emptyList())); put("calculationFailure", JsonNull)
                put("notice", "TEST_FIXTURE_ONLY_NOT_SERVER_VALIDATED")
            }
            return buildJsonObject {
                put("recommendationStatus", "PARTIAL")
                putJsonObject("aiProviderStatus") { put("provider", "DEBUG_FIXTURE"); put("status", "COMPLETED"); put("invocation", "FAKE_MODEL") }
                putJsonArray("alternatives") { add(buildJsonObject {
                    put("planId", REPLAN_FIXTURE_PLAN_PREFIX + case.name.lowercase()); put("title", "검증용 Fixture · 체류 30분")
                    put("recommendationReason", "테스트 Fixture · 실제 서버 검증 계획 아님")
                    putJsonArray("orderedVisits") { ids.indices.forEach { i -> add(buildJsonObject {
                        put("index", i); put("id", id(i)); put("originalKeyword", "TEST_FIXTURE_$i"); put("stayMinutes", stays[i])
                    }) } }
                    put("validation", validation); put("feasibility", "FEASIBLE"); put("dataModes", validation.getValue("dataModes"))
                    put("notices", JsonArray(listOf(JsonPrimitive("TEST_FIXTURE_ONLY_NOT_SERVER_VALIDATED"))))
                }) }
                put("rejectedAlternatives", JsonArray(emptyList()))
                putJsonArray("candidates") { ids.indices.forEach { i ->
                    val place = places[i].getValue("confirmedPlace").jsonObject
                    add(buildJsonObject {
                        put("originalKeyword", "TEST_FIXTURE_$i"); put("id", id(i)); put("name", place.getValue("name"))
                        put("address", place.getValue("address")); put("coordinates", point(points[i])); put("data", source("KAKAO_LOCAL"))
                        put("searchAttemptedAt", ReplanFixtureClock.START.toString()); put("visitStatus", "NOT_VERIFIED")
                    })
                } }
                put("searches", JsonArray(emptyList())); put("excludedCandidates", JsonArray(emptyList()))
                putJsonObject("calls") { put("placeSearches", 0); put("roadRoutes", 0); put("modelGenerations", 0) }
                putJsonObject("conditionStatus") { put("fixture", "DEBUG_ONLY_NOT_SERVER_VALIDATION") }
                put("limitations", JsonArray(listOf(JsonPrimitive("NO_HTTP_NO_PROVIDER_NO_VEHICLE_EVENTS"))))
            }.toString()
        }
    }
}
