package com.tripback.app.trip.recommendation

import com.tripback.app.trip.progress.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class RecommendationPage { INPUT, LOADING, RESULTS, DETAIL, READY, JOURNEY }
data class RecommendationUiState(
    val page: RecommendationPage = RecommendationPage.INPUT, val response: RecommendationResponse? = null,
    val request: RecommendationRequest? = null, val selectedPlanId: String? = null,
    val error: String? = null, val raw: String? = null
) {
    val selected get()=response?.alternatives?.find { it.planId==selectedPlanId }
}
/** Owns a request generation; only explicit submit can start work. Restoration never replays a request. */
class RecommendationSession(private val scope: CoroutineScope, private val repository: RecommendationRepository, navigationAdapter: NavigationAdapter = UnsupportedNavigationAdapter, private val journeyClock: JourneyClock = AndroidJourneyClock) {
    val journey = MutableStateFlow<TripJourney?>(null)
    fun beginDemo(clock: JourneyClock = journeyClock) {
        val s=mutable.value
        if(s.page != RecommendationPage.READY || s.selected == null || s.request == null) return
        journey.value=TripJourney(s.selected!!,s.request,clock)
        mutable.value=s.copy(page=RecommendationPage.JOURNEY)
    }
    fun finishDemo() {
        if(journey.value?.state?.value?.phase != JourneyPhase.COMPLETED) return
        mutable.value=mutable.value.copy(page=RecommendationPage.READY)
    }
    val navigation = NavigationDeparture(navigationAdapter)
    fun approveDeparture(approved: Boolean, uxUnrestricted: Boolean) {
        if(mutable.value.page == RecommendationPage.READY) navigation.approve(mutable.value.selected, approved, uxUnrestricted)
    }
    private val mutable=MutableStateFlow(RecommendationUiState())
    val state=mutable.asStateFlow()
    private var generation=0L
    private var job:Job?=null
    fun submit(request:RecommendationRequest,scenario:FakeScenario) {
        if(mutable.value.page==RecommendationPage.LOADING) return
        val token=++generation
        mutable.value=mutable.value.copy(page=RecommendationPage.LOADING,error=null)
        job=scope.launch {
            try {
                val raw=repository.recommend(request,scenario)
                val parsed=RecommendationJson.response(raw,request)
                if(token==generation) mutable.value=RecommendationUiState(RecommendationPage.RESULTS,parsed,request,raw=raw)
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(failure:Exception) {
                if(token==generation) mutable.value=mutable.value.copy(page=RecommendationPage.INPUT,
                    error=(when(failure) {
                        is RecommendationHttpException -> when(failure.status) {
                            404 -> "추천 API가 비활성화됐거나 주소가 없습니다 (HTTP 404)."
                            400 -> "서버가 입력을 거절했습니다 (HTTP 400)."
                            403 -> "서버가 연결을 거절했습니다. adb reverse와 loopback 설정을 확인해 주세요 (HTTP 403)."
                            else -> "추천 서버 HTTP 오류 (${failure.status})."
                        }
                        is RecommendationTimeoutException -> "추천 응답 시간이 초과됐습니다. 서버 처리가 계속될 수 있습니다."
                        is RecommendationConnectionException -> "추천 서버에 연결하지 못했습니다. 서버와 adb reverse를 확인해 주세요."
                        else -> "추천 응답을 읽지 못했습니다."
                    }) + " 이전 결과는 보존됩니다. 자동으로 재요청하지 않습니다.")
            }
        }
    }
    fun cancel() {
        ++generation;job?.cancel();job=null
        if(mutable.value.page==RecommendationPage.LOADING) mutable.value=mutable.value.copy(page=RecommendationPage.INPUT,error="요청을 취소했습니다. 늦은 응답은 반영하지 않습니다. 서버 처리는 계속될 수 있습니다.")
    }
    fun input() { cancel();mutable.value=mutable.value.copy(page=RecommendationPage.INPUT,error=null) }
    fun results() { if(mutable.value.response!=null) mutable.value=mutable.value.copy(page=RecommendationPage.RESULTS,error=null) }
    fun detail(id:String) { if(mutable.value.response?.alternatives?.any {it.planId==id}==true) mutable.value=mutable.value.copy(page=RecommendationPage.DETAIL,selectedPlanId=id) }
    fun select() { if(mutable.value.selected?.feasibility==com.tripback.app.trip.ValidationFeasibility.FEASIBLE && mutable.value.selected?.validation?.feasibility==com.tripback.app.trip.ValidationFeasibility.FEASIBLE) mutable.value=mutable.value.copy(page=RecommendationPage.READY) }
    fun back() { when(mutable.value.page) {
        RecommendationPage.LOADING -> cancel()
        RecommendationPage.DETAIL -> results()
        RecommendationPage.JOURNEY -> Unit // leave an active journey only through explicit completion
        RecommendationPage.READY -> mutable.value=mutable.value.copy(page=RecommendationPage.DETAIL)
        else -> input()
    } }
    fun snapshot():List<String> = mutable.value.let { s -> listOf(
        s.page.name,s.request?.let(RecommendationJson::request).orEmpty(),s.raw.orEmpty(),s.selectedPlanId.orEmpty(),journey.value?.snapshot().orEmpty()) }
    fun restore(saved:List<String>) {
        if(saved.size !in 4..5) return
        try {
            val page=RecommendationPage.valueOf(saved[0])
            if(saved[1].isEmpty()||saved[2].isEmpty()) {
                mutable.value=RecommendationUiState(error=if(page==RecommendationPage.LOADING) "중단된 요청입니다. 다시 시작하려면 직접 요청해 주세요." else null)
                return
            }
            val request=RecommendationJson.readRequest(saved[1]);val response=RecommendationJson.response(saved[2],request)
            val selected=saved[3].takeIf { id -> response.alternatives.any {it.planId==id} }
            val restoredPage=if(page==RecommendationPage.LOADING) RecommendationPage.INPUT else if(page in setOf(RecommendationPage.DETAIL,RecommendationPage.READY,RecommendationPage.JOURNEY)&&selected==null) RecommendationPage.RESULTS else page
            if(restoredPage == RecommendationPage.JOURNEY) {
                require(saved.size == 5 && saved[4].isNotBlank())
                journey.value=TripJourney(response.alternatives.first {it.planId == selected},request,journeyClock,saved[4])
            }
            mutable.value=RecommendationUiState(restoredPage,response,request,selected,
                if(page==RecommendationPage.LOADING)"중단된 요청을 자동 재실행하지 않았습니다." else null,saved[2])
        } catch(_:Exception) { mutable.value=RecommendationUiState(error="저장된 화면을 복원하지 못했습니다. 조건을 다시 확인해 주세요.") }
    }
}
