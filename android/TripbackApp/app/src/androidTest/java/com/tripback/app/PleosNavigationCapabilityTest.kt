package com.tripback.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.tripback.app.trip.recommendation.PleosNavigationAdapter
import org.junit.Assert.*
import org.junit.Test

class PleosNavigationCapabilityTest {
    @Test fun realSdkConnectionKeepsUnverifiedCoordinateRouteDisabled() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val adapter=PleosNavigationAdapter(instrumentation.targetContext)
        try {
            instrumentation.runOnMainSync { adapter.connect() }
            val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(25)
            while(System.nanoTime()<deadline && adapter.capability.value.message.contains("연결 중")) Thread.sleep(100)
            Log.i("TripbackCapability", "SDK 3.0.5: ${adapter.capability.value}; ${adapter.observation.value}")
            assertFalse(adapter.capability.value.supported)
            // Connection result is evidence, never a route-search success assertion.
            assertFalse(adapter.capability.value.message.contains("연결 중"))
        } finally { instrumentation.runOnMainSync { adapter.close() } }
    }
}
