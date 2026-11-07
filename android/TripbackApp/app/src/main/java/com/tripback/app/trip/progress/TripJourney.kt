package com.tripback.app.trip.progress

import com.tripback.app.trip.*
import com.tripback.app.trip.recommendation.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.Duration

interface JourneyClock { fun wall(): Long; fun elapsed(): Long }
enum class JourneyPhase { READY, DEPARTURE, TRAVELING, ARRIVED, STAYING, RETURN_PREPARATION, RETURNING, COMPLETED }
data class JourneyEvent(val index: Int, val kind: String, val at: Long, val staySeconds: Long = 0,
    val source: String = "DEMO_SIMULATED")
data class JourneyChange(val at: Long, val description: String, val approved: Boolean, val before: String, val after: String)
data class JourneyProgress(val phase: JourneyPhase = JourneyPhase.READY, val index: Int = 0,
    val stayStartWall: Long? = null, val stayStartElapsed: Long? = null,
    val stays: List<Long> = emptyList(), val omitted: Set<Int> = emptySet(),
    val events: List<JourneyEvent> = emptyList(), val changes: List<JourneyChange> = emptyList(), val revision: Int = 0)
data class ReturnProjection(val feasibility: ValidationFeasibility, val arithmetic: ValidationFeasibility,
    val returnAt: Instant?, val marginSeconds: Long?, val reasons: List<String>)
data class JourneyProposal(val revision: Int, val stays: List<Long>, val omitted: Set<Int>,
    val description: String, val before: ReturnProjection, val after: ReturnProjection)

/** Explicit manual adapter; no NaviHelper requests or live vehicle events can be emitted. */
class DemoNavigationAdapter : NavigationAdapter {
    override val capability = MutableStateFlow(NavigationCapability(true,true,"DEMO_SIMULATED · 수동 여행 시연"))
    override fun request(destination: NavigationDestination, event: (NavigationState)->Unit) {
        event(NavigationState(NavigationStatus.REQUESTED,"DEMO_SIMULATED · 실제 내비 요청 없음"))
    }
    fun advance(phase: JourneyPhase): JourneyPhase? = when(phase) {
        JourneyPhase.DEPARTURE -> JourneyPhase.TRAVELING
        JourneyPhase.TRAVELING -> JourneyPhase.ARRIVED
        JourneyPhase.RETURN_PREPARATION -> JourneyPhase.RETURNING
        JourneyPhase.RETURNING -> JourneyPhase.COMPLETED
        else -> null
    }
}

