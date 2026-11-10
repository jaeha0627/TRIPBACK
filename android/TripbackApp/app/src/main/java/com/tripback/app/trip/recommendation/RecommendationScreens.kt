package com.tripback.app.trip.recommendation

import androidx.activity.compose.BackHandler
import com.tripback.app.BuildConfig
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.*
import com.tripback.app.trip.*
import java.time.format.DateTimeFormatter

private val LocalHttp = staticCompositionLocalOf { false }

private val Navy=Color(0xFF002C5F)
private val Teal=Color(0xFF007FA8)
private val Line=Color(0xFFD9DEE3)
private val Soft=Color(0xFFF3F5F7)
private val Ink=Color(0xFF14191F)
private val HomeFont = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(com.tripback.app.R.font.noto_sans_kr_regular)
)

@Composable private fun HomeStyle(content:@Composable ()->Unit) {
    val base = MaterialTheme.typography
    MaterialTheme(typography=base.copy(
        bodyLarge=base.bodyLarge.copy(fontFamily=HomeFont),
        bodyMedium=base.bodyMedium.copy(fontFamily=HomeFont),
        bodySmall=base.bodySmall.copy(fontFamily=HomeFont),
        labelLarge=base.labelLarge.copy(fontFamily=HomeFont),
        labelMedium=base.labelMedium.copy(fontFamily=HomeFont),
        labelSmall=base.labelSmall.copy(fontFamily=HomeFont)
    ),content=content)
}

@Composable
fun RecommendationFlow(onExit:()->Unit, repository:RecommendationRepository?=null,
    journeyClock:com.tripback.app.trip.progress.JourneyClock=com.tripback.app.trip.progress.AndroidJourneyClock) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val navigation=remember(context) { PleosNavigationAdapter(context) }
    DisposableEffect(navigation) { navigation.connect(); onDispose { navigation.close() } }
    val capability by navigation.capability.collectAsState()
    val observation by navigation.observation.collectAsState()
    val scope=rememberCoroutineScope()
    val repo=repository ?: remember { FakeRecommendationRepository() }
    val saver=remember(scope,repo,navigation,journeyClock) { listSaver<RecommendationSession,String>(
        save={it.snapshot()},restore={ saved -> RecommendationSession(scope,repo,navigation,journeyClock).also {it.restore(saved)} }) }
    val session=rememberSaveable(saver=saver) { RecommendationSession(scope,repo,navigation,journeyClock) }
    val state by session.state.collectAsState()
    val journey by session.journey.collectAsState()
    val departure by session.navigation.state.collectAsState()
    var form by rememberSaveable { mutableStateOf(RecommendationForm()) }
    var inputError by rememberSaveable { mutableStateOf<String?>(null) }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(session,lifecycle) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP) session.cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer);session.cancel() }
    }
    BackHandler { if(state.page==RecommendationPage.INPUT) onExit() else session.back() }
    CompositionLocalProvider(LocalHttp provides (repo is HttpRecommendationRepository)) {
    MaterialTheme(colorScheme=lightColorScheme(primary=Navy,secondary=Teal,background=Color.White,surface=Color.White,onSurface=Ink)) {
        // Share the home width between header, form and footer. Keep other pages unchanged.
        Box(Modifier.fillMaxSize().background(Color.White),contentAlignment=Alignment.TopCenter) {
        val homeModifier = if (state.page == RecommendationPage.INPUT)
            Modifier.widthIn(max=1080.dp).imePadding() else Modifier
        Column(homeModifier.fillMaxSize().background(Color.White).testTag("recommendationFlow")) {
            if (state.page == RecommendationPage.INPUT) HomeStyle { HomeHeader(onExit) } else {
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                OutlinedButton(onClick={if(state.page==RecommendationPage.INPUT) onExit() else session.back()},shape=RectangleShape,modifier=Modifier.heightIn(min=48.dp)) { Text("‹ 뒤로") }
                Text("  ⇄  Tripback",color=Navy,fontSize=20.sp)
                Spacer(Modifier.weight(1f))
                BadgeText("새 여행",Teal)
                Spacer(Modifier.width(12.dp))
                TextButton(onClick=onExit,enabled=state.page!=RecommendationPage.JOURNEY,modifier=Modifier.heightIn(min=48.dp)) { Text("기존 기능") }
            }
            HorizontalDivider(color=Line)
            Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                listOf("가는 길","머무는 시간","오는 길").forEach { Text(it,Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Center,color=Color.Gray,fontSize=13.sp) }
            }
            Text(if(LocalHttp.current) "HTTP 개발 연결 · 127.0.0.1:8080 · 테스트 좌표 · 차량 기어·속도 미연결" else "FAKE 시연 · 실제 AI·카카오 호출 없음 · 차량 기어·속도 미연결",
                Modifier.fillMaxWidth().background(Soft).padding(horizontal=24.dp,vertical=8.dp),fontSize=13.sp,color=Navy)
            }
            Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.TopCenter) {
                when(state.page) {
                    RecommendationPage.INPUT -> HomeStyle { InputPage(form,{form=it;inputError=null},inputError ?: state.error,
                        onSubmit={
                            try { val request=form.request(); RecommendationJson.request(request);inputError=null;session.submit(request,form.scenario) }
                            catch(_:Exception) { inputError="지역·좌표·날짜·시각·정수 범위·필수 장소 ID와 주차 확인을 점검해 주세요." }
                        },hasPrevious=state.response!=null,onPrevious=session::results) }
                    RecommendationPage.LOADING -> LoadingPage(form,onCancel=session::cancel)
                    RecommendationPage.RESULTS -> ResultsPage(state,onDetail=session::detail,onEdit=session::input)
                    RecommendationPage.DETAIL -> DetailPage(state,onSelect=session::select,onBack=session::results)
                    RecommendationPage.JOURNEY -> journey?.let { current ->
                        com.tripback.app.trip.progress.JourneyScreen(current,session::finishDemo) {
                            com.tripback.app.trip.replan.JourneyReplanDebug(current,state.request,state.raw,state.selectedPlanId, repo is HttpRecommendationRepository)
                        }
                    }
                    RecommendationPage.READY -> ReadyPage(state,capability,observation,departure,onDemo={session.beginDemo()},onApprove={session.approveDeparture(it,false)},onBack=session::back,onEdit=session::input)
                }
            }
        }
    }
}

}
}

