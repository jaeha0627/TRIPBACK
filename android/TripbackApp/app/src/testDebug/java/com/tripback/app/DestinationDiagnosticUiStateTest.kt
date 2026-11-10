package com.tripback.app

import ai.pleos.playground.navi.data.DestinationInfo
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import org.junit.Assert.*
import org.junit.Test

class DestinationDiagnosticUiStateTest {
    private fun response(id: String = "synthetic-private-id", sub: String? = null) = DestinationInfo(
        latitude = 0.0, longitude = 0.0, name = "synthetic-private-name",
        address = "synthetic-private-address", remainDistance = 0, remainTime = 0,
        tollFare = 0, poiId = id, poiSubId = sub
    )
    @Test fun destinationCallbackInvalidatesObservedUiWithoutOtherStateWrites() {
        val state = DestinationDiagnosticUiState()
        var changed = false
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads(Unit, { _: Unit -> changed = true }) { assertNull(state.diagnostic) }
            val d = state.accept(response())
            Snapshot.sendApplyNotifications()
            assertTrue(changed)
            assertSame(d, state.diagnostic)
            assertEquals(DestinationComparison.INSUFFICIENT_DATA, d.summary)
            assertEquals(PoiIdComparison.DIFFERENT, d.poiId)
            assertEquals(SubIdComparison.NULL, d.poiSubId)
        } finally { observer.stop(); observer.clear() }
    }
    @Test fun subsequentResponseUpdatesStateAndSafeEventPayload() {
        val state = DestinationDiagnosticUiState()
        state.accept(response())
        val d = state.accept(response(id = "", sub = ""))
        assertEquals(PoiIdComparison.EMPTY, state.diagnostic!!.poiId)
        assertEquals(SubIdComparison.EMPTY, state.diagnostic!!.poiSubId)
        assertTrue(d.comparisonLine().contains("summary=INSUFFICIENT_DATA"))
        for (raw in listOf("synthetic-private-id", "synthetic-private-name", "synthetic-private-address")) {
            assertFalse(d.comparisonLine().contains(raw))
        }
    }
}
