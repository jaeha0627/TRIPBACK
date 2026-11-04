package com.tripback.app.trip

import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*

class ValidationResponseException : IOException("검증 API 응답 검증 실패")

object TripValidationJson {
    fun request(input: ValidationRequest): String = buildJsonObject {
        put("origin", point(input.origin))
        put("departAt", input.departAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        put("returnDeadline", input.returnDeadline.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        put("safetyBufferMin", input.safetyBufferMin)
        putJsonArray("visits") { input.visits.forEach { visit -> add(buildJsonObject {
            put("keyword", visit.keyword)
            putJsonObject("selectedId") { put("provider", visit.selectedId.provider); put("value", visit.selectedId.value) }
            put("stayMinutes", visit.stayMinutes)
        }) } }
    }.toString()

    fun response(body: String, request: ValidationRequest): ValidationResult {
        try {
            val root = Json.parseToJsonElement(body).jsonObject
            val visits = root.array("visits").map { e -> e.jsonObject.let { p ->
                ValidationPlaceResult(p.integer("index").toIntExact(), p.obj("requestedId").id(), p.integer("stayMinutes").nonnegative(),
                    p.optionalObj("confirmedPlace")?.let { c -> ValidationConfirmedPlace(c.obj("id").id(), c.text("name"),
                        c.text("address", blank = true), c.obj("coordinates").point(), c.obj("data").source()) },
                    p.choice("identityStatus", setOf("MATCHED_SELECTED_ID", "UNCONFIRMED")), p.choice("visitStatus", setOf("NOT_VERIFIED")),
                    p.optionalText("searchStatus")?.also { require(it in setOf("FOUND", "NO_RESULTS", "UNAVAILABLE", "FAILED")) },
                    p.optionalInstant("attemptedAt"), p.optionalText("reason")?.also { require(it in placeReasons) }, p.optionalObj("failure")?.failure())
            } }
            val legs = root.array("legs").map { e -> e.jsonObject.let { l -> ValidationLegResult(
                l.integer("index").toIntExact(), l.optionalObj("origin")?.point(), l.optionalObj("destination")?.point(),
                l.optionalLong("durationSeconds")?.nonnegative(), l.choice("status", setOf("FOUND", "NO_ROUTE", "ETA_MISSING", "UNAVAILABLE", "FAILED", "NOT_VALIDATED")),
                l.optionalObj("data")?.source(), l.optionalInstant("attemptedAt"), l.optionalText("reason")?.also { require(it in legReasons) },
                l.optionalObj("failure")?.failure()) } }
            val modes = root.array("dataModes").map { ValidationDataMode.valueOf(it.strictText()) }
            require(modes.distinct().size == modes.size)
            val result = ValidationResult(request, ValidationFeasibility.valueOf(root.text("feasibility")),
                ValidationCoverage.valueOf(root.text("dataCoverage")), modes.toSet(), visits, legs,
                root.optionalLong("totalTravelMinutes")?.nonnegative(), root.optionalLong("totalStayMinutes")?.nonnegative(),
                root.optionalText("predictedReturnAt")?.let(OffsetDateTime::parse), root.optionalLong("remainingMarginMin"),
                root.optionalBoolean("returnDeadlineMet"), root.optionalBoolean("safetyBufferSatisfied"),
                root.getValue("missingEtaLegIndexes").takeUnless { it == JsonNull }?.jsonArray?.map { it.strictLong().toIntExact() },
                root.optionalText("routeDataStatus")?.also { require(it in setOf("UNVERIFIED", "ALL_ETAS_DECLARED_VERIFIED", "INCOMPLETE")) },
                root.array("unknownReasons").map { it.strictText().also { reason -> require(reason in setOf("LEG_COUNT_MISMATCH", "MISSING_REQUIRED_LEG", "MISSING_ETA")) } },
                root.optionalText("calculationFailure")?.also { require(it == "CALCULATION_OVERFLOW") }, root.text("notice"))
            validate(result)
            return result
        } catch (_: Exception) { throw ValidationResponseException() }
    }

    private fun validate(r: ValidationResult) {
        require(r.visits.size == r.request.visits.size && r.legs.size == r.visits.size + 1)
        r.visits.forEachIndexed { index, p ->
            val expected = r.request.visits[index]
            require(p.index == index && p.requestedId == expected.selectedId && p.stayMinutes == expected.stayMinutes)
            if (p.confirmedPlace != null) {
                require(p.confirmedPlace.id == p.requestedId && p.identityStatus == "MATCHED_SELECTED_ID" && p.searchStatus == "FOUND" && p.reason == null && p.failure == null)
                require(p.confirmedPlace.data.provider == "KAKAO_LOCAL")
            } else require(p.identityStatus == "UNCONFIRMED" && p.reason != null)
        }
        val resolved = r.visits.all { it.confirmedPlace != null }
        r.legs.forEachIndexed { index, leg ->
            require(leg.index == index)
            if (resolved) {
                require(leg.origin == if (index == 0) r.request.origin else r.visits[index - 1].confirmedPlace!!.coordinates)
                require(leg.destination == if (index == r.visits.size) r.request.origin else r.visits[index].confirmedPlace!!.coordinates)
            } else require(leg.origin == null && leg.destination == null && leg.reason == "PLACES_UNRESOLVED")
            if (leg.reason == null) require(leg.status == "FOUND" && leg.durationSeconds != null && leg.data != null && leg.failure == null)
            else require(leg.durationSeconds == null && leg.data == null)
            if (leg.data != null) require(leg.data.provider == "KAKAO_MOBILITY")
        }
        val evidence = r.visits.mapNotNull { it.confirmedPlace?.data } + r.legs.mapNotNull { it.data }
        require(r.dataModes.containsAll(evidence.map { it.dataMode }))
        val incomplete = r.visits.any { it.reason != null } || r.legs.any { it.reason != null } || r.calculationFailure != null
        if (incomplete) require(r.dataCoverage == ValidationCoverage.INCOMPLETE && r.feasibility == ValidationFeasibility.UNKNOWN)
        else {
            require(r.dataModes == evidence.map { it.dataMode }.toSet())
            require(r.dataCoverage == if (ValidationDataMode.DEMO_SIMULATED in r.dataModes) ValidationCoverage.CONTAINS_DEMO else ValidationCoverage.ALL_PROVIDER_DATA_DECLARED_VALIDATED)
        }
        if (r.feasibility == ValidationFeasibility.UNKNOWN) {
            require(r.predictedReturnAt == null && r.remainingMarginMin == null && r.returnDeadlineMet == null && r.safetyBufferSatisfied == null && r.totalTravelMinutes == null)
        } else {
            require(!incomplete && r.predictedReturnAt != null && r.remainingMarginMin != null && r.totalTravelMinutes != null && r.totalStayMinutes != null)
            require(r.returnDeadlineMet != null && r.safetyBufferSatisfied != null && r.missingEtaLegIndexes == emptyList<Int>())
            require((r.feasibility == ValidationFeasibility.FEASIBLE) == r.safetyBufferSatisfied)
        }
        if (r.calculationFailure == null) {
            require(r.totalStayMinutes != null && r.routeDataStatus != null && r.missingEtaLegIndexes == r.legs.filter { it.reason != null }.map { it.index })
        } else require(r.missingEtaLegIndexes == null && r.totalStayMinutes == null)
    }

    private val placeReasons = setOf("PLACE_NO_RESULTS", "SELECTED_PLACE_NOT_FOUND", "PLACE_SEARCH_UNAVAILABLE", "PLACE_SEARCH_FAILED", "INVALID_PLACE_RESPONSE", "PLACE_PROVIDER_EXCEPTION")
    private val legReasons = setOf("PLACES_UNRESOLVED", "NO_ROUTE", "ETA_MISSING", "ROUTE_UNAVAILABLE", "ROUTE_FAILED", "INVALID_ROUTE_RESPONSE", "ROUTE_PROVIDER_EXCEPTION", "DURATION_OVERFLOW")
    private fun point(p: ValidationPoint) = buildJsonObject { put("latitude", p.latitude); put("longitude", p.longitude) }
    private fun JsonObject.point() = ValidationPoint(getValue("latitude").strictDouble(), getValue("longitude").strictDouble())
    private fun JsonObject.id() = ValidationPlaceId(text("provider"), text("value"))
    internal fun JsonObject.source(): ValidationSource {
        val mode = ValidationDataMode.valueOf(text("dataMode"))
        val verification = text("verification")
        require(verification == if (mode == ValidationDataMode.DEMO_SIMULATED) "UNVERIFIED" else "VALIDATED_PROVIDER_DATA")
        return ValidationSource(text("provider"), mode, verification, Instant.parse(text("retrievedAt")))
    }
    private fun JsonObject.failure(): ValidationFailure {
        val code = choice("code", setOf("NOT_CONFIGURED", "ADAPTER_NOT_IMPLEMENTED", "AUTHENTICATION_FAILED", "RATE_LIMITED", "TIMEOUT", "CONNECTION_FAILED", "HTTP_ERROR", "INVALID_DATA", "MISSING_ETA"))
        val http = optionalLong("httpStatus")?.toIntExact()?.also { require(it in 400..599) }
        return ValidationFailure(code, http)
    }
    private fun JsonObject.obj(key: String) = getValue(key).jsonObject
    private fun JsonObject.optionalObj(key: String) = getValue(key).takeUnless { it == JsonNull }?.jsonObject
    private fun JsonObject.array(key: String) = getValue(key).jsonArray
    private fun JsonObject.text(key: String, blank: Boolean = false) = getValue(key).strictText(blank)
    private fun JsonObject.optionalText(key: String) = getValue(key).takeUnless { it == JsonNull }?.strictText()
    private fun JsonObject.optionalInstant(key: String) = optionalText(key)?.let(Instant::parse)
    private fun JsonObject.choice(key: String, allowed: Set<String>) = text(key).also { require(it in allowed) }
    private fun JsonObject.integer(key: String) = getValue(key).strictLong()
    private fun JsonObject.optionalLong(key: String) = getValue(key).takeUnless { it == JsonNull }?.strictLong()
    private fun JsonObject.optionalBoolean(key: String): Boolean? = getValue(key).takeUnless { it == JsonNull }?.jsonPrimitive?.let {
        require(!it.isString); requireNotNull(it.booleanOrNull)
    }
    private fun JsonElement.strictText(blank: Boolean = false) = jsonPrimitive.let {
        require(it.isString && (blank || it.content.isNotBlank()) && it.content.length <= 8192); it.content
    }
    private fun JsonElement.strictLong() = jsonPrimitive.let {
        require(!it.isString && Regex("-?(?:0|[1-9][0-9]*)").matches(it.content)); it.content.toLong()
    }
    private fun JsonElement.strictDouble() = jsonPrimitive.let { require(!it.isString); it.double.also { n -> require(n.isFinite()) } }
    private fun Long.nonnegative() = also { require(it >= 0) }
    private fun Long.toIntExact(): Int { require(this in 0..Int.MAX_VALUE.toLong()); return toInt() }
}
