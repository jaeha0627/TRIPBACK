package com.tripback.app.trip

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*

object TripBackendConfig {
    const val PLAN_URL = "http://10.0.2.2:8080/api/v1/trips/plan"
    const val CONNECT_TIMEOUT_MS = 5_000
    const val READ_TIMEOUT_MS = 10_000
}

class TripHttpException(val statusCode: Int) : IOException("HTTP $statusCode")
class TripResponseException(cause: Exception) : IOException("서버 응답 JSON이 DEMO 계약과 일치하지 않습니다.", cause)

object TripPlanJson {
    fun request(input: TripInput, schedule: TripSchedule): String {
        val (depart, deadline) = schedule.timestamps(input)
        require(input.region.isNotBlank() && input.region.length <= 100)
        require(input.safetyBufferMin in setOf(15, 30, 45) && input.maximumOneWayMinutes in 1..180)
        require(input.companionType in TripChoices.companions && input.vehicleType in TripChoices.vehicles)
        require(input.interests.all { it in TripChoices.interests })
        if (input.companionType == "FAMILY") require(input.childAgeGroup in TripChoices.childAges)
        return buildJsonObject {
            put("dataMode", "DEMO_SIMULATED")
            put("region", input.region)
            put("departAt", depart.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            put("returnDeadline", deadline.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            put("safetyBufferMin", input.safetyBufferMin)
            putJsonObject("companions") {
                put("type", input.companionType)
                if (input.companionType == "FAMILY") put("childAgeGroup", input.childAgeGroup)
            }
            putJsonArray("interests") { input.interests.forEach { add(it) } }
            put("vehicleType", input.vehicleType)
            put("maximumOneWayMinutes", input.maximumOneWayMinutes)
        }.toString()
    }

    fun response(body: String, input: TripInput, schedule: TripSchedule): TripPlanResults {
        try {
            val root = Json.parseToJsonElement(body).jsonObject
            root.demoMode()
            val tripId = root.text("tripId")
            val status = root.text("validationStatus")
            require(status == "NOT_ROUTE_TIME_VALIDATED")
            val notice = root.text("notice")
            val alternatives = root.getValue("alternatives").jsonArray
            require(alternatives.size == 2)
            val plans = alternatives.map { element ->
                val plan = element.jsonObject
                plan.demoMode()
                // v0.2 promises nulls: reject unexpected calculations rather than imply verification.
                listOf("predictedReturnAt", "travelMinutes", "isFeasible", "remainingMarginMin").forEach {
                    require(plan.getValue(it) == JsonNull)
                }
                val stops = plan.getValue("stops").jsonArray.map { stopElement ->
                    val stop = stopElement.jsonObject
                    stop.demoMode()
                    require(listOf("placeId", "poiId", "latitude", "longitude").none { it in stop })
                    val minutes = stop.getValue("stayMinutes").jsonPrimitive
                    require(!minutes.isString && minutes.int > 0)
                    TripStop(stop.text("stopId"), stop.text("name"), stop.text("category"), minutes.int, stop.text("description"))
                }
                require(stops.isNotEmpty() && stops.map { it.stopId }.distinct().size == stops.size)
                TripPlan(plan.text("planId"), plan.text("title"), plan.text("description"), stops)
            }
            require(plans.map { it.planId }.distinct().size == plans.size)
            return TripPlanResults(input, plans, source = PlanSource.SERVER_DEMO, tripId = tripId,
                validationStatus = status, notice = notice, schedule = schedule)
        } catch (error: Exception) {
            throw TripResponseException(error)
        }
    }

    private fun JsonObject.text(key: String): String {
        val value = getValue(key).jsonPrimitive
        require(value.isString && value.content.isNotBlank())
        return value.content
    }

    private fun JsonObject.demoMode() { require(text("dataMode") == "DEMO_SIMULATED") }
}

// Blocking client: callers must run this on Dispatchers.IO, never on the UI thread.
class TripPlanApi(private val endpoint: String = TripBackendConfig.PLAN_URL) {
    fun request(input: TripInput, schedule: TripSchedule): TripPlanResults {
        val payload = TripPlanJson.request(input, schedule).toByteArray(Charsets.UTF_8)
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TripBackendConfig.CONNECT_TIMEOUT_MS
            connection.readTimeout = TripBackendConfig.READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            val code = connection.responseCode
            if (code != 200) throw TripHttpException(code)
            val contentType = connection.contentType?.substringBefore(';')?.trim()
            if (!contentType.equals("application/json", ignoreCase = true)) {
                throw TripResponseException(IllegalArgumentException("Expected application/json"))
            }
            val body = connection.inputStream.use { stream ->
                val bytes = stream.readBytesLimited(1_048_576)
                bytes.toString(Charsets.UTF_8)
            }
            return TripPlanJson.response(body, input, schedule)
        } finally {
            connection.disconnect()
        }
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count == -1) break
        if (output.size() + count > limit) throw IOException("서버 응답 크기가 제한을 초과했습니다.")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
