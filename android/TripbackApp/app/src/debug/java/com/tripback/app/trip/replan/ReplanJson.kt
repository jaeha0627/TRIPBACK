package com.tripback.app.trip.replan

import com.tripback.app.trip.*
import kotlinx.serialization.json.*
import java.time.*

/** Debug-only wire boundary. Existing validation and recommendation parsers remain unchanged. */
internal object ReplanJson {
    private val reasons = setOf("CACHED_ETA_ASSUMPTION", "MISSING_ETA", "UNTRUSTED_ETA", "STALE_ETA",
        "FUTURE_ETA_TIMESTAMP", "DEADLINE_PASSED", "DEADLINE_EXCEEDED", "SAFETY_BUFFER_SHORTFALL",
        "TOTAL_STAY_BELOW_ELAPSED", "CALCULATION_OUT_OF_RANGE")
    private val notices = setOf("NO_PLAN_COMMIT_OR_APPROVAL", "NO_PROVIDER_CALLS", "CACHED_ETAS_NOT_CURRENT_TRAFFIC",
        "MATHEMATICAL_RESULT_NOT_RETURN_GUARANTEE", "PROGRESS_NOT_VEHICLE_VERIFIED",
        "PRIVATE_LOOPBACK_DEVELOPMENT_ONLY_NOT_AUTHENTICATED")

    fun registration(body: String, headers: Map<String, List<String>>, selection: ReplanSelection,
        server: String, now: Instant): ReplanRegistration = guarded {
        strict(body) // Reject duplicate keys before using the established general validation parser.
        val validation = TripValidationJson.response(body, selection.validationRequest)
        fun header(name: String): String? {
            val values = headers.entries.filter { it.key.equals(name, true) }.flatMap { it.value }
            require(values.size <= 1)
            return values.singleOrNull()?.also { require(it == it.trim() && it.length <= 100) }
        }
        val status = PlanRegistrationStatus.valueOf(requireNotNull(header("X-Tripback-Plan-Registration")))
        val token = header("X-Tripback-Plan-Ref")
        val revision = header("X-Tripback-Plan-Revision")
        val expiry = header("X-Tripback-Plan-Expires-At")
        if (status != PlanRegistrationStatus.REGISTERED) {
            require(token == null && revision == null && expiry == null)
            return@guarded ReplanRegistration(status, validation, null)
        }
        require(token != null && Regex("[A-Za-z0-9_-]{43}").matches(token))
        require(revision != null && Regex("[1-9][0-9]*").matches(revision))
        val version = revision.toLong()
        require(version == 1L) // Current server snapshots are immutable revision 1; do not invent revision upgrades.
        val expiresAt = Instant.parse(requireNotNull(expiry))
        require(expiresAt > now && expiresAt <= now.plusSeconds(1800))
        verifySnapshot(selection, validation)
        ReplanRegistration(status, validation, RegisteredReplanPlan(selection, validation,
            PlanReference(token, version, expiresAt), server))
    }

    private fun verifySnapshot(selection: ReplanSelection, v: ValidationResult) {
        val original = selection.plan.validation
        require(original.dataCoverage == ValidationCoverage.ALL_PROVIDER_DATA_DECLARED_VALIDATED &&
            ValidationDataMode.DEMO_SIMULATED !in original.dataModes)
        require(v.feasibility == ValidationFeasibility.FEASIBLE && v.returnDeadlineMet == true && v.safetyBufferSatisfied == true)
        require(v.dataCoverage == ValidationCoverage.ALL_PROVIDER_DATA_DECLARED_VALIDATED &&
            ValidationDataMode.DEMO_SIMULATED !in v.dataModes && v.calculationFailure == null)
        require(original.visits.size == v.visits.size && v.request == selection.validationRequest)
        require(original.visits.map { it.requestedId }.containsAll(selection.request.mustVisitPlaceIds))
        v.visits.zip(original.visits).forEach { (current, previous) ->
            require(current.requestedId == previous.requestedId && current.stayMinutes == previous.stayMinutes)
            require(current.confirmedPlace != null && previous.confirmedPlace != null)
            // Exact coordinates: a changed place must be reviewed, never silently accepted as a new baseline.
            require(current.confirmedPlace.coordinates == previous.confirmedPlace.coordinates)
            require(current.confirmedPlace.data.verification == "VALIDATED_PROVIDER_DATA")
        }
        v.legs.forEach { require(it.data?.verification == "VALIDATED_PROVIDER_DATA" && it.durationSeconds != null) }
        for (leg in listOf(v.legs.first(), v.legs.last()))
            require(ceilMinutes(requireNotNull(leg.durationSeconds)) <= selection.request.maximumOneWayMinutes)
        val travel = v.legs.fold(0L) { sum, leg -> Math.addExact(sum, ceilMinutes(requireNotNull(leg.durationSeconds))) }
        val stay = v.request.visits.fold(0L) { sum, visit -> Math.addExact(sum, visit.stayMinutes) }
        require(v.totalTravelMinutes == travel && v.totalStayMinutes == stay)
        val predicted = v.request.departAt.toInstant().plusSeconds(Math.multiplyExact(Math.addExact(travel, stay), 60))
        require(v.predictedReturnAt?.toInstant() == predicted)
        val margin = Duration.between(predicted, v.request.returnDeadline.toInstant())
        require(v.remainingMarginMin == Math.floorDiv(margin.seconds, 60) && margin >= Duration.ofMinutes(v.request.safetyBufferMin.toLong()))
    }

