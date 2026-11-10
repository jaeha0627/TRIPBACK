package com.tripback.app.trip.replan

import com.tripback.app.trip.replan.ReplanFixtures.Server
import com.tripback.app.trip.replan.ReplanFixtures.TestClock
import com.tripback.app.trip.replan.ReplanFixtures.now
import com.tripback.app.trip.replan.ReplanFixtures.observation
import com.tripback.app.trip.replan.ReplanFixtures.preview
import com.tripback.app.trip.replan.ReplanFixtures.registered
import com.tripback.app.trip.replan.ReplanFixtures.selection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset

/** Synthetic loopback HTTP only; backend calculation time and Android clock are independent. */
class ReplanPreviewClockTest {
    private suspend fun rejected(error: ReplanError, block: suspend () -> Unit) {
        try { block(); fail("Expected time contract rejection") }
        catch (e: ReplanException) { assertEquals(error, e.error) }
    }

    @Test fun serverCalculationAllowsBoundedClientClockSkew() = runBlocking {
        for (offsetMillis in listOf(-2000L, -1043L, -1000L, 0L, 1000L, 2000L)) {
            Server().use { server ->
                // An observation before server receipt is valid for either clock direction.
                val observed = observation().copy(observedAt = now.minusSeconds(5).atOffset(ZoneOffset.UTC))
                val client = server.client(TestClock(now.plusMillis(offsetMillis)))
                val plan = client.register(selection(), true).plan!!
                server.previewBody = preview(plan, observed, now).toString()
                val result = client.preview(plan, observed)
                assertEquals(now, result.calculatedAt)
                assertEquals("PREVIEW_ONLY", result.status)
                assertEquals(1, server.previews.get())
            }
        }
    }

    @Test fun calculationBeyondTwoSecondsIsRejected() = runBlocking {
        Server().use { server ->
            val plan = registered(server = server.base)
            val client = server.client(TestClock(now.minusMillis(2001)))
            rejected(ReplanError.INVALID_RESPONSE) { client.preview(plan, observation()) }
            assertEquals(1, server.previews.get())
        }
    }

    @Test fun observationWindowIsStillExactlyZeroThroughSixtySeconds() = runBlocking {
        for (lagMillis in listOf(-1L, 0L, 60000L, 60001L)) {
            Server().use { server ->
                val plan = registered(server = server.base)
                val observed = observation().copy(observedAt = now.minusMillis(lagMillis).atOffset(ZoneOffset.UTC))
                server.previewBody = preview(plan, observed, now).toString()
                val client = server.client(TestClock(now.minusSeconds(2)))
                if (lagMillis in 0L..60000L) assertEquals("PREVIEW_ONLY", client.preview(plan, observed).status)
                else rejected(ReplanError.INVALID_RESPONSE) { client.preview(plan, observed) }
            }
        }
    }

    @Test fun localEarlyExpiryAndServerExpiryRemainStrict() = runBlocking {
        for (localAt in listOf(now.plusSeconds(1798), now.plusSeconds(1800))) {
            Server().use { server ->
                val plan = registered(server = server.base)
                rejected(ReplanError.PLAN_NOT_FOUND_OR_EXPIRED) {
                    server.client(TestClock(localAt)).preview(plan, observation())
                }
                assertEquals(0, server.previews.get())
            }
        }
        // Still locally usable, but calculatedAt at/after server expiry must be rejected.
        for (calculated in listOf(now.plusSeconds(1800), now.plusSeconds(1800).plusNanos(1))) {
            Server().use { server ->
                val plan = registered(server = server.base)
                val observed = observation().copy(observedAt = calculated.minusSeconds(5).atOffset(ZoneOffset.UTC))
                server.previewBody = preview(plan, observed, calculated).toString()
                rejected(ReplanError.INVALID_RESPONSE) {
                    server.client(TestClock(now.plusSeconds(1798).minusNanos(1))).preview(plan, observed)
                }
                assertEquals(1, server.previews.get())
            }
        }
    }

    @Test fun backendFutureObservationErrorIsNotBypassedOrRetried() = runBlocking {
        Server().use { server ->
            server.status = 400
            server.previewBody = "{\"code\":\"INVALID_OBSERVATION_TIME\"}"
            rejected(ReplanError.INVALID_OBSERVATION_TIME) {
                server.client(TestClock(now.plusSeconds(2))).preview(registered(server = server.base), observation())
            }
            assertEquals(1, server.previews.get())
        }
    }
}
