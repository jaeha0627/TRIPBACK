package com.tripback.app.trip

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TripPlanApiTest {
    private val input = TripInput()
    private val schedule = TripSchedule("2026-11-07", "Asia/Seoul", true)
    private fun fixture() = javaClass.getResource("/trip-plan-response.json")!!.readText()

    @Test fun mapsOnlyV02FieldsWithExplicitDateAndOffset() {
        val extended = input.copy(budgetPerPersonKrw = 50000, walkingPreference = "LOW",
            mealPreferences = setOf("VEGETARIAN"), preferIndoorWhenRaining = true)
        val json = Json.parseToJsonElement(TripPlanJson.request(extended, schedule)).jsonObject
        assertEquals(setOf("dataMode", "region", "departAt", "returnDeadline", "safetyBufferMin", "companions", "interests", "vehicleType", "maximumOneWayMinutes"), json.keys)
        assertEquals("DEMO_SIMULATED", json.getValue("dataMode").jsonPrimitive.content)
        assertEquals("서해안", json.getValue("region").jsonPrimitive.content)
        assertEquals("2026-11-07T08:30:00+09:00", json.getValue("departAt").jsonPrimitive.content)
        assertEquals("2026-11-07T18:30:00+09:00", json.getValue("returnDeadline").jsonPrimitive.content)
        assertEquals(30, json.getValue("safetyBufferMin").jsonPrimitive.int)
        assertEquals("EV", json.getValue("vehicleType").jsonPrimitive.content)
        assertEquals(120, json.getValue("maximumOneWayMinutes").jsonPrimitive.int)
        assertEquals(input.interests, json.getValue("interests").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals("ELEMENTARY", json.getValue("companions").jsonObject.getValue("childAgeGroup").jsonPrimitive.content)
    }

    @Test fun omitsChildAgeForEveryNonFamilyCompanion() {
        TripChoices.companions.keys.filter { it != "FAMILY" }.forEach { type ->
            val companion = Json.parseToJsonElement(TripPlanJson.request(input.copy(companionType = type), schedule))
                .jsonObject.getValue("companions").jsonObject
            assertEquals(type, companion.getValue("type").jsonPrimitive.content)
            assertFalse(companion.containsKey("childAgeGroup"))
        }
    }

    @Test fun serializesTheSelectedTimeZoneRatherThanAssumingSeoul() {
        val json = Json.parseToJsonElement(TripPlanJson.request(input, schedule.copy(zoneId = "America/New_York"))).jsonObject
        assertEquals("-05:00", OffsetDateTime.parse(json.getValue("departAt").jsonPrimitive.content).offset.toString())
    }

    @Test fun requiresDateZoneConfirmationAndPreservesSameDayRules() {
        assertTrue(schedule.copy(confirmed = false).validationErrors(input).isNotEmpty())
        assertTrue(schedule.copy(date = "2026-02-30").validationErrors(input).isNotEmpty())
        assertTrue(schedule.copy(zoneId = "UNKNOWN").validationErrors(input).isNotEmpty())
        assertTrue(schedule.validationErrors(input.copy(departTime = "23:00", returnDeadline = "02:00")).isNotEmpty())
        assertTrue(schedule.validationErrors(input.copy(departTime = "08:30", returnDeadline = "10:30")).isNotEmpty())
        assertThrows(IllegalArgumentException::class.java) { TripPlanJson.request(input, schedule.copy(confirmed = false)) }
    }

    @Test fun rejectsDstGapOverlapAndActualIntervalOfTwoHoursOrLess() {
        val zone = "America/New_York"
        assertTrue(TripSchedule("2026-03-08", zone, true).validationErrors(input.copy(departTime = "02:30", returnDeadline = "08:00")).isNotEmpty())
        assertTrue(TripSchedule("2026-11-01", zone, true).validationErrors(input.copy(departTime = "01:30", returnDeadline = "08:00")).isNotEmpty())
        assertTrue(TripSchedule("2026-03-08", zone, true).validationErrors(input.copy(departTime = "00:30", returnDeadline = "03:31")).isEmpty())
        assertTrue(TripSchedule("2026-03-08", zone, true).validationErrors(input.copy(departTime = "00:30", returnDeadline = "03:30")).isNotEmpty())
    }

    @Test fun parsesServerMetadataAndBothAlternativesPreservingNullsAndDemoMode() {
        val result = TripPlanJson.response(fixture(), input, schedule)
        assertEquals(PlanSource.SERVER_DEMO, result.source)
        assertEquals("demo-contract-fixture", result.tripId)
        assertEquals(PlanDataMode.DEMO_SIMULATED, result.dataMode)
        assertEquals("NOT_ROUTE_TIME_VALIDATED", result.validationStatus)
        assertTrue(result.notice.contains("AI 최적화 결과가 아닙니다"))
        assertEquals(listOf("여유형", "알찬형"), result.alternatives.map { it.title })
        assertEquals(listOf(3, 5), result.alternatives.map { it.stops.size })
        result.alternatives.forEach { plan ->
            assertNull(plan.predictedReturnAt)
            assertNull(plan.travelMinutes)
            assertNull(plan.isFeasible)
            assertNull(plan.remainingMarginMin)
            assertEquals(PlanDataMode.DEMO_SIMULATED, plan.dataMode)
            assertTrue(plan.stops.all { it.dataMode == PlanDataMode.DEMO_SIMULATED })
        }
        assertEquals(schedule, result.schedule)
        assertEquals(input, result.input)
    }

    @Test fun distinguishesExplicitLocalDemoFromServerResponse() {
        val local = demoTripPlanResults(input)
        assertEquals(PlanSource.LOCAL_DEMO, local.source)
        assertNull(local.tripId)
        assertTrue(local.notice.contains("서버 응답이 아니며"))
    }

    @Test fun rejectsMalformedOrIncompatibleResponseInsteadOfInventingDefaults() {
        listOf("not-json", "{}", "[]", fixture().replace("DEMO_SIMULATED", "VERIFIED_LIVE"),
            fixture().replace("NOT_ROUTE_TIME_VALIDATED", "VALIDATED"),
            fixture().replace("\"travelMinutes\":null", "\"travelMinutes\":120"),
            fixture().replace("\"stayMinutes\":60", "\"stayMinutes\":\"60\""),
            fixture().replace("\"category\":\"체험\",", ""),
            fixture().replace("\"stopId\":\"relaxed-2\"", "\"stopId\":\"relaxed-1\""),
            fixture().replace("\"category\":\"체험\"", "\"category\":\"체험\",\"latitude\":0")
        ).forEach { body -> assertThrows(TripResponseException::class.java) { TripPlanJson.response(body, input, schedule) } }
    }

    private fun withServer(code: Int, body: String, type: String = "application/json", action: (String, AtomicReference<String>) -> Unit) {
        val captured = AtomicReference("")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/trips/plan") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            captured.set(exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", type)
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { action("http://127.0.0.1:${server.address.port}/api/v1/trips/plan", captured) }
        finally { server.stop(0) }
    }

    @Test fun realHttpClientPostsMappedJsonAndParsesResponse() {
        withServer(200, fixture()) { url, captured ->
            val result = TripPlanApi(url).request(input, schedule)
            assertEquals(PlanSource.SERVER_DEMO, result.source)
            assertEquals(Json.parseToJsonElement(TripPlanJson.request(input, schedule)), Json.parseToJsonElement(captured.get()))
        }
    }

    @Test fun handlesHttp400And500WithoutReturningLocalData() {
        listOf(400, 500).forEach { code ->
            withServer(code, "{\"error\":\"test\"}") { url, _ ->
                val error = assertThrows(TripHttpException::class.java) { TripPlanApi(url).request(input, schedule) }
                assertEquals(code, error.statusCode)
            }
        }
    }

    @Test fun handlesWrongContentTypeAndMalformedHttpJson() {
        withServer(200, fixture(), "text/html") { url, _ ->
            assertThrows(TripResponseException::class.java) { TripPlanApi(url).request(input, schedule) }
        }
        withServer(200, "{broken") { url, _ ->
            assertThrows(TripResponseException::class.java) { TripPlanApi(url).request(input, schedule) }
        }
    }

    @Test fun connectionFailureDoesNotReturnLocalData() {
        val port = ServerSocket(0).use { it.localPort }
        assertThrows(java.io.IOException::class.java) { TripPlanApi("http://127.0.0.1:$port/api/v1/trips/plan").request(input, schedule) }
    }

    @Test fun requestGateBlocksDuplicatesAndLateResponsesAfterInputOrScreenChanges() {
        val gate = PlanRequestGate()
        val first = gate.begin()!!
        assertNull(gate.begin())
        gate.invalidate()
        val second = gate.begin()!!
        assertFalse(gate.complete(first))
        assertNull(gate.begin())
        assertTrue(gate.complete(second))
        assertFalse(gate.complete(second))
        val third = gate.begin()!!
        gate.invalidate()
        assertFalse(gate.complete(third))
    }
}