    fun request(plan: RegisteredReplanPlan, observation: ReplanObservation, now: Instant): String {
        require(observation.currentVisitIndex in plan.validation.visits.indices)
        return buildJsonObject {
            put("planRef", plan.reference.token(now)); put("revision", plan.reference.revision)
            put("currentVisitIndex", observation.currentVisitIndex); put("phase", observation.phase)
            put("observedAt", observation.observedAt.toString()); put("elapsedStaySeconds", observation.elapsedStaySeconds)
            put("requestedTotalStayMinutes", observation.requestedTotalStayMinutes)
        }.toString()
    }

    fun preview(body: String, plan: RegisteredReplanPlan, observation: ReplanObservation, now: Instant): ReplanPreview = guarded {
        plan.reference.token(now)
        val root = strict(body).jsonObject
        root.fields("status", "baseRevision", "planExpiresAt", "calculatedAt", "progressVerification", "currentVisitIndex",
            "safetyBufferMinutes", "returnDeadline", "originalHome", "orderedVisits", "requiredVisits", "before", "after", "notices")
        require(root.text("status") == "PREVIEW_ONLY" && root.text("progressVerification") == "CLIENT_REPORTED_NOT_VERIFIED")
        require(root.long("baseRevision") == plan.reference.revision && Instant.parse(root.text("planExpiresAt")) == plan.reference.expiresAt)
        val at = Instant.parse(root.text("calculatedAt"))
        val lag = Duration.between(observation.observedAt.toInstant(), at)
        require(!lag.isNegative && lag <= Duration.ofSeconds(60) && at <= now && at < plan.reference.expiresAt)
        val index = root.long("currentVisitIndex").toIntChecked()
        require(index == observation.currentVisitIndex)
        val input = plan.validation.request
        require(root.long("safetyBufferMinutes") == input.safetyBufferMin.toLong())
        val deadline = OffsetDateTime.parse(root.text("returnDeadline"))
        require(deadline.toInstant() == input.returnDeadline.toInstant())
        val home = root.getValue("originalHome").jsonObject.point()
        require(home == input.origin)
        val order = root.array("orderedVisits").map { it.jsonObject.id() }
        val required = root.array("requiredVisits").map { it.jsonObject.id() }
        require(order == input.visits.map { it.selectedId } && required.distinct().size == required.size && required.toSet() == order.toSet())
        val providedNotices = root.array("notices").map { it.text() }
        require(providedNotices.size == notices.size && providedNotices.toSet() == notices)
        val elapsed = Math.addExact(observation.elapsedStaySeconds, lag.seconds)
        fun projection(key: String, totalMinutes: Long): ReplanProjection {
            val p = root.getValue(key).jsonObject
            p.fields("feasibility", "arithmeticFeasibility", "predictedReturnAt", "remainingMarginSeconds", "remainingMarginMinutes",
                "returnDeadlineMet", "safetyBufferSatisfied", "currentRemainingStayMinutes", "remainingLegs", "reasons")
            val reportedReasons = p.array("reasons").map { it.text() }
            require(reportedReasons.all { it in reasons } && reportedReasons.distinct().size == reportedReasons.size)
            val eta = p.array("remainingLegs").map { e -> e.jsonObject.let { l ->
                l.fields("originalLegIndex", "minutes", "source", "dataMode", "verification", "retrievedAt", "cached")
                ReplanEta(l.long("originalLegIndex").toIntChecked(), l.nullLong("minutes"), l.nullText("source"),
                    l.nullText("dataMode")?.let(ValidationDataMode::valueOf), l.nullText("verification"),
                    l.nullText("retrievedAt")?.let(Instant::parse), l.bool("cached"))
            } }
            require(eta.map { it.originalLegIndex } == (index + 1..input.visits.size).toList())
            var trusted = true
            eta.forEach { leg ->
                val original = plan.validation.legs[leg.originalLegIndex]
                if (leg.minutes == null) {
                    trusted = false
                    require("MISSING_ETA" in reportedReasons)
                } else require(leg.minutes == ceilMinutes(requireNotNull(original.durationSeconds)))
                if (leg.source != null || leg.retrievedAt != null) {
                    require(leg.source == original.data?.provider && leg.retrievedAt == original.data?.retrievedAt)
                }
                if (leg.verification != "VERIFIED" || leg.dataMode != ValidationDataMode.VERIFIED_CACHED || !leg.cached) {
                    trusted = false; require("UNTRUSTED_ETA" in reportedReasons || "MISSING_ETA" in reportedReasons)
                } else {
                    val age = Duration.between(requireNotNull(leg.retrievedAt), at)
                    if (age.isNegative) { trusted = false; require("FUTURE_ETA_TIMESTAMP" in reportedReasons) }
                    if (age > Duration.ofMinutes(15)) { trusted = false; require("STALE_ETA" in reportedReasons) }
                }
                require(leg.dataMode != ValidationDataMode.VERIFIED_LIVE) // Preview never obtains fresh traffic.
            }
            val remaining = ceilMinutes((Math.multiplyExact(totalMinutes, 60) - elapsed).coerceAtLeast(0))
            require(p.nullLong("currentRemainingStayMinutes") == remaining)
            val predicted = p.nullText("predictedReturnAt")?.let(OffsetDateTime::parse)
            val marginSeconds = p.nullLong("remainingMarginSeconds")
            val marginMinutes = p.nullLong("remainingMarginMinutes")
            val deadlineMet = p.nullBool("returnDeadlineMet")
            val bufferMet = p.nullBool("safetyBufferSatisfied")
            val arithmetic = ValidationFeasibility.valueOf(p.text("arithmeticFeasibility"))
            val feasibility = ValidationFeasibility.valueOf(p.text("feasibility"))
            if (eta.any { it.minutes == null }) {
                require(predicted == null && marginSeconds == null && marginMinutes == null && deadlineMet == null && bufferMet == null)
                require(arithmetic == ValidationFeasibility.UNKNOWN && feasibility == ValidationFeasibility.UNKNOWN)
            } else {
                val futureStays = input.visits.drop(index + 1).fold(remaining) { sum, visit -> Math.addExact(sum, visit.stayMinutes) }
                val total = eta.fold(futureStays) { sum, leg -> Math.addExact(sum, requireNotNull(leg.minutes)) }
                require(predicted?.toInstant() == at.plusSeconds(Math.multiplyExact(total, 60)))
                val margin = Duration.between(requireNotNull(predicted).toInstant(), deadline.toInstant())
                require(marginSeconds == margin.seconds && marginMinutes == Math.floorDiv(margin.seconds, 60))
                require(deadlineMet == !margin.isNegative && bufferMet == (margin >= Duration.ofMinutes(input.safetyBufferMin.toLong())))
                val expected = if (bufferMet == true) ValidationFeasibility.FEASIBLE else ValidationFeasibility.INFEASIBLE
                require(arithmetic == expected && feasibility == if (trusted) expected else ValidationFeasibility.UNKNOWN)
            }
            return ReplanProjection(feasibility, arithmetic, predicted, marginSeconds, marginMinutes, deadlineMet, bufferMet,
                remaining, eta, reportedReasons.toSet())
        }
        ReplanPreview("PREVIEW_ONLY", plan.reference.revision, plan.reference.expiresAt, at, "CLIENT_REPORTED_NOT_VERIFIED",
            index, input.safetyBufferMin.toLong(), deadline, home, order, required.toSet(),
            projection("before", input.visits[index].stayMinutes), projection("after", observation.requestedTotalStayMinutes),
            providedNotices, observation.localRevision)
    }

