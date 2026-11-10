package com.tripback.app.trip.replan

import java.time.Clock
import org.junit.Assert.*
import org.junit.Test

class ReplanRepositoryModeTest {
    @Test fun recommendationModeExplicitlySelectsRepositoryWithoutSendingRequests() {
        // Constructors only: no server, provider, coroutine, or registration is started.
        assertTrue(replanRepository(true, Clock.systemUTC()) is DebugReplanHttp)
        val fake = replanRepository(false, Clock.systemUTC()) as FakeReplanUiRepository
        assertEquals(0, fake.registrationCalls)
        assertEquals(0, fake.previewCalls)
    }
}
