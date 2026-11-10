package com.tripback.app.trip.progress

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable fun DrivingSafetyGate(state: DrivingSafety, content: @Composable () -> Unit) {
    val holder=androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    if(state.canInteract) holder.SaveableStateProvider("tripback-content") { content() } else Column(Modifier.fillMaxSize().padding(32.dp).testTag("drivingRestricted"),
        verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("안전하게 정차한 후 이용해 주세요")
        Text(state.message)
        Text("입력·일정 편집·복잡한 선택을 잠갔습니다. 주차 체크박스로 해제할 수 없습니다.")
    }
}
