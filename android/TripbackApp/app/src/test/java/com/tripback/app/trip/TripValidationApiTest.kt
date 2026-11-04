package com.tripback.app.trip

import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.*
import java.time.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TripValidationApiTest {
    internal fun input(n: Int = 1): ValidationRequest = ValidationForm("37.1", "127.1",
        TripSchedule("2026-11-07", "Asia/Seoul", true), keyword = "가상 장소 검색", placeId = "fixture-0", parkedConfirmed = true).request().let { request ->
        request.copy(visits = (0 until n).map { ValidationVisit("가상 장소 검색 $it", ValidationPlaceId("KAKAO_LOCAL", "fixture-$it"), 30) })
    }
    private fun point(p: ValidationPoint) = buildJsonObject { put("latitude", p.latitude); put("longitude", p.longitude) }
    private fun placePoint(i: Int) = ValidationPoint(36 + i * .01, 126 + i * .01)
    private fun id(value: String) = buildJsonObject { put("provider", "KAKAO_LOCAL"); put("value", value) }
    private fun source(provider: String) = buildJsonObject {
        put("provider", provider); put("dataMode", "DEMO_SIMULATED"); put("verification", "UNVERIFIED"); put("retrievedAt", "2026-11-03T00:00:00Z")
    }
    internal fun fixture(request: ValidationRequest = input(), missing: Set<Int> = emptySet(), mismatch: Boolean = false): String {
        val n = request.visits.size
        val unknown = missing.isNotEmpty() || mismatch
        val returned = request.departAt.plusMinutes(n * 30L + (n + 1) * 10L)
        val margin = Duration.between(returned.toInstant(), request.returnDeadline.toInstant()).toMinutes()
        return buildJsonObject {
            put("feasibility", if (unknown) "UNKNOWN" else if (margin >= request.safetyBufferMin) "FEASIBLE" else "INFEASIBLE")
            put("dataCoverage", if (unknown) "INCOMPLETE" else "CONTAINS_DEMO")
            putJsonArray("dataModes") { if (!mismatch) add("DEMO_SIMULATED") }
            putJsonArray("visits") { request.visits.forEachIndexed { i, visit -> add(buildJsonObject {
                put("index", i); put("requestedId", id(visit.selectedId.value)); put("stayMinutes", visit.stayMinutes)
                put("confirmedPlace", if (mismatch) JsonNull else buildJsonObject {
                    put("id", id(visit.selectedId.value)); put("name", "가상 장소 $i"); put("address", "가상 주소")
                    put("coordinates", point(placePoint(i))); put("data", source("KAKAO_LOCAL"))
                })
                put("identityStatus", if (mismatch) "UNCONFIRMED" else "MATCHED_SELECTED_ID"); put("visitStatus", "NOT_VERIFIED")
                put("searchStatus", "FOUND"); put("attemptedAt", "2026-11-03T00:00:00Z")
                put("reason", if (mismatch) JsonPrimitive("SELECTED_PLACE_NOT_FOUND") else JsonNull); put("failure", JsonNull)
            }) } }
            putJsonArray("legs") { (0..n).forEach { i -> add(buildJsonObject {
                val absent = mismatch || i in missing
                put("index", i)
                put("origin", if (mismatch) JsonNull else point(if (i == 0) request.origin else placePoint(i - 1)))
                put("destination", if (mismatch) JsonNull else point(if (i == n) request.origin else placePoint(i)))
                put("durationSeconds", if (absent) JsonNull else JsonPrimitive(600L))
                put("status", if (mismatch) "NOT_VALIDATED" else if (absent) "ETA_MISSING" else "FOUND")
                put("data", if (absent) JsonNull else source("KAKAO_MOBILITY"))
                put("attemptedAt", if (mismatch) JsonNull else JsonPrimitive("2026-11-03T00:00:00Z"))
                put("reason", if (mismatch) JsonPrimitive("PLACES_UNRESOLVED") else if (absent) JsonPrimitive("ETA_MISSING") else JsonNull)
                put("failure", if (!mismatch && absent) buildJsonObject { put("code", "MISSING_ETA"); put("httpStatus", JsonNull) } else JsonNull)
            }) } }
            put("totalTravelMinutes", if (unknown) JsonNull else JsonPrimitive((n + 1) * 10L)); put("totalStayMinutes", n * 30L)
            put("predictedReturnAt", if (unknown) JsonNull else JsonPrimitive(returned.toString()))
            put("remainingMarginMin", if (unknown) JsonNull else JsonPrimitive(margin))
            put("returnDeadlineMet", if (unknown) JsonNull else JsonPrimitive(margin >= 0))
            put("safetyBufferSatisfied", if (unknown) JsonNull else JsonPrimitive(margin >= request.safetyBufferMin))
            putJsonArray("missingEtaLegIndexes") { (if (mismatch) (0..n).toList() else missing.sorted()).forEach { add(it) } }
            put("routeDataStatus", if (unknown) "INCOMPLETE" else "UNVERIFIED")
            putJsonArray("unknownReasons") { if (unknown) add("MISSING_ETA") }
            put("calculationFailure", JsonNull); put("notice", "수학적 계산일 뿐 실제 귀가 보장이 아닙니다. 테스트 전용 DEMO 데이터입니다.")
        }.toString()
    }
    private class Fixture : Closeable {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val captured = AtomicReference("")
        val method = AtomicReference("")
        val contentType = AtomicReference("")
        val calls = AtomicInteger()
        val release = CountDownLatch(1)
        var status = 200
        var type = "application/json; charset=UTF-8"
        var bytes = byteArrayOf()
        var chunked = false
        var stalled = false
        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                calls.incrementAndGet(); method.set(exchange.requestMethod)
                contentType.set(exchange.requestHeaders.getFirst("Content-Type"))
                captured.set(exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() })
                try {
                    if (stalled) release.await()
                    exchange.responseHeaders.add("Content-Type", type)
                    if (status == 302) exchange.responseHeaders.add("Location", "/other")
                    exchange.sendResponseHeaders(status, if (chunked) 0 else bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                } catch (_: Exception) { /* Deliberately cancelled or limited requests. */ }
                finally { exchange.close() }
            }
            server.start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}/api/v1/trips/validate"
        fun api(read: Int = 2_000, total: Long = 4_000, limit: Int = 1_048_576) =
            TripValidationApi(url, true, 1_000, read, total, limit)
        override fun close() { release.countDown(); server.stop(0); executor.shutdownNow() }
    }
    private fun http(body: String, action: (Fixture) -> Unit) {
        Fixture().use { f -> f.bytes = body.toByteArray(Charsets.UTF_8); action(f) }
    }

    @Test fun serializesExactContractNumbersAndUserCoordinates() {
        val request = input()
        val root = Json.parseToJsonElement(TripValidationJson.request(request)).jsonObject
        assertEquals(setOf("origin", "departAt", "returnDeadline", "safetyBufferMin", "visits"), root.keys)
        assertEquals(37.1, root.getValue("origin").jsonObject.getValue("latitude").jsonPrimitive.double, 0.0)
        assertEquals(127.1, root.getValue("origin").jsonObject.getValue("longitude").jsonPrimitive.double, 0.0)
        assertFalse(root.getValue("safetyBufferMin").jsonPrimitive.isString)
        assertFalse(root.getValue("visits").jsonArray[0].jsonObject.getValue("stayMinutes").jsonPrimitive.isString)
    }
    @Test fun preservesDatesOffsetsKoreanAndSelectedOpaqueId() {
        val request = input().let { it.copy(departAt = it.departAt.withOffsetSameInstant(ZoneOffset.ofHours(-5)),
            returnDeadline = it.returnDeadline.withOffsetSameInstant(ZoneOffset.ofHours(-5)),
            visits = listOf(ValidationVisit("독립기념관 주차장", ValidationPlaceId("KAKAO_LOCAL", "00020515272"), 30))) }
        val root = Json.parseToJsonElement(TripValidationJson.request(request)).jsonObject
        assertEquals(request.departAt.toInstant(), OffsetDateTime.parse(root.getValue("departAt").jsonPrimitive.content).toInstant())
        assertEquals(ZoneOffset.ofHours(-5), OffsetDateTime.parse(root.getValue("departAt").jsonPrimitive.content).offset)
        val visit = root.getValue("visits").jsonArray[0].jsonObject
        assertEquals("독립기념관 주차장", visit.getValue("keyword").jsonPrimitive.content)
        assertEquals("00020515272", visit.getValue("selectedId").jsonObject.getValue("value").jsonPrimitive.content)
    }
    @Test fun formRequiresConfirmationValidCoordinatesAndSameDayTime() {
        val form = ValidationForm("37", "127", TripSchedule("2026-11-07", "Asia/Seoul", true), keyword = "가상 장소", placeId = "fixture", parkedConfirmed = true)
        assertEquals(ZoneOffset.ofHours(9), form.request().departAt.offset)
        assertTrue(form.copy(deadlineTime = "08:01").request().returnDeadline.isAfter(form.request().departAt))
        listOf(form.copy(latitude = "NaN"), form.copy(longitude = "181"), form.copy(stayMinutes = "-1"), form.copy(stayMinutes = "1.5"),
            form.copy(stayMinutes = Long.MAX_VALUE.toString()), form.copy(placeId = ""), form.copy(keyword = ""),
            form.copy(parkedConfirmed = false), form.copy(deadlineTime = "08:00"), form.copy(deadlineTime = "02:00"),
            form.copy(schedule = form.schedule.copy(confirmed = false)), form.copy(schedule = form.schedule.copy(date = "2026-02-30")))
            .forEach { bad -> assertThrows(Exception::class.java) { bad.request() } }
    }
    @Test fun formRejectsDstGapsAndAmbiguousTimes() {
        val form = ValidationForm("37", "127", TripSchedule("2026-03-08", "America/New_York", true), departTime = "02:30",
            keyword = "가상", placeId = "fixture", parkedConfirmed = true)
        assertThrows(Exception::class.java) { form.request() }
        assertThrows(Exception::class.java) { form.copy(schedule = form.schedule.copy(date = "2026-11-01"), departTime = "01:30").request() }
    }
    @Test fun requestSupportsUpToFiveVisitsAndRejectsDuplicates() {
        assertEquals(5, input(5).visits.size)
        assertThrows(IllegalArgumentException::class.java) { input(0) }
        assertThrows(IllegalArgumentException::class.java) { input(6) }
        assertThrows(IllegalArgumentException::class.java) { input().copy(visits = listOf(input().visits[0], input().visits[0])) }
    }
    @Test fun parsesFeasibleOneVisitTwoLegs() {
        val r = TripValidationJson.response(fixture(), input())
        assertEquals(ValidationFeasibility.FEASIBLE, r.feasibility); assertEquals(2, r.legs.size)
        assertEquals("가상 장소 0", r.visits[0].confirmedPlace!!.name); assertEquals("fixture-0", r.visits[0].requestedId.value)
        assertEquals(20L, r.totalTravelMinutes); assertEquals(30L, r.totalStayMinutes)
        assertEquals(ValidationCoverage.CONTAINS_DEMO, r.dataCoverage)
    }
    @Test fun parsesInfeasibleDeadlineAndSafetyBufferFailures() {
        for (minutes in listOf(49L, 60L)) {
            val request = input().let { it.copy(returnDeadline = it.departAt.plusMinutes(minutes)) }
            val r = TripValidationJson.response(fixture(request), request)
            assertEquals(ValidationFeasibility.INFEASIBLE, r.feasibility); assertEquals(false, r.safetyBufferSatisfied)
        }
    }
    @Test fun unknownRetainsNullFieldsAndMissingReturnIndex() {
        val r = TripValidationJson.response(fixture(missing = setOf(1)), input())
        assertEquals(ValidationFeasibility.UNKNOWN, r.feasibility); assertNull(r.predictedReturnAt); assertNull(r.remainingMarginMin)
        assertNull(r.safetyBufferSatisfied); assertNull(r.legs[1].durationSeconds); assertEquals(listOf(1), r.missingEtaLegIndexes)
    }
    @Test fun parsesMultipleVisitsAndKeepsIntermediateAndReturnIndexes() {
        for (n in listOf(3, 5)) {
            val request = input(n); val r = TripValidationJson.response(fixture(request, setOf(1)), request)
            assertEquals(n, r.visits.size); assertEquals((0..n).toList(), r.legs.map { it.index })
            assertEquals(request.origin, r.legs.last().destination); assertEquals(listOf(1), r.missingEtaLegIndexes)
        }
    }
    @Test fun parsesSelectedIdMismatchWithoutInventingPlaces() {
        val r = TripValidationJson.response(fixture(mismatch = true), input())
        assertNull(r.visits[0].confirmedPlace); assertEquals("SELECTED_PLACE_NOT_FOUND", r.visits[0].reason)
        assertEquals(ValidationFeasibility.UNKNOWN, r.feasibility); assertEquals(listOf(0, 1), r.missingEtaLegIndexes)
    }
    @Test fun pureSourceMetadataKeepsLiveCachedAndDemoDistinct() {
        // Label mapping only. No fake place or ETA is marked LIVE or CACHED.
        ValidationDataMode.values().forEach { mode ->
            val metadata = buildJsonObject {
                put("provider", "KAKAO_LOCAL"); put("dataMode", mode.name)
                put("verification", if (mode == ValidationDataMode.DEMO_SIMULATED) "UNVERIFIED" else "VALIDATED_PROVIDER_DATA")
                put("retrievedAt", "2026-11-03T00:00:00Z")
            }
            val parsed = with(TripValidationJson) { metadata.source() }
            assertEquals(mode, parsed.dataMode); assertEquals(Instant.parse("2026-11-03T00:00:00Z"), parsed.retrievedAt)
        }
    }
    @Test fun malformedContradictoryOrMismatchedResponseIsRejected() {
        listOf("broken", "{}", "[]", fixture().replace("\"index\":1", "\"index\":0"),
            fixture().replace("\"fixture-0\"", "\"wrong-id\""), fixture().replace("CONTAINS_DEMO", "ALL_PROVIDER_DATA_DECLARED_VALIDATED"),
            fixture().replace("\"durationSeconds\":600", "\"durationSeconds\":\"600\""),
            fixture().replace("\"visitStatus\":\"NOT_VERIFIED\"", "\"visitStatus\":\"VERIFIED\""),
            fixture(missing = setOf(1)).replace("\"predictedReturnAt\":null", "\"predictedReturnAt\":\"2026-11-07T09:00:00+09:00\""))
            .forEach { body -> assertThrows(ValidationResponseException::class.java) { TripValidationJson.response(body, input()) } }
    }
    @Test fun realHttpPostsUtf8OnceAndParsesOnlyValidationContract() {
        http(fixture()) { f -> runBlocking {
            val result = f.api().request(input()); assertEquals(ValidationFeasibility.FEASIBLE, result.feasibility)
            assertEquals(Json.parseToJsonElement(TripValidationJson.request(input())), Json.parseToJsonElement(f.captured.get()))
            assertTrue(f.captured.get().contains("가상 장소 검색")); assertEquals("POST", f.method.get())
            assertEquals("application/json; charset=UTF-8", f.contentType.get()); assertEquals(1, f.calls.get())
        } }
    }
    @Test fun http400404500RemainFailuresWithoutDemoFallback() {
        listOf(400, 404, 500).forEach { code -> http("server-error-body") { f ->
            f.status = code
            val error = assertThrows(ValidationHttpException::class.java) { runBlocking { f.api().request(input()) } }
            assertEquals(code, error.statusCode); assertFalse(validationErrorMessage(error).contains("server-error-body"))
        } }
    }
    @Test fun redirectIsNotFollowed() {
        http("redirect") { f -> f.status = 302
            assertThrows(ValidationHttpException::class.java) { runBlocking { f.api().request(input()) } }; assertEquals(1, f.calls.get())
        }
    }
    @Test fun malformedJsonOrWrongContentTypeFailsSafely() {
        http("{broken") { f -> assertThrows(ValidationResponseException::class.java) { runBlocking { f.api().request(input()) } } }
        http(fixture()) { f -> f.type = "text/html"; assertThrows(ValidationResponseException::class.java) { runBlocking { f.api().request(input()) } } }
    }
    @Test fun malformedUtf8IsRejected() {
        http(fixture()) { f -> f.bytes = byteArrayOf(0xc3.toByte(), 0x28)
            assertThrows(ValidationResponseException::class.java) { runBlocking { f.api().request(input()) } }
        }
    }
    @Test fun declaredAndChunkedOversizedResponsesAreBounded() {
        for (chunked in listOf(false, true)) http(" ".repeat(20_000)) { f -> f.chunked = chunked
            assertThrows(ValidationResponseException::class.java) { runBlocking { f.api(limit = 1024).request(input()) } }
        }
    }
    @Test fun serverConnectionFailureIsReported() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        assertThrows(java.io.IOException::class.java) { runBlocking {
            TripValidationApi("http://127.0.0.1:$port/api/v1/trips/validate", true).request(input())
        } }
    }
    @Test fun stalledServerTimesOut() {
        http(fixture()) { f -> f.stalled = true
            assertThrows(SocketTimeoutException::class.java) { runBlocking { f.api(read = 100).request(input()) } }
        }
    }
    @Test fun totalDeadlineAlsoCancelsSlowRequests() {
        http(fixture()) { f -> f.stalled = true
            assertThrows(SocketTimeoutException::class.java) { runBlocking { f.api(read = 3_000, total = 150).request(input()) } }
        }
    }
    @Test fun coroutineCancellationIsNotConvertedToSuccessOrDemo() {
        http(fixture()) { f -> f.stalled = true
            runBlocking {
                val job = launch { f.api().request(input()); fail("Cancelled request returned") }
                withTimeout(2_000) { while (f.calls.get() == 0) delay(5) }
                job.cancelAndJoin(); assertTrue(job.isCancelled)
            }
        }
    }
    @Test fun cleartextRequiresExplicitLocalDebugOptIn() {
        assertThrows(IllegalArgumentException::class.java) { TripValidationApi() }
        listOf("http://example.com/api/v1/trips/validate", "http://user@localhost/api/v1/trips/validate",
            "http://localhost/api/v1/trips/validate?key=bad").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { TripValidationApi(url, true) }
        }
    }
}