/** Pure event-driven reducer. No background timer, network request, automatic replan or route start. */
class TripJourney(val plan: RecommendationAlternative, val request: RecommendationRequest,
    private val clock: JourneyClock, restored: String? = null) {
    private val demo = DemoNavigationAdapter()
    private val mutable = MutableStateFlow(JourneyProgress(stays=plan.orderedVisits.map { Math.multiplyExact(it.stayMinutes,60L) }))
    val state = mutable.asStateFlow()
    init {
        require(plan.feasibility == ValidationFeasibility.FEASIBLE && plan.validation.feasibility == ValidationFeasibility.FEASIBLE)
        require(plan.orderedVisits.isNotEmpty() && plan.validation.legs.size == plan.orderedVisits.size+1)
        if(restored != null) restore(restored)
    }
    val currentName get() = plan.validation.visits.getOrNull(state.value.index)?.confirmedPlace?.name ?: "방문지 미확인"
    val requiredIndexes get() = plan.orderedVisits.indices.filter { plan.orderedVisits[it].id in request.mustVisitPlaceIds }.toSet()
    private fun commit(s: JourneyProgress) { mutable.value=s.copy(revision=state.value.revision+1) }
    fun depart(approved: Boolean) {
        if(!approved || state.value.phase != JourneyPhase.READY) return
        commit(state.value.copy(phase=JourneyPhase.DEPARTURE))
    }
    fun manualAdvance(expected: JourneyPhase, expectedRevision: Int) {
        val s=state.value
        if(s.phase != expected || s.revision != expectedRevision) return // rejects late or duplicate events with prior phase
        val next=demo.advance(expected) ?: return
        val kind=when(next) { JourneyPhase.ARRIVED -> "ARRIVAL";JourneyPhase.COMPLETED -> "HOME_ARRIVAL";else -> "MANUAL_PHASE" }
        commit(s.copy(phase=next,events=s.events+JourneyEvent(s.index,kind,clock.wall())))
    }
    fun startStay() {
        val s=state.value
        if(s.phase != JourneyPhase.ARRIVED) return
        commit(s.copy(phase=JourneyPhase.STAYING,stayStartWall=clock.wall(),stayStartElapsed=clock.elapsed()))
    }
    private fun elapsedSeconds(s: JourneyProgress): Long? {
        val wall=s.stayStartWall ?: return 0
        val elapsed=s.stayStartElapsed ?: return null
        val d=clock.elapsed()-elapsed;val w=clock.wall()-wall
        // Clock jumps/reboot cannot silently produce a fresh, validated schedule.
        if(d < 0 || w < 0 || kotlin.math.abs(d-w)>5000) return null
        return d/1000
    }
    fun remainingStay(): Long? = if(state.value.phase != JourneyPhase.STAYING) null else
        elapsedSeconds(state.value)?.let { state.value.stays[state.value.index]-it }
    fun finishStay(approved: Boolean) {
        val s=state.value
        if(!approved || s.phase != JourneyPhase.STAYING) return
        val elapsed=elapsedSeconds(s) ?: return
        val next=(s.index+1 until plan.orderedVisits.size).firstOrNull { it !in s.omitted }
        commit(s.copy(phase=if(next == null) JourneyPhase.RETURN_PREPARATION else JourneyPhase.DEPARTURE,
            index=next ?: s.index,stayStartWall=null,stayStartElapsed=null,
            events=s.events+JourneyEvent(s.index,"STAY_FINISHED",clock.wall(),elapsed)))
    }
    fun projection(stays: List<Long> = state.value.stays, omitted: Set<Int> = state.value.omitted): ReturnProjection {
        val s=state.value
        val reasons=mutableListOf("DEMO_SIMULATED · 수동 진행, 실제 이동 상태 미확인")
        if(s.phase == JourneyPhase.COMPLETED) return ReturnProjection(ValidationFeasibility.UNKNOWN,
            ValidationFeasibility.UNKNOWN,null,null,listOf("시연 종료 · 실제 귀가시각 미확인"))
        if(omitted.isNotEmpty()) return ReturnProjection(ValidationFeasibility.UNKNOWN,ValidationFeasibility.UNKNOWN,
            null,null,reasons+"방문지 제외로 이동 구간 변경 · 서버 재검증 필요")
        val remaining=if(s.phase == JourneyPhase.STAYING) elapsedSeconds(s) else 0L
        if(remaining == null) return ReturnProjection(ValidationFeasibility.UNKNOWN,ValidationFeasibility.UNKNOWN,
            null,null,reasons+"타이머 시계 변경/재부팅 감지")
        val returning=s.phase in setOf(JourneyPhase.RETURN_PREPARATION,JourneyPhase.RETURNING)
        val indexes=if(returning) listOf(plan.orderedVisits.size) else {
            val first=if(s.phase in setOf(JourneyPhase.ARRIVED,JourneyPhase.STAYING)) s.index+1 else s.index
            (first..plan.orderedVisits.size).toList()
        }
        var seconds=0L
        try {
            for(i in indexes) {
                val leg=plan.validation.legs.getOrNull(i)
                val duration=leg?.durationSeconds
                if(duration == null || duration<0 || leg.status != "FOUND") return ReturnProjection(
                    ValidationFeasibility.UNKNOWN,ValidationFeasibility.UNKNOWN,null,null,reasons+"남은 이동 ETA 누락")
                // Backend RouteLegMapper rounds EACH leg up to a minute; keep the same conservative rule.
                seconds=Math.addExact(seconds,Math.multiplyExact(Math.floorDiv(Math.addExact(duration,59),60),60))
                val data=leg.data
                if(data == null || data.dataMode == ValidationDataMode.DEMO_SIMULATED || data.verification != "VALIDATED_PROVIDER_DATA") reasons += "미검증/시연 ETA"
                else {
                    val age=Duration.between(data.retrievedAt,Instant.ofEpochMilli(clock.wall())).seconds
                    if(age !in 0..900) reasons += "ETA 15분 초과 또는 조회시각 오류 · 최신 교통 미확인"
                }
            }
            if(!returning) for(i in s.index until stays.size) if(i !in omitted) {
                val stay=if(i == s.index && s.phase == JourneyPhase.STAYING) (stays[i]-remaining).coerceAtLeast(0) else stays[i]
                seconds=Math.addExact(seconds,stay)
            }
            val at=Instant.ofEpochMilli(clock.wall()).plusSeconds(seconds)
            val margin=Duration.between(at,request.returnDeadline.toInstant()).seconds
            val arithmetic=if(margin>=request.safetyBufferMin*60L) ValidationFeasibility.FEASIBLE else ValidationFeasibility.INFEASIBLE
            // No GPS/live route progress: even recent provider ETAs cannot establish live feasibility.
            return ReturnProjection(ValidationFeasibility.UNKNOWN,arithmetic,at,margin,reasons.distinct())
        } catch (_: Exception) { return ReturnProjection(ValidationFeasibility.UNKNOWN,ValidationFeasibility.UNKNOWN,null,null,reasons+"계산 범위 초과") }
    }
    fun shortenProposal(): JourneyProposal? {
        val s=state.value
        if(s.phase != JourneyPhase.STAYING) return null
        val changed=s.stays.toMutableList();changed[s.index]=(changed[s.index]-600).coerceAtLeast(0)
        return JourneyProposal(s.revision,changed,s.omitted,"현재 체류 계획 10분 단축",projection(),projection(changed))
    }
    fun omitProposal(): JourneyProposal? {
        val s=state.value
        if(s.phase != JourneyPhase.STAYING) return null
        val candidate=(s.index+1 until s.stays.size).firstOrNull { it !in requiredIndexes && it !in s.omitted } ?: return null
        val omitted=s.omitted+candidate
        return JourneyProposal(s.revision,s.stays,omitted,"선택 방문지 ${candidate+1} 제외 · 서버 재검증 필요",projection(),projection(omitted=omitted))
    }
    fun decide(proposal: JourneyProposal, approved: Boolean) {
        val s=state.value
        if(s.phase != JourneyPhase.STAYING || s.revision != proposal.revision) return
        // Re-evaluate at approval time, and defend against forged/stale proposal payloads.
        require(proposal.stays.size == s.stays.size && proposal.stays.all { it in 0..86400 })
        require(proposal.omitted.containsAll(s.omitted) && (proposal.omitted-s.omitted).all { it in s.index+1 until s.stays.size && it !in requiredIndexes })
        require(proposal.stays.indices.all { i -> i == s.index || proposal.stays[i] == s.stays[i] })
        require(proposal.stays[s.index] <= s.stays[s.index])
        val after=projection(proposal.stays,proposal.omitted)
        commit(s.copy(stays=if(approved) proposal.stays else s.stays,omitted=if(approved) proposal.omitted else s.omitted,
            changes=(s.changes+JourneyChange(clock.wall(),proposal.description,approved,
                projection().returnAt?.toString() ?: "UNKNOWN",after.returnAt?.toString() ?: "UNKNOWN")).takeLast(100)))
    }
    fun snapshot(): String = JourneyCodec.encode(state.value)
    private fun restore(raw: String) {
        val s=JourneyCodec.decode(raw)
        require(s.index in plan.orderedVisits.indices && s.stays.size == plan.orderedVisits.size && s.stays.all { it in 0..86400 })
        require(s.omitted.none { it in requiredIndexes } && s.omitted.all { it in plan.orderedVisits.indices })
        require((s.phase == JourneyPhase.STAYING) == (s.stayStartWall != null && s.stayStartElapsed != null))
        mutable.value=s
    }
}