@Composable private fun HomeHeader(onExit:()->Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    Row(Modifier.horizontalScroll(rememberScrollState()).width(maxOf(maxWidth,600.dp)).heightIn(min=57.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
        OutlinedButton(onClick=onExit,shape=RectangleShape,modifier=Modifier.heightIn(min=48.dp)) { Text("‹ 뒤로") }
        Text("⇄",Modifier.padding(start=12.dp).background(Navy).padding(5.dp),color=Color.White,fontSize=14.sp)
        Text("Tripback",Modifier.padding(start=8.dp),color=Navy,fontSize=15.sp,fontWeight=FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Text("새 여행",Modifier.padding(vertical=14.dp),color=Navy,fontSize=16.sp,fontWeight=FontWeight.Medium)
            Box(Modifier.width(83.dp).height(3.dp).background(Teal))
        }
        Text("내 여행 · 준비 중",Modifier.padding(horizontal=12.dp),color=Color.Gray,fontSize=13.sp)
        Spacer(Modifier.weight(1f))
        TextButton(onClick=onExit,modifier=Modifier.heightIn(min=48.dp)) { Text("기존 기능") }
    }
    }
    HorizontalDivider(color=Line)
    Text(if(LocalHttp.current) "HTTP 개발 연결 · 테스트 좌표 · 차량 상태 미연결" else "FAKE · DEMO_SIMULATED · 실제 AI·카카오 호출 없음 · 차량 상태 미연결",
        Modifier.fillMaxWidth().background(Soft).padding(horizontal=16.dp,vertical=6.dp),fontSize=12.sp,color=Navy)
}

