package com.tripback.app.trip.progress

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object AndroidJourneyClock : JourneyClock {
    override fun wall() = System.currentTimeMillis()
    override fun elapsed() = SystemClock.elapsedRealtime()
}
/** Device-local demo records only. No GPS/actual trip fields are invented. Up to 20 records. */
class DemoTripHistory(context: Context) {
    private val prefs=context.applicationContext.getSharedPreferences("tripback-demo-history-v1",Context.MODE_PRIVATE)
    fun records(): List<JsonObject> = try { Json.parseToJsonElement(prefs.getString("records","[]")!!).jsonArray.map {it.jsonObject} } catch (_: Exception) { emptyList() }
    fun save(journey: TripJourney): Boolean {
        val s=journey.state.value
        if(s.phase != JourneyPhase.COMPLETED) return false
        val id=journey.plan.planId+":"+s.events.firstOrNull()?.at
        val record=buildJsonObject {
            put("id",id);put("title",journey.plan.title);put("source","DEMO_SIMULATED")
            put("actualDrivingVerified",false);put("actualHomeArrivalVerified",false)
            put("originalPredictedReturnAt",journey.plan.validation.predictedReturnAt?.toString() ?: "UNKNOWN")
            put("returnDeadline",journey.request.returnDeadline.toString())
            put("plannedStops",JsonArray(journey.plan.orderedVisits.mapIndexed {i,v -> buildJsonObject {
                put("name",journey.plan.validation.visits[i].confirmedPlace?.name ?: "미확인");put("sourceId",v.id.value)
                put("required",v.id in journey.request.mustVisitPlaceIds);put("originalStayMinutes",v.stayMinutes)
            } }))
            put("demoProgress",Json.parseToJsonElement(journey.snapshot()))
        }
        return prefs.edit().putString("records",JsonArray((listOf(record)+records().filter {it["id"] != record["id"]}).take(20)).toString()).commit()
    }
}
private val JourneyNavy=Color(0xFF002C5F)
private val JourneyTeal=Color(0xFF007FA8)
private fun time(value: Instant?): String = value?.atZone(ZoneId.of("Asia/Seoul"))?.format(DateTimeFormatter.ofPattern("MM/dd HH:mm:ss")) ?: "UNKNOWN"
@Composable private fun JourneyAction(label: String, tag: String, enabled: Boolean = true, action: ()->Unit) {
    Button(onClick=action,enabled=enabled,shape=androidx.compose.ui.graphics.RectangleShape,colors=ButtonDefaults.buttonColors(containerColor=JourneyNavy),modifier=Modifier.heightIn(min=48.dp).testTag(tag)) { Text(label) }
}
@Composable fun JourneyScreen(journey: TripJourney, onBack: ()->Unit, debugContent: @Composable ()->Unit = {}) {
    val s by journey.state.collectAsState()
    var tick by remember { mutableLongStateOf(0) }
    // One disposable UI ticker; the model stores anchors, never a running countdown job.
    LaunchedEffect(journey,s.phase) { while(s.phase == JourneyPhase.STAYING) { tick++;delay(1000) } }
    @Suppress("UNUSED_VARIABLE") val refresh=tick
    var proposal by remember(journey,s.revision) { mutableStateOf<JourneyProposal?>(null) }
    val context=LocalContext.current
    val history=remember(context) { DemoTripHistory(context) }
    var saved by remember { mutableStateOf<String?>(null) }
    val projection=journey.projection()
    if(s.phase == JourneyPhase.STAYING) {
        com.tripback.app.trip.recommendation.HomeStyle {
            StayingScreen(journey,s,projection,{proposal=it},debugContent)
        }
    } else {
    Column(Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState()).padding(24.dp).testTag("journeyScreen"),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("⇄ Tripback · 가는 길 → 머무는 시간 → 오는 길",color=JourneyNavy,fontSize=20.sp)
        Text("DEMO_SIMULATED · 수동 여행 시연 · 실제 GPS/주행/내비 이벤트 없음",color=JourneyTeal)
        Text(when(s.phase) {
            JourneyPhase.READY -> "여행 시연 준비"
            JourneyPhase.DEPARTURE -> "출발 준비 · 수동 시연"
            JourneyPhase.TRAVELING -> "이동 시연 중"
            JourneyPhase.ARRIVED -> "방문지 도착 시연"
            JourneyPhase.STAYING -> "머무는 시간"
            JourneyPhase.RETURN_PREPARATION -> "귀가 준비"
            JourneyPhase.RETURNING -> "귀가 이동 시연 중"
            JourneyPhase.COMPLETED -> "여행 시연을 기록했어요"
        },fontSize=30.sp,color=JourneyNavy,modifier=Modifier.testTag("journeyPhase"))
        Text(journey.plan.title+" · "+journey.currentName)
        Text("실제 추천 장소 내비 시작은 POI 규약 검증 전까지 비활성입니다.")
        if(s.phase != JourneyPhase.COMPLETED) {
            Text("귀가 관리: ${projection.feasibility} · 실제 이동 상태 미확인",modifier=Modifier.testTag("returnStatus"))
            Text("기존 ETA 가정 계산: ${projection.arithmetic} · 최신 교통 검증 아님")
            Text("참고 귀가 예상 ${time(projection.returnAt)} · 안전 여유 ${journey.request.safetyBufferMin}분")
            Text("마감까지 여유 ${projection.marginSeconds?.let { Math.floorDiv(it,60).toString()+"분" } ?: "UNKNOWN"}")
            if(projection.arithmetic == com.tripback.app.trip.ValidationFeasibility.INFEASIBLE)
                Text("귀가 마감 또는 안전 여유가 부족합니다. 변경안을 검토해 주세요.",color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("returnWarning"))
            Text(projection.reasons.joinToString("\n"),fontSize=13.sp)
        }
        when(s.phase) {
            JourneyPhase.READY -> JourneyAction("승인하고 여행 시연 준비","demoDepart") { journey.depart(true) }
            JourneyPhase.DEPARTURE -> JourneyAction("수동 시연 · 이동 시작","demoTravel") {journey.manualAdvance(s.phase,s.revision)}
            JourneyPhase.TRAVELING -> JourneyAction("수동 시연 · 방문지 도착","demoArrive") {journey.manualAdvance(s.phase,s.revision)}
            JourneyPhase.ARRIVED -> JourneyAction("체류 시작","startStay") {journey.startStay()}
            JourneyPhase.STAYING -> {
                val remaining=journey.remainingStay()
                Text("계획 체류 ${s.stays[s.index]/60}분",fontSize=20.sp)
                Text(remaining?.let { if(it>=0) "남은 체류 ${it/60}분 ${it%60}초" else "체류시간 초과 ${-it/60}분 ${-it%60}초" }
                    ?: "체류 시계 변경 감지 · 확인 필요",fontSize=32.sp,color=JourneyNavy,modifier=Modifier.testTag("stayTimer"))
                if(remaining != null && remaining<0) Text("예정 시간을 넘겼습니다. 자동 이동·일정 변경은 하지 않습니다.",color=MaterialTheme.colorScheme.error)
                JourneyAction("체류 10분 단축 제안","shortenProposal") {proposal=journey.shortenProposal()}
                JourneyAction("선택 방문지 제외 제안","omitProposal",journey.omitProposal()!=null) {proposal=journey.omitProposal()}
                JourneyAction("체류 종료 승인 · 다음 준비","finishStay",remaining!=null) {journey.finishStay(true)}
            }
            JourneyPhase.RETURN_PREPARATION -> JourneyAction("승인하고 귀가 이동 시연","demoReturn") {journey.manualAdvance(s.phase,s.revision)}
            JourneyPhase.RETURNING -> JourneyAction("수동 시연 · 귀가 완료","demoComplete") {journey.manualAdvance(s.phase,s.revision)}
            JourneyPhase.COMPLETED -> {
                Text("실제 귀가·주행 기록 아님 · 모든 도착은 수동 시연입니다.")
                Text("원래 계획 귀가 예상: ${journey.plan.validation.predictedReturnAt ?: "UNKNOWN"} · 당시 ETA 기준")
                Text("귀가 마감: ${journey.request.returnDeadline}")
                s.events.filter {it.kind in setOf("ARRIVAL","HOME_ARRIVAL","STAY_FINISHED")}.forEach { e ->
                    Text("DEMO_SIMULATED · ${e.index+1}번 ${e.kind} · ${time(Instant.ofEpochMilli(e.at))} · 체류 ${e.staySeconds}초")
                }
                s.changes.forEach {c -> Text("변경 ${if(c.approved) "승인" else "거절"}: ${c.description} · ${c.before} → ${c.after} · 서버 재검증 안 됨")}
                JourneyAction("시연 기록을 이 기기에 저장","saveDemoHistory") {saved=if(history.save(journey)) "DEMO_SIMULATED 기록 저장됨" else "저장 실패 · 재시도 가능"}
                saved?.let {Text(it,modifier=Modifier.testTag("historySaved"))}
                Text("기기 내 시연 기록: ${history.records().size}건")
                history.records().forEach {record -> Text("DEMO_SIMULATED · "+record["title"]?.jsonPrimitive?.content)}
                JourneyAction("추천 일정으로 돌아가기","journeyBack",action=onBack)
            }
        }
        debugContent()
        HorizontalDivider()
        Text("확정 일정 · 필수 방문지는 동의 없이 제외하지 않습니다.",color=JourneyTeal)
        journey.plan.orderedVisits.forEachIndexed {i,v ->
            Text("${i+1}. ${journey.plan.validation.visits[i].confirmedPlace?.name ?: "미확인"} · 원래 ${v.stayMinutes}분 / 현재 ${s.stays[i]/60}분"+
                (if(i in journey.requiredIndexes) " · 필수" else " · 선택")+(if(i in s.omitted) " · 시연 제외 승인됨 / 경로 미검증" else ""))
        }
    }
    }
    proposal?.let { p -> AlertDialog(onDismissRequest={proposal=null},title={Text("일정 변경안 검토")},text={Column {
        Text(p.description)
        Text("변경 전: ${time(p.before.returnAt)} · 가정 계산 ${p.before.arithmetic}")
        Text("변경 후: ${time(p.after.returnAt)} · 가정 계산 ${p.after.arithmetic}")
        Text("실제 귀가 판정 UNKNOWN · 서버 재검증 안 됨. 시연 일정만 바뀝니다.")
    }},confirmButton={TextButton(onClick={journey.decide(p,true);proposal=null},modifier=Modifier.testTag("approveChange")){Text("시연 변경 승인")}},
        dismissButton={TextButton(onClick={journey.decide(p,false);proposal=null},modifier=Modifier.testTag("rejectChange")){Text("거절 · 원래 일정 유지")}}) }
}