    fun error(body: String, status: Int): ReplanError {
        val code = try { strict(body).jsonObject["code"]?.text() } catch (_: Exception) { null }
        val allowed = when (status) {
            400 -> setOf("INVALID_REQUEST", "INVALID_CURRENT_VISIT", "INVALID_OBSERVATION_TIME", "INVALID_STAY_OBSERVATION",
                "TOTAL_STAY_BELOW_ELAPSED", "CALCULATION_OUT_OF_RANGE")
            403 -> setOf("LOCAL_DEVELOPMENT_ONLY")
            404 -> setOf("PLAN_NOT_FOUND_OR_EXPIRED")
            409 -> setOf("PLAN_REVISION_MISMATCH")
            413 -> setOf("REQUEST_TOO_LARGE")
            415 -> setOf("UNSUPPORTED_CHARSET")
            500 -> setOf("INTERNAL_ERROR")
            else -> emptySet()
        }
        return if (code in allowed) ReplanError.valueOf(requireNotNull(code))
            else if (status == 404) ReplanError.API_NOT_AVAILABLE else ReplanError.HTTP_ERROR
    }

    /** Bound depth and reject duplicate (including escaped) keys before kotlinx JSON discards them. */
    fun strict(body: String): JsonElement {
        require(body.toByteArray(Charsets.UTF_8).size <= 1_048_576)
        val stack = mutableListOf<MutableSet<String>?>()
        var i = 0
        while (i < body.length) {
            when (body[i]) {
                '{' -> { stack.add(mutableSetOf()); require(stack.size <= 32) }
                '[' -> { stack.add(null); require(stack.size <= 32) }
                '}', ']' -> { require(stack.isNotEmpty()); stack.removeAt(stack.lastIndex) }
                '"' -> {
                    val start = i++
                    while (i < body.length && body[i] != '"') { if (body[i] == '\\') i++; i++ }
                    require(i < body.length)
                    var after = i + 1
                    while (after < body.length && body[after].isWhitespace()) after++
                    if (after < body.length && body[after] == ':') {
                        val key = Json.parseToJsonElement(body.substring(start, i + 1)).jsonPrimitive.content
                        require(stack.lastOrNull()?.add(key) == true)
                    }
                }
            }
            i++
        }
        require(stack.isEmpty())
        return Json.parseToJsonElement(body)
    }
    private inline fun <T> guarded(block: () -> T): T = try { block() }
        catch (e: ReplanException) { throw e }
        catch (_: Exception) { throw ReplanException(ReplanError.INVALID_RESPONSE) }
    internal fun ceilMinutes(seconds: Long): Long { require(seconds >= 0); return seconds / 60 + if (seconds % 60 == 0L) 0 else 1 }
    private fun JsonObject.fields(vararg names: String) { require(keys == names.toSet()) }
    private fun JsonElement.text(): String = jsonPrimitive.let { require(it.isString && it.content.isNotBlank()); it.content }
    private fun JsonObject.text(key: String) = getValue(key).text()
    private fun JsonObject.nullText(key: String) = getValue(key).takeUnless { it == JsonNull }?.text()
    private fun JsonElement.number(): Long = jsonPrimitive.let {
        require(!it.isString && Regex("-?(?:0|[1-9][0-9]*)").matches(it.content)); it.content.toLong()
    }
    private fun JsonObject.long(key: String) = getValue(key).number()
    private fun JsonObject.nullLong(key: String) = getValue(key).takeUnless { it == JsonNull }?.number()
    private fun JsonElement.boolean(): Boolean = jsonPrimitive.let { require(!it.isString); requireNotNull(it.booleanOrNull) }
    private fun JsonObject.bool(key: String) = getValue(key).boolean()
    private fun JsonObject.nullBool(key: String) = getValue(key).takeUnless { it == JsonNull }?.boolean()
    private fun JsonObject.array(key: String) = getValue(key).jsonArray
    private fun JsonObject.point(): ValidationPoint {
        fields("latitude", "longitude")
        fun value(key: String) = getValue(key).jsonPrimitive.let { require(!it.isString); it.double }
        return ValidationPoint(value("latitude"), value("longitude"))
    }
    private fun JsonObject.id(): ValidationPlaceId { fields("provider", "value"); return ValidationPlaceId(text("provider"), text("value")) }
    private fun Long.toIntChecked(): Int { require(this in 0..Int.MAX_VALUE.toLong()); return toInt() }
}