@Composable private fun Title(text:String,subtitle:String) {
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(text,fontSize=34.sp,lineHeight=44.sp,color=Ink,fontWeight=FontWeight.Normal)
        Text(subtitle,fontSize=14.sp,color=Color.DarkGray)
    }
}
@Composable private fun Panel(modifier:Modifier=Modifier,soft:Boolean=false,content:@Composable ColumnScope.()->Unit) {
    Column(modifier.fillMaxWidth().background(if(soft)Soft else Color.White).border(1.dp,if(soft)Soft else Line).padding(18.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
}
@Composable private fun BadgeText(text:String,color:Color=Teal) {
    Text(text,Modifier.border(1.dp,color).padding(horizontal=10.dp,vertical=6.dp),color=color,fontSize=12.sp)
}
@Composable private fun Action(text:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true) {
    Button(onClick=onClick,enabled=enabled,shape=RectangleShape,modifier=modifier.heightIn(min=50.dp)) { Text(text) }
}
@Composable private fun Field(label:String,value:String,onChange:(String)->Unit,modifier:Modifier=Modifier) {
    OutlinedTextField(value=value,onValueChange=onChange,label={Text(label)},singleLine=true,shape=RectangleShape,modifier=modifier.fillMaxWidth())
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun Choices(label:String,options:Map<String,String>,selected:List<String>,choose:(String)->Unit) {
    Text(label,fontSize=14.sp,fontWeight=FontWeight.Medium)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        options.forEach { (key,title) ->
            FilterChip(selected=key in selected,onClick={choose(key)},label={Text(title)},shape=RectangleShape,modifier=Modifier.heightIn(min=48.dp),
                border=androidx.compose.foundation.BorderStroke(1.dp,if(key in selected) Teal else Line),
                colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Color.White,selectedLabelColor=Teal))
        }
    }
}
private fun toggle(values:List<String>,key:String)=if(key in values) values-key else values+key

@Composable private fun InputPage(form:RecommendationForm,change:(RecommendationForm)->Unit,error:String?,
    onSubmit:()->Unit,hasPrevious:Boolean,onPrevious:()->Unit) {
    var more by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.widthIn(max=1080.dp).fillMaxSize()) {
        val wide=maxWidth>=840.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).padding(horizontal=16.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(if(wide) 688f else 1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Text("오늘은 어디로\n떠나볼까요?",fontSize=40.sp,lineHeight=53.sp,letterSpacing=(-0.4).sp)
                    Text("가는 길, 머무는 시간, 돌아오는 길까지 하루 일정을 한 번에 준비해요.",fontSize=14.sp,color=Color.DarkGray)
                    Column(Modifier.fillMaxWidth().border(1.dp,Teal).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("여행 조건을 알려주세요",fontWeight=FontWeight.Medium)
                        Text("주차 후 아래 조건을 선택해 주세요. 음성 입력은 아직 지원하지 않습니다.",fontSize=13.sp)
                    }
                    Field("목표 지역",form.area,{change(form.copy(area=it))})
                    Field("여행 날짜 · YYYY-MM-DD · Asia/Seoul",form.date,{change(form.copy(date=it))})
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Field("출발 · HH:mm",form.depart,{change(form.copy(depart=it))},Modifier.weight(1f))
                        Field("귀가 마감 · 같은 날",form.deadline,{change(form.copy(deadline=it))},Modifier.weight(1f))
                    }
                    Choices("안전 여유 시간",mapOf("15" to "15분","30" to "30분","45" to "45분"),listOf(form.buffer.toString())){change(form.copy(buffer=it.toInt()))}
                    Choices("누구와 가나요?",TripChoices.companions,listOf(form.companion)){change(form.copy(companion=it))}
                    if(form.companion=="FAMILY") Choices("아이 연령대",TripChoices.childAges,listOf(form.child)){change(form.copy(child=it))}
                    Choices("하고 싶은 것 · 여러 개 선택",TripChoices.interests,form.interests){change(form.copy(interests=toggle(form.interests,it)))}
                    Choices("차량 · 사용자 선택",TripChoices.vehicles,listOf(form.vehicle)){change(form.copy(vehicle=it))}
                    Field("최대 편도시간 · 1~180분",form.oneWay,{change(form.copy(oneWay=it))})
                    Field("필수 방문 카카오 ID · 쉼표 구분, 최대 5개",form.requiredIds,{change(form.copy(requiredIds=it))})
                    Text(if(LocalHttp.current) "서버가 확인할 KAKAO_LOCAL ID를 입력합니다. 미확인 ID는 대체하지 않습니다." else "시연 후보 ID: fixture-001, fixture-002. 다른 ID는 임의 대체하지 않습니다.",fontSize=12.sp,color=Color.DarkGray)
                    OutlinedButton(onClick={more=!more},shape=RectangleShape,modifier=Modifier.heightIn(min=48.dp)) {Text(if(more)"더 자세한 조건 접기 −" else "더 자세한 조건 +")}
                    if(more) {
                    Text("테스트용 좌표 · GPS나 실제 사용자 위치가 아닙니다.",color=Teal,fontSize=13.sp)
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Field("출발 위도",form.originLat,{change(form.copy(originLat=it))},Modifier.weight(1f))
                        Field("출발 경도",form.originLon,{change(form.copy(originLon=it))},Modifier.weight(1f))
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Field("목표 중심 위도",form.centerLat,{change(form.copy(centerLat=it))},Modifier.weight(1f))
                        Field("목표 중심 경도",form.centerLon,{change(form.copy(centerLon=it))},Modifier.weight(1f))
                    }

                        Text("아래 조건은 AI 선호 힌트이며 실제 충족 여부는 확인하지 않습니다.",color=Teal,fontSize=13.sp)
                        Field("1인 예산 · 빈칸은 미지정",form.budget,{change(form.copy(budget=it))})
                        Choices("식사",TripChoices.meals,form.meals){change(form.copy(meals=toggle(form.meals,it)))}
                        Choices("걷기",TripChoices.walking,listOf(form.walking)){change(form.copy(walking=it))}
                        Choices("주차",TripChoices.parking,form.parking){change(form.copy(parking=toggle(form.parking,it)))}
                        Choices("편의시설",TripChoices.amenities,form.amenities){change(form.copy(amenities=toggle(form.amenities,it)))}
                        Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(form.rain,{change(form.copy(rain=it))});Text("비가 오면 실내 선호")}
                    }
                    if(BuildConfig.DEBUG && !wide && !LocalHttp.current) ScenarioPicker(form.scenario){change(form.copy(scenario=it))}
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(form.parked,{change(form.copy(parked=it))},Modifier.testTag("parked"))
                        Text("주차 상태에서 시연합니다 · 차량 상태 자동 확인 아님")
                    }
                    error?.let { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("recommendationError")) }
                    if(hasPrevious) TextButton(onClick=onPrevious){Text("이전 입력의 마지막 결과 보기")}
                    Spacer(Modifier.height(16.dp))
                }
                if(wide) Column(Modifier.weight(340f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Panel {
                        Text("지난 여행",fontSize=13.sp,fontWeight=FontWeight.Medium)
                        Text("지난 여행 내역 미연결",Modifier.fillMaxWidth().background(Soft).padding(16.dp),color=Navy)
                        Text("저장된 여행 조회 기능은 아직 지원하지 않습니다.",fontSize=14.sp,color=Color.DarkGray)
                    }
                    if(BuildConfig.DEBUG && !LocalHttp.current) ScenarioPicker(form.scenario){change(form.copy(scenario=it))}
                    Panel { Text("왕복 여행 준비",fontWeight=FontWeight.Medium);Text("01  조건 입력\n02  코스 비교\n03  상세 확인\n04  로컬 선택",lineHeight=30.sp)
                        Text("실제 내비 안내·여행 저장은 시작하지 않습니다.",fontSize=13.sp,color=Color.DarkGray) }
                    SourceLegend()
                }
            }
            HorizontalDivider(color=Line)
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                Box(Modifier.weight(if(wide) 688f else 1f),contentAlignment=Alignment.Center) {
                    Action(if(LocalHttp.current) "AI로 일정 만들기" else "AI 일정 시연 만들기",onSubmit,Modifier.widthIn(max=520.dp).fillMaxWidth().testTag("generate"))
                }
                if(wide) Spacer(Modifier.weight(340f))
            }
        }
    }
}
@Composable private fun ScenarioPicker(selected:FakeScenario,onChange:(FakeScenario)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    Panel {
        Text("개발용 Fake 시나리오",fontWeight=FontWeight.Medium)
        Box {
            OutlinedButton(onClick={expanded=true},shape=RectangleShape,modifier=Modifier.heightIn(min=48.dp).testTag("scenarioPicker")) {Text(selected.label+" ▾")}
            DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                FakeScenario.entries.forEach { scenario -> DropdownMenuItem(text={Text(scenario.label)},
                    onClick={onChange(scenario);expanded=false},modifier=Modifier.testTag("scenario"+scenario.name)) }
            }
        }
        Text("가상 응답으로 화면을 확인합니다. 실제 추천·외부 요청·API 비용은 없습니다.",fontSize=13.sp)
    }
}
@Composable private fun LoadingPage(form:RecommendationForm,onCancel:()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=32.dp,vertical=64.dp).testTag("loading"),verticalArrangement=Arrangement.Top,horizontalAlignment=Alignment.CenterHorizontally) {
        CircularProgressIndicator(color=Teal);Spacer(Modifier.height(24.dp))
        Text("일정을 짜는 중이에요",fontSize=26.sp)
        Text(form.area+" · "+form.deadline+"까지 귀가",Modifier.padding(12.dp))
        Text(if(LocalHttp.current) "서버 응답을 기다립니다. 최대 약 195초가 걸릴 수 있습니다." else "Fake JSON을 준비하고 화면 계약을 확인하고 있어요.",color=Color.DarkGray)
        Text(if(LocalHttp.current) "자동 재시도하지 않습니다. 취소해도 서버 처리는 계속될 수 있습니다." else "실제 장소 검색·AI 생성·에너지 검증은 실행하지 않습니다.",fontSize=13.sp)
        Spacer(Modifier.height(24.dp));OutlinedButton(onClick=onCancel,shape=RectangleShape,modifier=Modifier.heightIn(min=48.dp).testTag("cancel")){Text("취소")}
    }
}
@Composable private fun ResultsPage(state:RecommendationUiState,onDetail:(String)->Unit,onEdit:()->Unit) {
    val response=state.response ?: return
    Column(Modifier.widthIn(max=1280.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("results"),
        verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Title(if(response.alternatives.isEmpty())"추천 가능한 코스가 없어요" else "일정안 ${response.alternatives.size}가지를\n준비했어요",
            if(LocalHttp.current) "서버 검증 결과와 미검증 AI 설명을 구분해 주세요." else "시연 결과입니다. 귀가 판정과 AI 설명을 구분해서 확인해 주세요.")
        Text(if(response.alternatives.isEmpty()) "조건과 처리 결과를 확인해 주세요." else if(LocalHttp.current) "현재 ETA 기준 귀가 시간 검사를 통과한 제안입니다." else "귀가 시간 검사를 통과한 시연 제안입니다.",color=Navy)
        BoxWithConstraints {
            if(maxWidth>=760.dp && response.alternatives.size==2) Row(Modifier.height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                response.alternatives.forEach { a -> PlanCard(a,onDetail,Modifier.weight(1f).fillMaxHeight()) }
            } else Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {response.alternatives.forEach {PlanCard(it,onDetail,Modifier.fillMaxWidth())}}
        }
        if(response.alternatives.isEmpty()) Panel(soft=true) {
            Text(when(response.aiProviderStatus.status) {"NOT_CONFIGURED"->"AI 연결이 설정되지 않았습니다.";"TIMEOUT"->"모델 응답 시간이 초과되었습니다.";"HTTP_ERROR"->"모델 요청을 처리하지 못했습니다.";else->"조건 또는 검증 결과를 확인해 주세요."})
            Text("다른 장소나 시연 코스로 자동 대체하지 않습니다.")
        }
        response.rejectedAlternatives.forEach { rejected -> Panel {
            Text("선택할 수 없는 제안 · "+rejectionLabel(rejected.reason),color=MaterialTheme.colorScheme.error)
            RejectionDiagnostic(rejected.index, rejected.reason, LocalHttp.current)
            rejected.proposal?.let { a -> Text(a.title+" · "+feasibilityLabel(a.feasibility));Margin(a)
                Text("장소 또는 필수 이동 구간의 정보를 확인해 주세요.",fontSize=12.sp) }
        } }
        SourceLegend()
        if(LocalHttp.current) {
            Text("AI: ${response.aiProviderStatus.provider} / ${response.aiProviderStatus.status} / ${response.aiProviderStatus.invocation}",fontSize=12.sp)
            response.conditionStatus.forEach { (condition,status) -> Text("$condition: $status",fontSize=12.sp) }
            response.limitations.forEach { limitation -> Text(limitation,fontSize=12.sp,color=Color.Gray) }
        }
        Text("선호 조건: 예산·식사·주차·걷기·편의시설·우천 조건은 미검증입니다.",fontSize=13.sp,color=Color.DarkGray)
        Text(if(LocalHttp.current) "서버 호출 통계: 장소 ${response.calls.placeSearches}, 경로 ${response.calls.roadRoutes}, 모델 ${response.calls.modelGenerations} · 호출 성공·과금 횟수를 뜻하지 않습니다." else "시연 호출 통계: 장소 ${response.calls.placeSearches}, 경로 ${response.calls.roadRoutes}, 모델 ${response.calls.modelGenerations} · 실제 외부 호출 0회",fontSize=12.sp)
        OutlinedButton(onClick=onEdit,shape=RectangleShape,modifier=Modifier.heightIn(min=48.dp).testTag("editConditions")){Text("조건 바꾸기")}
    }
}
@Composable private fun PlanCard(a:RecommendationAlternative,onDetail:(String)->Unit,modifier:Modifier) {
    Panel(modifier) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text(a.title,Modifier.weight(1f).padding(end=8.dp),fontSize=23.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis);BadgeText(if(ValidationDataMode.DEMO_SIMULATED in a.dataModes) "DEMO" else a.dataModes.joinToString {it.name})
        }
        Margin(a);Text("현재 ETA 기준 수학적 판정 · 실제 귀가 보장 아님",fontSize=12.sp,color=Teal)
        Timeline(a)
        Text("AI 추천 이유 · 미검증",color=Teal,fontSize=13.sp)
        Text(a.recommendationReason,fontSize=14.sp)
        Action("상세 일정 보기",{onDetail(a.planId)},Modifier.fillMaxWidth().testTag("detail"+a.planId))
    }
}
@Composable private fun Margin(a:RecommendationAlternative) {
    val v=a.validation
    Text("집 도착 예상  "+(v.predictedReturnAt?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "미확인"),fontSize=19.sp,color=Navy)
    Text("귀가 여유  "+(v.remainingMarginMin?.let {"${it}분"} ?: "미확인")+" · "+feasibilityLabel(a.feasibility),fontSize=14.sp)
}
fun feasibilityLabel(value:ValidationFeasibility)=when(value) {
    ValidationFeasibility.FEASIBLE->"현재 ETA 기준 가능"
    ValidationFeasibility.INFEASIBLE->"마감 또는 안전 여유 부족"
    ValidationFeasibility.UNKNOWN->"귀가 가능 여부 미확인"
}
@Composable private fun Timeline(a:RecommendationAlternative) {
    a.orderedVisits.forEachIndexed { i,visit ->
        val place=a.validation.visits[i].confirmedPlace
        val leg=a.validation.legs[i]
        Text("이동 "+(leg.durationSeconds?.let {"${(it+59)/60}분"} ?: "미확인"),color=Color.Gray,fontSize=12.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("${i+1}  •",color=Teal)
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(place?.name ?: "장소 미확인",fontWeight=FontWeight.Medium)
                Text("체류 ${visit.stayMinutes}분 · "+(place?.data?.dataMode?.name ?: "출처 미확인"),fontSize=12.sp)
                Text(place?.address ?: "주소 미확인",fontSize=12.sp,color=Color.DarkGray)
            }
        }
        HorizontalDivider(color=Line)
    }
    Text("마지막 방문 → 출발지 귀가  "+(a.validation.legs.last().durationSeconds?.let {"${(it+59)/60}분"} ?: "ETA 미확인"),fontSize=13.sp)
}
@Composable private fun DetailPage(state:RecommendationUiState,onSelect:()->Unit,onBack:()->Unit) {
    val a=state.selected ?: return
    BoxWithConstraints(Modifier.widthIn(max=1280.dp).fillMaxSize()) {
        val wide=maxWidth>=900.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).padding(24.dp),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(if(wide)0.65f else 1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Title(a.title+" 일정","선택 전 귀가 조건과 장소 정보를 확인해 주세요.")
                    Panel(soft=true) {Text("귀가 시간 확인",color=Teal);Margin(a);Text("안전 여유 ${state.request?.safetyBufferMin}분 · 미래 교통과 실제 귀가를 보장하지 않습니다.",fontSize=13.sp)}
                    Panel {Text("AI 추천 이유 · 미검증",color=Teal);Text(a.recommendationReason)}
                    Panel {Text("방문 정보 안내",fontWeight=FontWeight.Medium);Text("영업·예약·주차·가격·차량 진입 가능성은 확인하지 않았습니다.")}
                    if(!wide) Panel {Text("오늘의 시연 일정",fontWeight=FontWeight.Medium);Timeline(a)}
                    Text("장소·이동시간 출처: "+a.dataModes.joinToString {it.name},fontSize=12.sp)
                    Text("AI 설명 미검증 · 현재 ETA 기준 · 영업과 방문 가능성 미확인",fontSize=12.sp,color=Color.Gray)
                }
                if(wide) Panel(Modifier.weight(0.35f).verticalScroll(rememberScrollState())) {Text("오늘의 일정 · 시연",fontWeight=FontWeight.Medium);Timeline(a)}
            }
            Row(Modifier.fillMaxWidth().border(1.dp,Line).padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp,Alignment.CenterHorizontally)) {
                OutlinedButton(onClick=onBack,shape=RectangleShape,modifier=Modifier.heightIn(min=48.dp)){Text("다른 안 보기")}
                Action("이 코스 선택 · 출발 준비",onSelect,Modifier.testTag("selectPlan"))
            }
        }
    }
}
@Composable private fun ReadyPage(state:RecommendationUiState,capability:NavigationCapability,observation:String,departure:NavigationState,onDemo:()->Unit,onApprove:(Boolean)->Unit,onBack:()->Unit,onEdit:()->Unit) {
    val a=state.selected ?: return
    Column(Modifier.widthIn(max=1000.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp).testTag("ready"),
        verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Title("출발을 준비해요","코스를 이 기기에서 선택했습니다. 서버 여행 저장이나 내비 안내는 시작하지 않았습니다.")
        Panel(soft=true) {Text(a.title,fontSize=24.sp,color=Navy);Margin(a);BadgeText("로컬 선택 · "+a.dataModes.joinToString {it.name})}
        Panel {Text("안내 시작 전 확인",fontWeight=FontWeight.Medium)
            Text("장소·ETA 출처: "+a.dataModes.joinToString {it.name})
            val destination=runCatching { NavigationDestinationValidator.first(a) }
            destination.getOrNull()?.let { d ->
                Text("첫 목적지: ${d.name}\n${d.address}\n위도 ${d.coordinates.latitude} / 경도 ${d.coordinates.longitude}")
                Text("원본 ID: ${d.sourceId.provider} / ${d.sourceId.value} · Pleos POI ID 아님")
            }
            destination.exceptionOrNull()?.let { Text(it.message ?: "목적지 검증 실패") }
            Text(capability.message,modifier=Modifier.testTag("navigationCapability"))
            Text("차량 UX 제한은 앱에서 확인합니다. 주차 체크박스는 실제 차량 상태가 아닙니다. POI 규약 검증 전 출발 승인 기능은 비활성 상태입니다.")
            Text(observation)
            Text(departure.message,modifier=Modifier.testTag("navigationState"))
        }
        Action("실제 내비 안내 미연결",{onApprove(true)},enabled=false,modifier=Modifier.testTag("navigationDisabled"))
        Action("여행 진행 수동 시연 · DEMO_SIMULATED",onDemo,modifier=Modifier.testTag("beginJourneyDemo"))
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick=onBack,shape=RectangleShape,modifier=Modifier.heightIn(min=48.dp).testTag("readyReview")){Text("일정 다시 보기")}
            OutlinedButton(onClick=onEdit,shape=RectangleShape,modifier=Modifier.heightIn(min=48.dp)){Text("조건 바꾸기")}
        }
    }
}
private val rejectionDescriptions = mapOf(
    "INVALID_TEXT" to "제목 또는 설명의 길이·공백·문자 형식이 올바르지 않아요.",
    "UNSAFE_GENERATED_TEXT" to "제목 또는 설명에서 민감정보 형태가 감지됐어요.",
    "UNLISTED_PLACE_ID" to "후보 목록에 없는 방문지가 포함됐어요.",
    "DUPLICATE_VISIT" to "한 제안에 같은 방문지가 중복됐어요.",
    "INVALID_VISIT_COUNT" to "방문지 개수가 허용 범위인 1~5개를 벗어났어요.",
    "INTEGER_OUT_OF_RANGE" to "체류시간 등 정수 값이 허용 범위를 벗어났어요.",
    "INVALID_INTEGER" to "체류시간 등 정수 값의 형식이 올바르지 않아요.",
    "DUPLICATE_ALTERNATIVE" to "방문 순서와 체류시간이 같은 제안이 중복됐어요.",
    "INVALID_STRUCTURE" to "제안의 데이터 구조가 올바르지 않아요.",
    "UNSUPPORTED_FIELD" to "제안에 허용되지 않은 항목이 포함됐어요.",
    "MISSING_FIELD" to "제안에 필요한 항목이 빠졌어요.",
    "UNSUPPORTED_VALUE" to "제안에 허용되지 않은 값이 포함됐어요.",
    "INVALID_JSON" to "제안의 JSON 형식이 올바르지 않아요.",
    "JSON_SIZE_LIMIT" to "제안 데이터의 크기가 허용 범위를 벗어났어요.",
    "INVALID_ALTERNATIVE_COUNT" to "제안 개수가 허용 범위를 벗어났어요.",
    "MODEL_RETURNED_NO_ALTERNATIVES" to "모델이 제안을 반환하지 않았어요.",
    "VALIDATION_UNKNOWN" to "ETA 또는 장소 확인이 부족해요.",
    "VALIDATION_INFEASIBLE" to "귀가 마감 또는 안전 여유를 지키기 어려워요.",
    "MAXIMUM_ONE_WAY_EXCEEDED" to "최대 편도시간을 초과했어요.",
    "REQUIRED_PLACE_UNAVAILABLE" to "필수 방문지를 확인하지 못했어요.",
    "REQUIRED_VISIT_MISSING" to "필수 방문지를 확인하지 못했어요."
)
internal fun rejectionLabel(reason:String) =
    rejectionDescriptions[reason] ?: "제안의 장소와 검증 결과를 확인하지 못했어요."

