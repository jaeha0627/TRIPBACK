package com.tripback.app.trip.replan

import com.tripback.app.trip.*
import kotlinx.coroutines.delay
import java.time.*
import java.util.UUID

enum class ReplanUiScenario(val label: String) {
    NORMAL("정상 가정"), STALE("ETA 15분 초과"), SERVER_ERROR("서버 오류"),
    REVISION_CONFLICT("revision 충돌"), EXPIRED("참조 만료"), DELAYED("지연 응답")
}

/** In-process UI simulator only. No HTTP client, provider, server registration or validated data creation.
 * The selection must already pass ReplanSelection's production boundary. DEMO inputs stay blocked.
 * The issued fake:// origin prevents this simulated reference being sent by DebugReplanHttp.
 */
class FakeReplanUiRepository(private val clock: Clock = Clock.systemUTC(), private val latencyMs: Long = 350) : DebugReplanRepository {
    var scenario = ReplanUiScenario.NORMAL
    var registrationCalls = 0; private set
    var previewCalls = 0; private set
    override suspend fun register(selection: ReplanSelection, explicitlyRequested: Boolean): ReplanRegistration {
        if (!explicitlyRequested) throw ReplanException(ReplanError.EXPLICIT_REQUEST_REQUIRED)
        registrationCalls++
        delay(latencyMs)
        val token = ("FAKE" + UUID.randomUUID().toString().replace("-", "") + "0000000").take(43)
        val plan = RegisteredReplanPlan(selection, selection.plan.validation,
            PlanReference(token, 1, clock.instant().plusSeconds(1800)), "fake://replan-ui")
        return ReplanRegistration(PlanRegistrationStatus.REGISTERED, plan.validation, plan)
    }
    override suspend fun preview(plan: RegisteredReplanPlan, observation: ReplanObservation): ReplanPreview {
        require(plan.server == "fake://replan-ui")
        plan.reference.token(clock.instant())
        previewCalls++
        val selectedScenario = scenario
        delay(if (selectedScenario == ReplanUiScenario.DELAYED) 4000 else latencyMs)
        when (selectedScenario) {
            ReplanUiScenario.SERVER_ERROR -> throw ReplanException(ReplanError.INTERNAL_ERROR, 500)
            ReplanUiScenario.REVISION_CONFLICT -> throw ReplanException(ReplanError.PLAN_REVISION_MISMATCH, 409)
            ReplanUiScenario.EXPIRED -> throw ReplanException(ReplanError.PLAN_NOT_FOUND_OR_EXPIRED, 404)
            else -> Unit
        }
        val at = clock.instant(); plan.reference.token(at)
        val input = plan.validation.request
        val index = observation.currentVisitIndex
        require(index in input.visits.indices)
        val lag = Duration.between(observation.observedAt.toInstant(), at).seconds
        if (lag !in 0..60) throw ReplanException(ReplanError.INVALID_OBSERVATION_TIME)
        val elapsed = Math.addExact(observation.elapsedStaySeconds, lag)
        if (observation.requestedTotalStayMinutes * 60 < elapsed) throw ReplanException(ReplanError.TOTAL_STAY_BELOW_ELAPSED)
        val forceStale = selectedScenario == ReplanUiScenario.STALE
        val eta = plan.validation.legs.drop(index + 1).map { leg ->
            // All displayed UI response evidence stays explicitly simulated, never VERIFIED_LIVE.
            val timestamp = if (forceStale) at.minusSeconds(901) else leg.data?.retrievedAt
            ReplanEta(leg.index, leg.durationSeconds?.let(ReplanJson::ceilMinutes),
                "FAKE_UI:${leg.data?.provider ?: "UNKNOWN"}", ValidationDataMode.DEMO_SIMULATED,
                "UNVERIFIED", timestamp, false)
        }
        fun projection(total: Long): ReplanProjection {
            val remaining = ReplanJson.ceilMinutes((total * 60 - elapsed).coerceAtLeast(0))
            val reasons = mutableSetOf("FAKE_REPOSITORY_NO_HTTP", "SIMULATED_ETA_NOT_VALIDATED")
            if (eta.any { it.minutes == null }) return ReplanProjection(ValidationFeasibility.UNKNOWN,
                ValidationFeasibility.UNKNOWN, null, null, null, null, null, remaining, eta, reasons + "MISSING_ETA")
            val future = input.visits.drop(index + 1).sumOf { it.stayMinutes }
            val returned = at.plusSeconds((remaining + future + eta.sumOf { it.minutes!! }) * 60).atOffset(observation.observedAt.offset)
            val margin = Duration.between(returned.toInstant(), input.returnDeadline.toInstant())
            val deadlineMet = !margin.isNegative
            val bufferMet = margin >= Duration.ofMinutes(input.safetyBufferMin.toLong())
            val arithmetic = if (bufferMet) ValidationFeasibility.FEASIBLE else ValidationFeasibility.INFEASIBLE
            val stale = eta.any { it.retrievedAt == null || Duration.between(it.retrievedAt, at).let { age -> age.isNegative || age > Duration.ofMinutes(15) } }
            if (stale) reasons += "STALE_ETA"
            if (!deadlineMet) reasons += "DEADLINE_EXCEEDED" else if (!bufferMet) reasons += "SAFETY_BUFFER_SHORTFALL"
            return ReplanProjection(if (stale) ValidationFeasibility.UNKNOWN else arithmetic, arithmetic, returned,
                margin.seconds, Math.floorDiv(margin.seconds, 60), deadlineMet, bufferMet, remaining, eta, reasons)
        }
        return ReplanPreview("PREVIEW_ONLY", plan.reference.revision, plan.reference.expiresAt, at,
            "CLIENT_REPORTED_NOT_VERIFIED", index, input.safetyBufferMin.toLong(), input.returnDeadline,
            input.origin, input.visits.map { it.selectedId }, input.visits.map { it.selectedId }.toSet(),
            projection(input.visits[index].stayMinutes), projection(observation.requestedTotalStayMinutes),
            listOf("FAKE_REPOSITORY_NO_HTTP", "SIMULATED_RESULT_NOT_SERVER_VALIDATION"), observation.localRevision)
    }
}
