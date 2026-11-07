package com.tripback.app.trip.replan

import com.tripback.app.trip.*
import com.tripback.app.trip.recommendation.*
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import java.io.Closeable
import java.net.InetSocketAddress
import java.time.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/** Labels below describe fake trusted server output only. No place/provider is contacted. */
internal object ReplanFixtures {
    val now: Instant = Instant.parse("2026-11-07T01:00:00Z")
    const val token = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    class TestClock(@Volatile var at: Instant = now) : Clock() {
        override fun instant() = at
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    val input = TripValidationApiTest().input(2)
    val request = RecommendationForm(parked = true, date = "2026-11-07").request().copy(
        origin = input.origin, departAt = input.departAt, returnDeadline = input.returnDeadline,
        safetyBufferMin = input.safetyBufferMin, mustVisitPlaceIds = listOf(input.visits.last().selectedId))
    fun JsonObject.edit(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(toMutableMap().apply { putAll(pairs) })
    fun verified(at: Instant = now): JsonObject {
        val original = Json.parseToJsonElement(TripValidationApiTest().fixture(input)).jsonObject
        fun source(s: JsonObject) = s.edit("dataMode" to JsonPrimitive("VERIFIED_CACHED"),
            "verification" to JsonPrimitive("VALIDATED_PROVIDER_DATA"), "retrievedAt" to JsonPrimitive(at.toString()))
        val visits = original.getValue("visits").jsonArray.map { v -> v.jsonObject.let { p ->
            val c = p.getValue("confirmedPlace").jsonObject
            p.edit("confirmedPlace" to c.edit("data" to source(c.getValue("data").jsonObject)))
        } }
        val legs = original.getValue("legs").jsonArray.map { l -> l.jsonObject.let {
            it.edit("data" to source(it.getValue("data").jsonObject))
        } }
        return original.edit("visits" to JsonArray(visits), "legs" to JsonArray(legs),
            "dataModes" to JsonArray(listOf(JsonPrimitive("VERIFIED_CACHED"))),
            "dataCoverage" to JsonPrimitive("ALL_PROVIDER_DATA_DECLARED_VALIDATED"),
            "routeDataStatus" to JsonPrimitive("ALL_ETAS_DECLARED_VERIFIED"))
    }
    fun recommendation(validation: JsonObject = verified()): String = buildJsonObject {
        put("recommendationStatus", "PARTIAL")
        putJsonObject("aiProviderStatus") { put("provider", "FAKE"); put("status", "COMPLETED"); put("invocation", "FAKE_MODEL") }
        putJsonArray("alternatives") { add(buildJsonObject {
            put("planId", "fake-plan-1"); put("title", "Fake itinerary"); put("recommendationReason", "Offline fixture")
            putJsonArray("orderedVisits") { input.visits.forEachIndexed { i, v -> add(buildJsonObject {
                put("index", i); put("id", id(v.selectedId)); put("originalKeyword", v.keyword); put("stayMinutes", v.stayMinutes)
            }) } }
            put("validation", validation); put("feasibility", "FEASIBLE")
            put("dataModes", validation.getValue("dataModes")); put("notices", JsonArray(listOf(JsonPrimitive("FAKE_ONLY"))))
        }) }
        put("rejectedAlternatives", JsonArray(emptyList()))
        putJsonArray("candidates") { validation.getValue("visits").jsonArray.forEachIndexed { i, p ->
            val c = p.jsonObject.getValue("confirmedPlace").jsonObject
            add(buildJsonObject {
                put("originalKeyword", input.visits[i].keyword); put("id", c.getValue("id")); put("name", c.getValue("name"))
                put("address", c.getValue("address")); put("coordinates", c.getValue("coordinates")); put("data", c.getValue("data"))
                put("searchAttemptedAt", now.toString()); put("visitStatus", "NOT_VERIFIED")
            })
        } }
        put("searches", JsonArray(emptyList())); put("excludedCandidates", JsonArray(emptyList()))
        putJsonObject("calls") { put("placeSearches", 0); put("roadRoutes", 0); put("modelGenerations", 0) }
        put("conditionStatus", JsonObject(emptyMap())); put("limitations", JsonArray(listOf(JsonPrimitive("FAKE_ONLY"))))
    }.toString()
    fun selection() = ReplanSelection.fromRecommendation(recommendation(), request, "fake-plan-1")
    fun headers(): MutableMap<String, List<String>> = mutableMapOf(
        "X-Tripback-Plan-Registration" to listOf("REGISTERED"), "X-Tripback-Plan-Ref" to listOf(token),
        "X-Tripback-Plan-Revision" to listOf("1"), "X-Tripback-Plan-Expires-At" to listOf(now.plusSeconds(1800).toString()))
    fun registered(body: JsonObject = verified(), server: String = "http://127.0.0.1:8080") =
        ReplanJson.registration(body.toString(), headers(), selection(), server, now).plan!!
    fun observation(total: Long = 50, elapsed: Long = 600) = ReplanObservation(0, now.atOffset(ZoneOffset.UTC), elapsed, total, 7)
    fun id(id: ValidationPlaceId) = buildJsonObject { put("provider", id.provider); put("value", id.value) }
    fun preview(plan: RegisteredReplanPlan, observation: ReplanObservation = observation(), at: Instant = now,
        missing: Boolean = false): JsonObject {
        val snapshot = plan.validation
        val lag = Duration.between(observation.observedAt.toInstant(), at).seconds
        val stale = snapshot.legs.any { Duration.between(it.data!!.retrievedAt, at) > Duration.ofMinutes(15) }
        fun projection(total: Long): JsonObject = buildJsonObject {
            val remaining = ReplanJson.ceilMinutes((total * 60 - observation.elapsedStaySeconds - lag).coerceAtLeast(0))
            val future = snapshot.request.visits.drop(observation.currentVisitIndex + 1).sumOf { it.stayMinutes }
            val legs = snapshot.legs.drop(observation.currentVisitIndex + 1)
            val predicted = at.plusSeconds((remaining + future + legs.sumOf { ReplanJson.ceilMinutes(it.durationSeconds!!) }) * 60)
            val margin = Duration.between(predicted, input.returnDeadline.toInstant()).seconds
            val arithmetic = if (margin >= input.safetyBufferMin * 60) "FEASIBLE" else "INFEASIBLE"
            put("feasibility", if (stale || missing) "UNKNOWN" else arithmetic)
            put("arithmeticFeasibility", if (missing) "UNKNOWN" else arithmetic)
            put("predictedReturnAt", if (missing) JsonNull else JsonPrimitive(predicted.toString()))
            put("remainingMarginSeconds", if (missing) JsonNull else JsonPrimitive(margin))
            put("remainingMarginMinutes", if (missing) JsonNull else JsonPrimitive(Math.floorDiv(margin, 60)))
            put("returnDeadlineMet", if (missing) JsonNull else JsonPrimitive(margin >= 0))
            put("safetyBufferSatisfied", if (missing) JsonNull else JsonPrimitive(margin >= input.safetyBufferMin * 60))
            put("currentRemainingStayMinutes", remaining)
            putJsonArray("remainingLegs") { legs.forEachIndexed { offset, leg -> add(buildJsonObject {
                val absent = missing && offset == 0
                put("originalLegIndex", leg.index)
                put("minutes", if (absent) JsonNull else JsonPrimitive(ReplanJson.ceilMinutes(leg.durationSeconds!!)))
                put("source", if (absent) JsonNull else JsonPrimitive(leg.data!!.provider))
                put("dataMode", if (absent) JsonNull else JsonPrimitive("VERIFIED_CACHED"))
                put("verification", if (absent) JsonNull else JsonPrimitive("VERIFIED"))
                put("retrievedAt", if (absent) JsonNull else JsonPrimitive(leg.data!!.retrievedAt.toString()))
                put("cached", !absent)
            }) } }
            putJsonArray("reasons") {
                add("CACHED_ETA_ASSUMPTION"); if (stale) add("STALE_ETA")
                if (missing) { add("MISSING_ETA"); add("UNTRUSTED_ETA") }
                if (margin < 0) add("DEADLINE_EXCEEDED") else if (margin < input.safetyBufferMin * 60) add("SAFETY_BUFFER_SHORTFALL")
            }
        }
        return buildJsonObject {
            put("status", "PREVIEW_ONLY"); put("baseRevision", plan.reference.revision)
            put("planExpiresAt", plan.reference.expiresAt.toString()); put("calculatedAt", at.toString())
            put("progressVerification", "CLIENT_REPORTED_NOT_VERIFIED"); put("currentVisitIndex", observation.currentVisitIndex)
            put("safetyBufferMinutes", input.safetyBufferMin); put("returnDeadline", input.returnDeadline.toString())
            putJsonObject("originalHome") { put("latitude", input.origin.latitude); put("longitude", input.origin.longitude) }
            put("orderedVisits", JsonArray(input.visits.map { id(it.selectedId) })); put("requiredVisits", JsonArray(input.visits.map { id(it.selectedId) }))
            put("before", projection(input.visits[observation.currentVisitIndex].stayMinutes)); put("after", projection(observation.requestedTotalStayMinutes))
            put("notices", JsonArray(listOf("NO_PLAN_COMMIT_OR_APPROVAL", "NO_PROVIDER_CALLS", "CACHED_ETAS_NOT_CURRENT_TRAFFIC",
                "MATHEMATICAL_RESULT_NOT_RETURN_GUARANTEE", "PROGRESS_NOT_VEHICLE_VERIFIED",
                "PRIVATE_LOOPBACK_DEVELOPMENT_ONLY_NOT_AUTHENTICATED").map(::JsonPrimitive)))
        }
    }

    class Server : Closeable {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val executor = Executors.newCachedThreadPool()
        val base get() = "http://127.0.0.1:${server.address.port}"
        val registrations = AtomicInteger(); val previews = AtomicInteger()
        val captured = AtomicReference(""); val registrationHeader = AtomicReference<String?>()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        @Volatile var stalled = false
        @Volatile var registerBody = verified().toString()
        val responseHeaders = headers()
        @Volatile var previewBody = preview(registered()).toString()
        @Volatile var previewBytes: ByteArray? = null
        @Volatile var status = 200
        @Volatile var type = "application/json; charset=UTF-8"
        @Volatile var chunked = false
        init {
            server.executor = executor
            server.createContext("/") { e ->
                try {
                    val register = e.requestURI.path == "/api/v1/trips/validate"
                    if (register) registrations.incrementAndGet() else previews.incrementAndGet()
                    captured.set(e.requestBody.use { it.readBytes().toString(Charsets.UTF_8) })
                    registrationHeader.set(e.requestHeaders.getFirst("X-Tripback-Register-Replan"))
                    entered.countDown(); if (stalled) release.await(5, TimeUnit.SECONDS)
                    e.responseHeaders.set("Content-Type", type)
                    if (register) responseHeaders.forEach { (k, values) -> values.forEach { e.responseHeaders.add(k, it) } }
                    val bytes = if (!register && previewBytes != null) previewBytes!! else
                        (if (register && status == 200) registerBody else previewBody).toByteArray(Charsets.UTF_8)
                    e.sendResponseHeaders(status, if (chunked) 0 else bytes.size.toLong())
                    e.responseBody.use { it.write(bytes) }
                } catch (_: Exception) { e.close() }
            }
            server.start()
        }
        fun client(clock: Clock = TestClock(), read: Int = 1000, total: Long = 3000, limit: Int = 1_048_576) =
            DebugReplanHttp(base, clock, 500, read, total, limit)
        override fun close() { release.countDown(); server.stop(0); executor.shutdownNow() }
    }
}