// Only fixed allowlisted text and a bounded numeric index enter this diagnostic.
internal fun rejectionDiagnosticText(index:Int, reason:String, debug:Boolean, http:Boolean):String? {
    if (!debug || !http) return null
    val safeIndex = if (index in 0..1) index.toString() else if (index == -1) "전체" else "미확인"
    val safeCode = if (reason in rejectionDescriptions) reason else "UNKNOWN_REJECTION"
    return "거부안 index: $safeIndex · reason: $safeCode"
}

@Composable internal fun RejectionDiagnostic(index:Int, reason:String, http:Boolean) {
    rejectionDiagnosticText(index, reason, BuildConfig.DEBUG, http)?.let {
        Text(it, fontSize=12.sp, modifier=Modifier.testTag("rejectionDiagnostic"))
    }
}
@Composable private fun SourceLegend() {
    Panel {
        Text("출처 표시 안내",fontWeight=FontWeight.Medium)
        Text("DEMO_SIMULATED · 가상 장소와 ETA",color=Teal,fontSize=12.sp)
        Text("VERIFIED_LIVE · 공급자 응답 확인",fontSize=12.sp)
        Text("VERIFIED_CACHED · 검증된 캐시",fontSize=12.sp)
        Text(if(LocalHttp.current) "서버가 반환한 출처를 표시하며 실제 방문 가능성이나 위치 인증을 뜻하지 않습니다." else "위 LIVE/CACHED는 라벨 설명입니다. 이 화면의 Fake 코스를 실제 데이터로 승격하지 않습니다.",fontSize=12.sp,color=Color.Gray)
    }
}
