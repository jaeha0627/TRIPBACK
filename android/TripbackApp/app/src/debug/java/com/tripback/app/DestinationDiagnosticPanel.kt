package com.tripback.app

import ai.pleos.playground.navi.data.DestinationInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

internal const val DESTINATION_DIAGNOSTIC_UI_VERSION = "DEST_DIAG_UI_V2"

/** Observable callback-to-UI boundary. Retains comparison categories only; no request/prefs access. */
internal class DestinationDiagnosticUiState {
    var diagnostic by mutableStateOf<DestinationDiagnostic?>(null)
        private set

    fun accept(info: DestinationInfo): DestinationDiagnostic = diagnoseDestination(
        latitude = info.latitude, longitude = info.longitude, poiId = info.poiId,
        poiSubId = info.poiSubId, name = info.name,
        displayLatitude = info.displayLatitude, displayLongitude = info.displayLongitude
    ).also { diagnostic = it }
}

@Composable
internal fun DestinationDiagnosticPanel(state: DestinationDiagnosticUiState) {
    Column(Modifier.testTag("destinationDiagnosticPanel")) {
        Text(DESTINATION_DIAGNOSTIC_UI_VERSION)
        val d = state.diagnostic
        if (d == null) {
            Text("목적지 진단 응답 대기 · 읽기 전용 조회로 확인하세요")
        } else {
            Text("VERIFIED_LIVE · 목적지 비교: ${d.summary}", Modifier.testTag("destinationSummary"))
            Text("POI ID: ${d.poiId}")
            Text("POI Sub ID: ${d.poiSubId}")
            Text("latitude: ${d.latitude}")
            Text("longitude: ${d.longitude}")
            Text("namePresent: ${d.namePresent} / nameExact: ${d.nameExact}")
            Text("displayLatitude: ${d.displayLatitude}")
            Text("displayLongitude: ${d.displayLongitude}")
        }
    }
}
