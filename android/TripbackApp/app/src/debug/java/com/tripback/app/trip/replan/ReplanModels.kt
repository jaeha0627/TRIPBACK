package com.tripback.app.trip.replan

import com.tripback.app.trip.*
import com.tripback.app.trip.recommendation.*
import java.io.IOException
import java.time.*

enum class ReplanError {
    INVALID_REQUEST, INVALID_CURRENT_VISIT, INVALID_OBSERVATION_TIME, INVALID_STAY_OBSERVATION,
    TOTAL_STAY_BELOW_ELAPSED, CALCULATION_OUT_OF_RANGE, LOCAL_DEVELOPMENT_ONLY,
    PLAN_NOT_FOUND_OR_EXPIRED, PLAN_REVISION_MISMATCH, REQUEST_TOO_LARGE, UNSUPPORTED_CHARSET,
    INTERNAL_ERROR, API_NOT_AVAILABLE, HTTP_ERROR, INVALID_RESPONSE, NETWORK, TIMEOUT,
    EXPLICIT_REQUEST_REQUIRED, SELECTION_MISMATCH, REGISTRATION_UNAVAILABLE
}
enum class ReplanDiagnostic { BODY_SCHEMA, HEADER_CONTRACT, EXPIRY_WINDOW, SNAPSHOT_CONTRACT, COORDINATES_CHANGED, ONE_WAY_LIMIT, CALCULATION_MISMATCH, HTTP_RESPONSE }

class ReplanException(val error: ReplanError, val httpStatus: Int? = null,
    val diagnostic: ReplanDiagnostic? = null, val registrationUnconfirmed: Boolean = false) :
    IOException("Replan failure: ${error.name}${httpStatus?.let { " (HTTP $it)" }.orEmpty()}")

/** A separate immutable-by-convention selection snapshot; never a TripJourney or an approved change. */
class ReplanSelection private constructor(val request: RecommendationRequest, val plan: RecommendationAlternative) {
    val validationRequest get() = ValidationRequest(request.origin, request.departAt, request.returnDeadline,
        request.safetyBufferMin, plan.orderedVisits.map { ValidationVisit(it.originalKeyword, it.id, it.stayMinutes) })
    companion object {
        /** Pass the exact request originally submitted with this response (the server does not echo preferences).
         * Reparse to detach caller-owned lists. Preference preservation is a binding, not provider verification.
         */
        fun fromRecommendation(raw: String, request: RecommendationRequest, selectedPlanId: String): ReplanSelection {
            try {
                val snapshot = RecommendationJson.readRequest(RecommendationJson.request(request))
                val plan = RecommendationJson.response(raw, snapshot).alternatives.single { it.planId == selectedPlanId }
                require(plan.validation.dataCoverage == ValidationCoverage.ALL_PROVIDER_DATA_DECLARED_VALIDATED &&
                    ValidationDataMode.DEMO_SIMULATED !in plan.dataModes)
                return ReplanSelection(snapshot, plan)
            } catch (_: Exception) { throw ReplanException(ReplanError.SELECTION_MISMATCH) }
        }
    }
    override fun toString() = "ReplanSelection(snapshot)"
}

/** Memory only. No Serializable, public token accessor, data-class copy, log or persistence hook. */
class PlanReference internal constructor(private var value: String?, val revision: Long, val expiresAt: Instant) {
    @Synchronized internal fun token(now: Instant): String {
        if (!now.isBefore(expiresAt)) value = null
        return value ?: throw ReplanException(ReplanError.PLAN_NOT_FOUND_OR_EXPIRED)
    }
    @Synchronized fun discard() { value = null }
    fun isUsable(now: Instant): Boolean = try { token(now); true } catch (_: ReplanException) { false }
    override fun toString() = "PlanReference([REDACTED])"
}
class RegisteredReplanPlan internal constructor(val selection: ReplanSelection, val validation: ValidationResult,
    val reference: PlanReference, internal val server: String) {
    override fun toString() = "RegisteredReplanPlan([REDACTED])"
}
enum class PlanRegistrationStatus { REGISTERED, NOT_ELIGIBLE, CAPACITY_EXCEEDED, DISABLED }
data class ReplanRegistration(val status: PlanRegistrationStatus, val validation: ValidationResult,
    val plan: RegisteredReplanPlan?)

data class ReplanObservation(val currentVisitIndex: Int, val observedAt: OffsetDateTime,
    val elapsedStaySeconds: Long, val requestedTotalStayMinutes: Long,
    val localRevision: Int, val phase: String = "STAYING") {
    init {
        require(currentVisitIndex in 0..4 && phase == "STAYING" && localRevision >= 0)
        require(elapsedStaySeconds in 0..604800 && requestedTotalStayMinutes in 0..1440)
        require(requestedTotalStayMinutes * 60 >= elapsedStaySeconds)
    }
}
data class ReplanEta(val originalLegIndex: Int, val minutes: Long?, val source: String?,
    val dataMode: ValidationDataMode?, val verification: String?, val retrievedAt: Instant?, val cached: Boolean)
data class ReplanProjection(val feasibility: ValidationFeasibility, val arithmeticFeasibility: ValidationFeasibility,
    val predictedReturnAt: OffsetDateTime?, val remainingMarginSeconds: Long?, val remainingMarginMinutes: Long?,
    val returnDeadlineMet: Boolean?, val safetyBufferSatisfied: Boolean?, val currentRemainingStayMinutes: Long?,
    val remainingLegs: List<ReplanEta>, val reasons: Set<String>)
data class ReplanPreview(val status: String, val baseRevision: Long, val planExpiresAt: Instant,
    val calculatedAt: Instant, val progressVerification: String, val currentVisitIndex: Int,
    val safetyBufferMinutes: Long, val returnDeadline: OffsetDateTime, val originalHome: ValidationPoint,
    val orderedVisits: List<ValidationPlaceId>, val requiredVisits: Set<ValidationPlaceId>,
    val before: ReplanProjection, val after: ReplanProjection, val notices: List<String>, val localRevision: Int)

interface DebugReplanRepository {
    suspend fun register(selection: ReplanSelection, explicitlyRequested: Boolean): ReplanRegistration
    suspend fun preview(plan: RegisteredReplanPlan, observation: ReplanObservation): ReplanPreview
}
