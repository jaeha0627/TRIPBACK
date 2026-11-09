package com.tripback.app.trip.replan

import com.tripback.app.trip.replan.ReplanFixtures.Server
import com.tripback.app.trip.replan.ReplanFixtures.TestClock
import com.tripback.app.trip.replan.ReplanFixtures.selection
import com.tripback.app.trip.replan.ReplanFixtures.observation
import com.tripback.app.trip.replan.ReplanFixtures.now
import com.tripback.app.trip.replan.ReplanFixtures.token
import com.tripback.app.trip.replan.ReplanFixtures.verified
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic loopback responses only; never uses a provider or an existing backend. */
class ReplanRegistrationDiagnosticTest {
    private suspend fun rejected(code: ReplanDiagnostic, configure: (Server) -> Unit) {
        Server().use { server ->
            configure(server)
            try {
                server.client().register(selection(), true)
                fail("Expected contract rejection")
            } catch (e: ReplanException) {
                assertEquals(ReplanError.INVALID_RESPONSE, e.error)
                assertEquals(code, e.diagnostic)
                assertTrue(e.registrationUnconfirmed)
                assertNull(e.cause)
                assertFalse(e.message.orEmpty().contains(token))
            }
            assertEquals(1, server.registrations.get())
            assertEquals(0, server.previews.get())
        }
    }

    @Test fun successfulRegistrationAndPreviewRemainUsable() = runBlocking {
        Server().use { server ->
            val client = server.client()
            val result = client.register(selection(), true)
            assertEquals(PlanRegistrationStatus.REGISTERED, result.status)
            assertEquals("PREVIEW_ONLY", client.preview(result.plan!!, observation()).status)
            assertEquals(1, server.registrations.get())
            assertEquals(1, server.previews.get())
        }
    }

    @Test fun responseAndBodyFailures() = runBlocking {
        rejected(ReplanDiagnostic.HTTP_RESPONSE) { it.type = "text/plain" }
        rejected(ReplanDiagnostic.BODY_SCHEMA) { it.registerBody = "{}" }
        rejected(ReplanDiagnostic.BODY_SCHEMA) {
            it.registerBody = it.registerBody.replaceFirst("{", "{\"notice\":\"synthetic\",")
        }
    }

    @Test fun headerAndExpiryFailures() = runBlocking {
        rejected(ReplanDiagnostic.HEADER_CONTRACT) { it.responseHeaders.remove("X-Tripback-Plan-Ref") }
        rejected(ReplanDiagnostic.HEADER_CONTRACT) {
            it.responseHeaders["X-Tripback-Plan-Ref"] = listOf(token, token)
        }
        rejected(ReplanDiagnostic.HEADER_CONTRACT) { it.responseHeaders["X-Tripback-Plan-Revision"] = listOf("2") }
        rejected(ReplanDiagnostic.EXPIRY_WINDOW) {
            it.responseHeaders["X-Tripback-Plan-Expires-At"] = listOf("invalid")
        }
        for (expiry in listOf(now, now.plusSeconds(1803))) {
            rejected(ReplanDiagnostic.EXPIRY_WINDOW) {
                it.responseHeaders["X-Tripback-Plan-Expires-At"] = listOf(expiry.toString())
            }
        }
    }

    @Test fun boundedClockSkewAndConservativeExpiryOverHttp() = runBlocking {
        // Server registration is fixed; only the Android clock differs (including measured skew).
        for (offsetMillis in listOf(-2000L, -1043L, -1000L, 0L, 1000L, 2000L)) {
            Server().use { server ->
                val clock = TestClock(now.plusMillis(offsetMillis))
                val plan = server.client(clock).register(selection(), true).plan!!
                assertEquals(now.plusSeconds(1800), plan.reference.expiresAt)
                assertEquals(now.plusSeconds(1798), plan.reference.localExpiresAt)
                assertTrue(plan.reference.isUsable(plan.reference.localExpiresAt.minusNanos(1)))
                // At actual server expiry even the slowest supported Android clock rejects it.
                assertFalse(plan.reference.isUsable(now.plusSeconds(1800).plusMillis(offsetMillis)))
            }
        }
    }

    @Test fun outsideSkewBudgetAndNearOrPastExpiryAreRejected() = runBlocking {
        // A server ahead by more than the budget produces an excessive apparent TTL.
        Server().use { server ->
            try {
                server.client(TestClock(now.minusMillis(2001))).register(selection(), true)
                fail("Expected skew rejection")
            } catch (e: ReplanException) {
                assertEquals(ReplanDiagnostic.EXPIRY_WINDOW, e.diagnostic)
            }
        }
        for (expiry in listOf(now.minusSeconds(1), now, now.plusSeconds(1), now.plusSeconds(2),
            now.plusSeconds(1802).plusNanos(1), now.plusSeconds(3600))) {
            rejected(ReplanDiagnostic.EXPIRY_WINDOW) {
                it.responseHeaders["X-Tripback-Plan-Expires-At"] = listOf(expiry.toString())
            }
        }
    }

    @Test fun explicitNonRegistrationIsNotReportedAsInvalidResponse() = runBlocking {
        for (status in listOf("DISABLED", "NOT_ELIGIBLE", "CAPACITY_EXCEEDED")) {
            Server().use { server ->
                server.responseHeaders.clear()
                server.responseHeaders["X-Tripback-Plan-Registration"] = listOf(status)
                val result = server.client().register(selection(), true)
                assertEquals(PlanRegistrationStatus.valueOf(status), result.status)
                assertNull(result.plan)
                assertEquals(1, server.registrations.get())
            }
        }
    }

    @Test fun snapshotCoordinateLimitAndCalculationFailures() = runBlocking {
        rejected(ReplanDiagnostic.SNAPSHOT_CONTRACT) {
            it.registerBody = it.registerBody.replace("FEASIBLE", "INFEASIBLE")
                .replace("\"safetyBufferSatisfied\":true", "\"safetyBufferSatisfied\":false")
        }
        rejected(ReplanDiagnostic.COORDINATES_CHANGED) {
            val point = verified().getValue("visits").jsonArray[0].jsonObject
                .getValue("confirmedPlace").jsonObject.getValue("coordinates").toString()
            it.registerBody = it.registerBody.replace(point, point.replace("36.0", "36.5"))
        }
        rejected(ReplanDiagnostic.ONE_WAY_LIMIT) {
            it.registerBody = it.registerBody.replace("\"durationSeconds\":600", "\"durationSeconds\":10860")
        }
        rejected(ReplanDiagnostic.CALCULATION_MISMATCH) {
            it.registerBody = it.registerBody.replace("\"totalTravelMinutes\":30", "\"totalTravelMinutes\":31")
        }
    }

    @Test fun sessionPreservesSanitizedRegistrationUncertainty() = runBlocking {
        Server().use { server ->
            server.registerBody = "{}"
            val session = ReplanPreviewSession(this, server.client(), TestClock())
            assertTrue(session.register(selection(), true))
            withTimeout(5000) {
                while (session.state.value.phase == ReplanRequestPhase.LOADING) delay(10)
            }
            assertEquals(ReplanDiagnostic.BODY_SCHEMA, session.state.value.diagnostic)
            assertTrue(session.state.value.registrationUnconfirmed)
            assertNull(session.state.value.registrationStatus)
            assertFalse(session.preview(observation()))
            session.close()
        }
    }
}
