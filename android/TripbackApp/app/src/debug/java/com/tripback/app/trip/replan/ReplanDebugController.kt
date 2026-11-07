package com.tripback.app.trip.replan

import com.tripback.app.trip.progress.*
import com.tripback.app.trip.recommendation.RecommendationRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*

data class ReplanDebugView(val title: String, val currentName: String, val phase: JourneyPhase,
    val originalStayMinutes: Long, val elapsedSeconds: Long?, val requestedTotalMinutes: Long,
    val deadline: OffsetDateTime, val bufferMinutes: Int, val blockedReason: String?,
    val requestState: ReplanRequestState, val canRegister: Boolean, val canPreview: Boolean,
    val visits: List<Pair<String, Long>>, val currentIndex: Int, val etaNotice: String? = null)

/** Read-only adapter around the existing journey. Never calls decide/finishStay/depart or writes history. */
class ReplanDebugController(scope: CoroutineScope, private val journey: TripJourney,
    request: RecommendationRequest?, raw: String?, selectedPlanId: String?,
    private val repository: DebugReplanRepository, private val clock: Clock = Clock.systemUTC()) {
    private val session = ReplanPreviewSession(scope, repository, clock)
    val state = session.state
    private val mutableTotal = MutableStateFlow(journey.state.value.stays[journey.state.value.index] / 60)
    val totalStayMinutes = mutableTotal.asStateFlow()
    private val selection = runCatching {
        require(request != null && raw != null && selectedPlanId != null)
        require(request == journey.request && selectedPlanId == journey.plan.planId)
        ReplanSelection.fromRecommendation(raw, request, selectedPlanId).also { require(it.plan == journey.plan) }
    }.getOrNull()
    private var lastProgress = journey.state.value
    private var etaNotice: String? = null
    private val observer = scope.launch {
        journey.state.collect { progress ->
            if (progress != lastProgress) {
                if (progress.index != lastProgress.index) mutableTotal.value = progress.stays[progress.index] / 60
                if (baselineMatches()) session.cancel() else session.invalidateContext()
                lastProgress = progress
            }
        }
    }
    private fun baselineMatches(): Boolean {
        val s = journey.state.value
        return s.omitted.isEmpty() && s.changes.none { it.approved } &&
            s.stays == journey.plan.orderedVisits.map { it.stayMinutes * 60 }
    }
    fun view(): ReplanDebugView {
        session.checkExpiry()
        val previous = state.value.preview
        if (previous != null && previous.after.feasibility != com.tripback.app.trip.ValidationFeasibility.UNKNOWN &&
            previous.after.remainingLegs.any { eta -> eta.retrievedAt?.let {
                Duration.between(it, clock.instant()).let { age -> age.isNegative || age > Duration.ofMinutes(15) }
            } ?: true }) {
            session.cancel()
            etaNotice = "ETA 조회 후 15분이 지나 이전 Preview를 무효화했습니다. 자동 조회하지 않습니다."
        }
        val s = journey.state.value
        val elapsed = if (s.phase == JourneyPhase.STAYING) journey.remainingStay()?.let { s.stays[s.index] - it } else null
        val blocked = when {
            !baselineMatches() -> "로컬 일정 변경·방문지 제외로 기준이 다릅니다. Preview를 사용할 수 없습니다."
            selection == null -> "원래 추천 요청·검증 snapshot을 확인할 수 없습니다. DEMO 코스는 서버 검증 계획으로 등록하지 않습니다."
            s.phase != JourneyPhase.STAYING -> "현재 방문지에서 체류 중일 때만 Preview를 요청할 수 있습니다."
            elapsed == null -> "체류 시계 변경·재부팅 감지로 경과시간을 확인할 수 없습니다."
            clock.instant().isBefore(journey.request.departAt.toInstant()) -> "원래 출발시각 이전입니다. 실제 여행 관측으로 취급하지 않습니다."
            Duration.between(journey.request.departAt.toInstant(), clock.instant()).seconds < elapsed -> "출발시각과 체류 경과시간이 맞지 않습니다."
            mutableTotal.value * 60 < elapsed -> "변경 총 체류시간이 이미 경과한 시간보다 짧습니다."
            else -> null
        }
        var requestState = state.value
        if (blocked != null || requestState.preview?.localRevision?.let { it != s.revision } == true)
            requestState = requestState.copy(preview = null, phase = if (requestState.phase == ReplanRequestPhase.PREVIEW_READY) ReplanRequestPhase.IDLE else requestState.phase)
        val loading = requestState.phase == ReplanRequestPhase.LOADING
        return ReplanDebugView(journey.plan.title, journey.currentName, s.phase, s.stays[s.index] / 60, elapsed,
            mutableTotal.value, journey.request.returnDeadline, journey.request.safetyBufferMin, blocked,
            requestState, blocked == null && !loading,
            blocked == null && !loading && requestState.registrationStatus == PlanRegistrationStatus.REGISTERED &&
                requestState.expiresAt?.isAfter(clock.instant()) == true,
            journey.plan.orderedVisits.mapIndexed { i, v ->
                (journey.plan.validation.visits[i].confirmedPlace?.name ?: "방문지 미확인") to v.stayMinutes
            }, s.index, etaNotice)
    }
    fun changeTotal(minutes: Long) {
        if (minutes !in 0..1440 || minutes == mutableTotal.value) return
        session.cancel(); mutableTotal.value = minutes
    }
    fun register(explicitlyConfirmed: Boolean): Boolean {
        if (!explicitlyConfirmed || !view().canRegister) return false
        return session.register(requireNotNull(selection), true)
    }
    fun preview(): Boolean {
        val v = view()
        if (!v.canPreview) return false
        etaNotice = null
        val s = journey.state.value
        return session.preview(ReplanObservation(s.index, clock.instant().atOffset(journey.request.departAt.offset),
            requireNotNull(v.elapsedSeconds), v.requestedTotalMinutes, s.revision))
    }
    fun scenario(value: ReplanUiScenario) {
        session.cancel()
        (repository as? FakeReplanUiRepository)?.scenario = value
    }
    fun dismiss() { session.cancel() }
    fun stop() { session.cancel() }
    fun close() { observer.cancel(); session.close() }
}
