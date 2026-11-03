package com.tripback.app.trip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TripInputScreen(input: TripInput, onChange: (TripInput) -> Unit, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val errors = input.validationErrors()
    val change: (TripInput) -> Unit = { submitted = false; onChange(it) }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("오늘은 어디로 떠나볼까요?", style = MaterialTheme.typography.headlineMedium)
        Text("가는 길부터 돌아오는 길까지 · AI 추천 연결 전")
        Text("정차 중 입력해 주세요. 차량 상태 연동과 주행 중 자동 편집 제한은 아직 없습니다.")
        ChoiceGroup("여행 지역", TripChoices.regions.associateWith { it }, setOf(input.region)) { change(input.copy(region = it)) }
        OutlinedTextField(input.departTime, { change(input.copy(departTime = it)) },
            label = { Text("출발시간 (HH:mm)") }, singleLine = true,
            isError = showErrors && errors.isNotEmpty(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(input.returnDeadline, { change(input.copy(returnDeadline = it)) },
            label = { Text("귀가 마감시간 (HH:mm)") }, singleLine = true,
            isError = showErrors && errors.isNotEmpty(), modifier = Modifier.fillMaxWidth())
        Text("같은 날 일정만 지원합니다. 귀가 마감은 출발보다 2시간 넘게 뒤로 정해 주세요.")
        ChoiceGroup("안전 여유", listOf(15, 30, 45).associate { it.toString() to "${it}분" }, setOf(input.safetyBufferMin.toString())) {
            change(input.copy(safetyBufferMin = it.toInt()))
        }
        ChoiceGroup("동행 유형", TripChoices.companions, setOf(input.companionType)) {
            change(input.copy(companionType = it, childAgeGroup = if (it == "FAMILY") input.childAgeGroup ?: "ELEMENTARY" else null))
        }
        if (input.companionType == "FAMILY") {
            ChoiceGroup("아이 연령", TripChoices.childAges, setOfNotNull(input.childAgeGroup)) { change(input.copy(childAgeGroup = it)) }
        }
        ChoiceGroup("관심 활동 · 복수 선택", TripChoices.interests, input.interests) {
            change(input.copy(interests = input.interests.toggle(it)))
        }
        ChoiceGroup("차량 종류 · 직접 선택", TripChoices.vehicles, setOf(input.vehicleType)) { change(input.copy(vehicleType = it)) }
        Text("출발지 검색·꼭 가고 싶은 곳 검색: 후속 기능입니다. 실제 위치·POI·좌표를 조회하지 않습니다.")
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (expanded) "선택 조건 접기 −" else "선택 조건 펼치기 +")
        }
        if (expanded) {
            ChoiceGroup("편도 최대 이동시간", listOf(60, 120, 180).associate { it.toString() to "${it / 60}시간 이내" }, setOf(input.maximumOneWayMinutes.toString())) {
                change(input.copy(maximumOneWayMinutes = it.toInt()))
            }
            ChoiceGroup("1인 예산 · 식비·체험비 기준", linkedMapOf("ANY" to "상관없음", "30000" to "3만 원 이하", "50000" to "5만 원 이하", "100000" to "10만 원 이하"), setOf(input.budgetPerPersonKrw?.toString() ?: "ANY")) {
                change(input.copy(budgetPerPersonKrw = it.toIntOrNull()))
            }
            ChoiceGroup("식사 취향 · 복수 선택", TripChoices.meals, input.mealPreferences) { change(input.copy(mealPreferences = input.mealPreferences.toggle(it))) }
            ChoiceGroup("걷는 정도", TripChoices.walking, setOf(input.walkingPreference)) { change(input.copy(walkingPreference = it)) }
            ChoiceGroup("주차 조건 · 복수 선택", TripChoices.parking, input.parkingPreferences) { change(input.copy(parkingPreferences = input.parkingPreferences.toggle(it))) }
            ChoiceGroup("편의시설·동반 · 복수 선택", TripChoices.amenities, input.amenities) { change(input.copy(amenities = input.amenities.toggle(it))) }
            ChoiceGroup("날씨 대응", mapOf("INDOOR" to "비 오면 실내 위주로"), if (input.preferIndoorWhenRaining) setOf("INDOOR") else emptySet()) {
                change(input.copy(preferIndoorWhenRaining = !input.preferIndoorWhenRaining))
            }
        }
        if (showErrors) errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { showErrors = true; submitted = errors.isEmpty() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("입력 확인") }
        if (submitted) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("선택한 조건 · AI 추천 연결 전", style = MaterialTheme.typography.titleLarge)
                    Text(input.summary())
                    Text("입력 조건만 확인했습니다. 일정 추천·귀가 가능성 검증·서버 요청은 수행하지 않았습니다.")
                }
            }
        }
    }
}

private fun Set<String>.toggle(value: String) = if (value in this) this - value else this + value

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceGroup(title: String, options: Map<String, String>, selected: Set<String>, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { (key, label) ->
                FilterChip(selected = key in selected, onClick = { onSelect(key) }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
}