private val StayLine=Color(0xFFD9DEE3)
private val StaySoft=Color(0xFFF3F5F7)
private fun stayDuration(seconds:Long):String = "%02d:%02d:%02d".format(seconds/3600,seconds%3600/60,seconds%60)

@Composable private fun StayPanel(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    Column(modifier.fillMaxWidth().border(1.dp,StayLine).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun StayingScreen(journey:TripJourney,s:JourneyProgress,projection:ReturnProjection,
    onProposal:(JourneyProposal?)->Unit,debugContent:@Composable ()->Unit) {
    val remaining=journey.remainingStay()
    val elapsed=remaining?.let { s.stays[s.index]-it }
    Box(Modifier.fillMaxSize().background(Color.White),contentAlignment=Alignment.TopCenter) {
    Column(Modifier.widthIn(max=1080.dp).fillMaxSize().testTag("journeyScreen")) {
        Row(Modifier.fillMaxWidth().heightIn(min=57.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("⇄",Modifier.background(JourneyNavy).padding(5.dp),color=Color.White,fontSize=14.sp)
            Text("Tripback",Modifier.padding(start=8.dp),color=JourneyNavy,fontSize=15.sp,fontWeight=FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("수동 체류 시연",fontSize=12.sp,color=JourneyTeal)
        }
        HorizontalDivider(color=StayLine)
        Row(Modifier.fillMaxWidth()) {
            listOf("가는 길","머무는 시간","오는 길").forEachIndexed { index,label ->
                Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(label,Modifier.padding(vertical=10.dp),fontSize=13.sp,lineHeight=18.sp,color=if(index==1) JourneyNavy else Color.Gray)
                    Box(Modifier.fillMaxWidth().height(3.dp).background(if(index==1) JourneyTeal else Color.Transparent))
                }
            }
        }
        HorizontalDivider(color=StayLine)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide=maxWidth>=840.dp
            val information:@Composable ColumnScope.()->Unit = {
                Text("DEMO_SIMULATED · 수동 여행 시연 · 실제 GPS/도착/차량/내비 이벤트 없음",fontSize=12.sp,lineHeight=18.sp,color=JourneyTeal)
                Text(journey.currentName,fontSize=34.sp,lineHeight=44.sp,color=JourneyNavy)
                Text("머무는 시간",fontSize=14.sp,lineHeight=20.sp,color=JourneyTeal,modifier=Modifier.testTag("journeyPhase"))
                if(projection.arithmetic==com.tripback.app.trip.ValidationFeasibility.INFEASIBLE)
                    Text("귀가 마감 또는 안전 여유가 부족합니다. 변경안을 검토해 주세요.",fontSize=14.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("returnWarning"))
                StayPanel {
                    Text(if(remaining!=null && remaining<0) "계획 체류시간 초과" else "남은 체류시간",fontSize=13.sp,color=Color.DarkGray,modifier=Modifier.align(Alignment.CenterHorizontally))
                    Text(remaining?.let { stayDuration(kotlin.math.abs(it)) } ?: "UNKNOWN",fontSize=72.sp,lineHeight=86.sp,color=JourneyNavy,modifier=Modifier.align(Alignment.CenterHorizontally).testTag("stayCountdown"))
                    Text(remaining?.let { if(it>=0) "남은 체류 ${it/60}분 ${it%60}초" else "체류시간 초과 ${-it/60}분 ${-it%60}초" }
                        ?: "체류 시계 변경 감지 · 확인 필요",fontSize=14.sp,lineHeight=20.sp,modifier=Modifier.testTag("stayTimer"))
                    Text("원래 계획 ${journey.plan.orderedVisits[s.index].stayMinutes}분 · 현재 계획 ${s.stays[s.index]/60}분",fontSize=14.sp,lineHeight=20.sp,modifier=Modifier.testTag("stayPlan"))
                    Text("수동 시연 경과 "+(elapsed?.let { stayDuration(it) } ?: "UNKNOWN"),fontSize=14.sp,lineHeight=20.sp,modifier=Modifier.testTag("stayElapsed"))
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        JourneyAction("체류 10분 단축 제안","shortenProposal") {onProposal(journey.shortenProposal())}
                        JourneyAction("선택 방문지 제외 제안","omitProposal",journey.omitProposal()!=null) {onProposal(journey.omitProposal())}
                    }
                    Text("변경안은 승인 전 적용되지 않습니다. 체류 연장은 현재 지원하지 않습니다.",fontSize=12.sp,lineHeight=18.sp,color=Color.DarkGray)
                    if(remaining!=null && remaining<0) Text("예정 시간을 넘겼습니다. 자동 이동·일정 변경은 하지 않습니다.",fontSize=14.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.error)
                }
                StayPanel {
                    Text("참고 귀가 예상 ${time(projection.returnAt)}",fontSize=16.sp,lineHeight=22.sp,color=JourneyNavy)
                    Text("귀가 관리: ${projection.feasibility} · 실제 이동 상태 미확인",fontSize=14.sp,lineHeight=20.sp,modifier=Modifier.testTag("returnStatus"))
                    Text("기존 ETA 가정 계산: ${projection.arithmetic} · 최신 교통 검증 아님",fontSize=13.sp,lineHeight=18.sp)
                    Text("귀가 마감 ${time(journey.request.returnDeadline.toInstant())} · 안전 여유 ${journey.request.safetyBufferMin}분",fontSize=13.sp,lineHeight=18.sp)
                    Text("마감까지 여유 ${projection.marginSeconds?.let { Math.floorDiv(it,60).toString()+"분" } ?: "UNKNOWN"}",fontSize=13.sp,lineHeight=18.sp)
                    Text(projection.reasons.joinToString(" · "),fontSize=12.sp,lineHeight=18.sp,color=Color.DarkGray)
                }
                JourneyAction("체류 종료 승인 · 다음 준비","finishStay",remaining!=null) {journey.finishStay(true)}
                debugContent()
                if(!wide) StayingSchedule(journey,s)
            }
            if(wide) Row(Modifier.fillMaxSize().padding(horizontal=16.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(688f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp),content=information)
                StayingSchedule(journey,s,Modifier.weight(340f).verticalScroll(rememberScrollState()))
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=information)
        }
    }
    }
}

@Composable private fun StayingSchedule(journey:TripJourney,s:JourneyProgress,modifier:Modifier=Modifier) {
    StayPanel(modifier.testTag("stayingSchedule")) {
        Text("오늘의 일정 · ${journey.plan.title}",fontSize=13.sp,lineHeight=18.sp,fontWeight=FontWeight.Medium)
        Text("확정 일정 · 필수 방문지는 동의 없이 제외하지 않습니다.",fontSize=12.sp,lineHeight=18.sp,color=JourneyTeal)
        journey.plan.orderedVisits.forEachIndexed { i,v ->
            val current=i==s.index
            val place=journey.plan.validation.visits.getOrNull(i)?.confirmedPlace
            val leg=journey.plan.validation.legs.getOrNull(i)
            Column(Modifier.fillMaxWidth().background(if(current) StaySoft else Color.White)
                .border(1.dp,if(current) JourneyTeal else StayLine).padding(10.dp).testTag("stayVisit$i"),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text(when { i in s.omitted -> "시연 제외 승인됨 · 경로 미검증"; current -> "현재 방문지 · 머무는 중"; i<s.index -> "이전 방문 · 수동 시연"; else -> "남은 방문지" },fontSize=11.sp,lineHeight=16.sp,color=JourneyTeal)
                Text("${i+1}. ${place?.name ?: "미확인"}",fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.Medium)
                Text("원래 ${v.stayMinutes}분 / 현재 ${s.stays[i]/60}분 · "+(if(i in journey.requiredIndexes) "필수" else "선택"),fontSize=12.sp,lineHeight=18.sp)
                Text(if(s.omitted.isNotEmpty()) "이동 구간 변경 · 서버 재검증 필요" else "원래 구간 이동 "+(leg?.durationSeconds?.let { "${(it+59)/60}분" } ?: "UNKNOWN"),fontSize=12.sp,lineHeight=18.sp,color=Color.DarkGray)
                Text(place?.data?.dataMode?.name ?: "출처 미확인",fontSize=11.sp,lineHeight=16.sp,color=JourneyTeal)
            }
        }
        Text("방문별 도착 시각은 제공되지 않았습니다.",fontSize=12.sp,lineHeight=18.sp,color=Color.Gray)
        Text("원래 검증 계획 귀가 예상 ${time(journey.plan.validation.predictedReturnAt?.toInstant())}",fontSize=13.sp,lineHeight=18.sp,color=JourneyNavy)
        Text("계획 데이터 출처: "+journey.plan.dataModes.joinToString {it.name},fontSize=11.sp,lineHeight=16.sp,color=JourneyTeal)
    }
}