object JourneyCodec {
    fun encode(s: JourneyProgress): String = buildJsonObject {
        put("phase",s.phase.name);put("index",s.index);put("revision",s.revision)
        put("wall",s.stayStartWall?.let(::JsonPrimitive) ?: JsonNull);put("elapsed",s.stayStartElapsed?.let(::JsonPrimitive) ?: JsonNull)
        put("stays",JsonArray(s.stays.map(::JsonPrimitive)));put("omitted",JsonArray(s.omitted.sorted().map(::JsonPrimitive)))
        put("events",JsonArray(s.events.map { e -> buildJsonObject {put("i",e.index);put("kind",e.kind);put("at",e.at);put("stay",e.staySeconds);put("source",e.source)} }))
        put("changes",JsonArray(s.changes.map { c -> buildJsonObject {put("at",c.at);put("text",c.description);put("approved",c.approved);put("before",c.before);put("after",c.after)} }))
    }.toString()
    fun decode(raw: String): JourneyProgress {
        require(raw.length < 100000)
        val o=Json.parseToJsonElement(raw).jsonObject
        val events=o.getValue("events").jsonArray.map { it.jsonObject.let { e ->
            require(e.getValue("source").jsonPrimitive.content == "DEMO_SIMULATED")
            JourneyEvent(e.getValue("i").jsonPrimitive.int,e.getValue("kind").jsonPrimitive.content,
                e.getValue("at").jsonPrimitive.long,e.getValue("stay").jsonPrimitive.long) } }
        val changes=o.getValue("changes").jsonArray.map { it.jsonObject.let { c -> JourneyChange(
            c.getValue("at").jsonPrimitive.long,c.getValue("text").jsonPrimitive.content,c.getValue("approved").jsonPrimitive.boolean,
            c.getValue("before").jsonPrimitive.content,c.getValue("after").jsonPrimitive.content) } }
        return JourneyProgress(JourneyPhase.valueOf(o.getValue("phase").jsonPrimitive.content),o.getValue("index").jsonPrimitive.int,
            o["wall"]?.jsonPrimitive?.longOrNull,o["elapsed"]?.jsonPrimitive?.longOrNull,
            o.getValue("stays").jsonArray.map {it.jsonPrimitive.long},o.getValue("omitted").jsonArray.map {it.jsonPrimitive.int}.toSet(),
            events,changes,o.getValue("revision").jsonPrimitive.int)
    }
}
