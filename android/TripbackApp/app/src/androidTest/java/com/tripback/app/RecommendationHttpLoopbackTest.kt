package com.tripback.app

import androidx.test.platform.app.InstrumentationRegistry
import com.tripback.app.trip.recommendation.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in only, against a keyless local backend started by the developer. */
class RecommendationHttpLoopbackTest {
    @Test fun keylessBackendThroughAdbReverse() = runBlocking {
        val expected=InstrumentationRegistry.getArguments().getString("keylessBackendExpected")
        assumeTrue("Requires explicitly isolated keyless backend",expected in setOf("404","AI_UNAVAILABLE"))
        val request=RecommendationForm(parked=true,date="2026-11-07").request()
        try {
            val raw=HttpRecommendationRepository().recommend(request,FakeScenario.TWO)
            assertEquals("AI_UNAVAILABLE",expected)
            val response=RecommendationJson.response(raw,request)
            assertEquals(expected,response.recommendationStatus)
            assertEquals("NOT_CONFIGURED",response.aiProviderStatus.status)
            assertEquals("NOT_CALLED",response.aiProviderStatus.invocation)
            assertTrue(response.alternatives.isEmpty())
            assertEquals(RecommendationCalls(0,0,0),response.calls)
        } catch(failure:RecommendationHttpException) {
            assertEquals("404",expected);assertEquals(404,failure.status)
        }
    }
}
