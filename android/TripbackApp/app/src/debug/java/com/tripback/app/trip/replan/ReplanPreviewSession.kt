package com.tripback.app.trip.replan

import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class ReplanRequestPhase { IDLE, LOADING, PREVIEW_READY, EXPIRED, ERROR }
data class ReplanRequestState(val phase: ReplanRequestPhase = ReplanRequestPhase.IDLE,
    val registrationStatus: PlanRegistrationStatus? = null, val expiresAt: Instant? = null,
    val preview: ReplanPreview? = null, val error: ReplanError? = null,
    val diagnostic: ReplanDiagnostic? = null, val registrationUnconfirmed: Boolean = false)

/** UI-scope owner. No Journey reducer, approval, persistence or automatic registration.
 * Call cancel on phase/local-revision/candidate changes or screen stop (keeps a usable baseline).
 * Call invalidateContext on selection/baseline changes, and close on disposal. No request is replayed.
 */
class ReplanPreviewSession(private val scope: CoroutineScope, private val repository: DebugReplanRepository,
    private val clock: Clock = Clock.systemUTC()) {
    private val mutable = MutableStateFlow(ReplanRequestState())
    val state = mutable.asStateFlow()
    private var plan: RegisteredReplanPlan? = null
    private var generation = 0L
    private var job: Job? = null
    private var expiryJob: Job? = null

    fun register(selection: ReplanSelection, explicitlyRequested: Boolean): Boolean {
        if (mutable.value.phase == ReplanRequestPhase.LOADING) return false
        if (!explicitlyRequested) {
            mutable.value = mutable.value.copy(phase = ReplanRequestPhase.ERROR, preview = null, error = ReplanError.EXPLICIT_REQUEST_REQUIRED)
            return false
        }
        plan?.reference?.discard(); plan = null; expiryJob?.cancel()
        launch {
            val registration = repository.register(selection, true)
            registration.plan?.let { registered ->
                if (!registered.reference.isUsable(clock.instant())) throw ReplanException(ReplanError.PLAN_NOT_FOUND_OR_EXPIRED)
            }
            registration
        }
        return true
    }

    fun preview(observation: ReplanObservation): Boolean {
        if (mutable.value.phase == ReplanRequestPhase.LOADING) return false
        checkExpiry()
        val current = plan ?: return false
        val token = ++generation
        mutable.value = mutable.value.copy(phase = ReplanRequestPhase.LOADING, preview = null, error = null, diagnostic = null)
        job = scope.launch {
            try {
                val result = repository.preview(current, observation)
                if (token == generation) {
                    if (!current.reference.isUsable(clock.instant())) expire()
                    else mutable.value = mutable.value.copy(phase = ReplanRequestPhase.PREVIEW_READY, preview = result)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { if (token == generation) failure(e) }
        }
        return true
    }

    private fun launch(work: suspend () -> ReplanRegistration) {
        val token = ++generation
        mutable.value = ReplanRequestState(ReplanRequestPhase.LOADING)
        job = scope.launch {
            var received: RegisteredReplanPlan? = null
            try {
                val result = work(); received = result.plan
                if (token != generation) { received?.reference?.discard(); return@launch }
                plan = result.plan
                mutable.value = ReplanRequestState(registrationStatus = result.status, expiresAt = result.plan?.reference?.localExpiresAt)
                if (plan == null) mutable.value = mutable.value.copy(phase = ReplanRequestPhase.ERROR, error = ReplanError.REGISTRATION_UNAVAILABLE)
                else scheduleExpiry(requireNotNull(plan))
            } catch (cancelled: CancellationException) { received?.reference?.discard(); throw cancelled }
            catch (e: Exception) { if (token == generation) failure(e) }
        }
    }
    private fun scheduleExpiry(registered: RegisteredReplanPlan) {
        expiryJob?.cancel()
        expiryJob = scope.launch {
            while (plan === registered && registered.reference.isUsable(clock.instant())) {
                delay(Duration.between(clock.instant(), registered.reference.localExpiresAt).toMillis().coerceAtLeast(1))
            }
            if (plan === registered) expire()
        }
    }
    fun checkExpiry() {
        if (plan?.reference?.isUsable(clock.instant()) == false) expire()
    }
    private fun expire() {
        ++generation; job?.cancel(); plan?.reference?.discard(); plan = null
        mutable.value = ReplanRequestState(ReplanRequestPhase.EXPIRED, error = ReplanError.PLAN_NOT_FOUND_OR_EXPIRED)
    }
    private fun failure(e: Exception) {
        val code = (e as? ReplanException)?.error ?: ReplanError.NETWORK
        when (code) {
            ReplanError.PLAN_NOT_FOUND_OR_EXPIRED -> expire()
            ReplanError.PLAN_REVISION_MISMATCH -> {
                plan?.reference?.discard(); plan = null; expiryJob?.cancel()
                mutable.value = ReplanRequestState(ReplanRequestPhase.ERROR, error = code)
            }
            else -> mutable.value = mutable.value.copy(phase = ReplanRequestPhase.ERROR, preview = null, error = code,
                diagnostic = (e as? ReplanException)?.diagnostic,
                registrationUnconfirmed = (e as? ReplanException)?.registrationUnconfirmed == true)
        }
    }
    fun cancel() {
        ++generation; job?.cancel(); job = null
        mutable.value = mutable.value.copy(phase = ReplanRequestPhase.IDLE, preview = null, error = null, diagnostic = null)
        checkExpiry()
    }
    fun invalidateContext() {
        cancel(); expiryJob?.cancel(); plan?.reference?.discard(); plan = null
        mutable.value = ReplanRequestState()
    }
    fun close() { invalidateContext() }
}
