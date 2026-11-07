package com.tripback.app.trip.replan

import com.tripback.app.trip.replan.ReplanFixtures.Server
import com.tripback.app.trip.replan.ReplanFixtures.TestClock
import com.tripback.app.trip.replan.ReplanFixtures.selection
import com.tripback.app.trip.replan.ReplanFixtures.token
import com.tripback.app.trip.replan.ReplanFixtures.headers
import com.tripback.app.trip.replan.ReplanFixtures.registered
import com.tripback.app.trip.replan.ReplanFixtures.observation
import com.tripback.app.trip.replan.ReplanFixtures.now
import com.tripback.app.trip.replan.ReplanFixtures.input
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ReplanHttpTest {
    private suspend fun failure(expected: ReplanError, block: suspend () -> Unit) {
        try { block(); fail("Expected transport failure") }
        catch (e: ReplanException) { assertEquals(expected, e.error); assertFalse(e.message.orEmpty().contains(token)) }
    }
    @Test fun explicitRegistrationAndPreviewMakeOnePostEach() = runBlocking {
        Server().use { server ->
            val client = server.client()
            val result = client.register(selection(), true)
            assertNotNull(result.plan); assertEquals("true", server.registrationHeader.get())
            assertEquals(1, server.registrations.get())
            val parsed = client.preview(result.plan!!, observation())
            assertEquals("PREVIEW_ONLY", parsed.status); assertEquals(1, server.previews.get())
            assertNull(server.registrationHeader.get()); assertEquals(1, server.registrations.get())
        }
    }
    @Test fun absentExplicitConsentMakesNoRequest() = runBlocking {
        Server().use { server ->
            failure(ReplanError.EXPLICIT_REQUEST_REQUIRED) { server.client().register(selection(), false) }
            assertEquals(0, server.registrations.get())
        }
    }
    @Test fun malformedHeadersAndChangedSnapshotDoNotProducePlans() = runBlocking {
        Server().use { server ->
            server.responseHeaders.remove("X-Tripback-Plan-Ref")
            failure(ReplanError.INVALID_RESPONSE) { server.client().register(selection(), true) }
            server.responseHeaders.putAll(headers())
            server.registerBody = server.registerBody.replace("\"stayMinutes\":30", "\"stayMinutes\":31")
            failure(ReplanError.INVALID_RESPONSE) { server.client().register(selection(), true) }
        }
    }
    @Test fun reference404InvalidatesWithoutAutomaticRegistration() = runBlocking {
        Server().use { server ->
            val client = server.client(); val plan = client.register(selection(), true).plan!!
            server.status = 404; server.previewBody = "{\"code\":\"PLAN_NOT_FOUND_OR_EXPIRED\"}"
            failure(ReplanError.PLAN_NOT_FOUND_OR_EXPIRED) { client.preview(plan, observation()) }
            failure(ReplanError.PLAN_NOT_FOUND_OR_EXPIRED) { client.preview(plan, observation()) }
            assertEquals(1, server.previews.get()); assertEquals(1, server.registrations.get())
        }
    }
    @Test fun revision409InvalidatesReferenceAndPreservesOriginalPlan() = runBlocking {
        Server().use { server ->
            val client = server.client(); val plan = client.register(selection(), true).plan!!
            val before = plan.validation
            server.status = 409; server.previewBody = "{\"code\":\"PLAN_REVISION_MISMATCH\"}"
            failure(ReplanError.PLAN_REVISION_MISMATCH) { client.preview(plan, observation()) }
            assertSame(before, plan.validation); assertFalse(plan.reference.isUsable(now))
        }
    }
    @Test fun disabled404IsDifferentFromExpiredReference() = runBlocking {
        Server().use { server ->
            server.status = 404; server.previewBody = "{\"status\":404}"
            failure(ReplanError.API_NOT_AVAILABLE) { server.client().preview(registered(server = server.base), observation()) }
        }
    }
    @Test fun known400And403AreNotRetried() = runBlocking {
        for ((status, code) in listOf(400 to ReplanError.INVALID_OBSERVATION_TIME, 403 to ReplanError.LOCAL_DEVELOPMENT_ONLY)) {
            Server().use { server ->
                server.status = status; server.previewBody = "{\"code\":\"${code.name}\"}"
                failure(code) { server.client().preview(registered(server = server.base), observation()) }
                assertEquals(1, server.previews.get()); assertEquals(0, server.registrations.get())
            }
        }
    }
    @Test fun expiredLocalReferenceNeverReachesServer() = runBlocking {
        Server().use { server ->
            val clock = TestClock(now.plusSeconds(1800))
            failure(ReplanError.PLAN_NOT_FOUND_OR_EXPIRED) { server.client(clock).preview(registered(server = server.base), observation()) }
            assertEquals(0, server.previews.get()); assertEquals(0, server.registrations.get())
        }
    }
    @Test fun wrongJsonAndContentTypeAreRejected() = runBlocking {
        Server().use { server ->
            server.previewBody = "{bad-json}"
            failure(ReplanError.INVALID_RESPONSE) { server.client().preview(registered(server = server.base), observation()) }
            server.type = "text/html"
            failure(ReplanError.INVALID_RESPONSE) { server.client().preview(registered(server = server.base), observation()) }
        }
    }
    @Test fun redirectsAndUnknownErrorsNeverLeakBodyOrRetry() = runBlocking {
        Server().use { server ->
            server.status = 302; server.previewBody = "sensitive-error-marker"
            failure(ReplanError.HTTP_ERROR) { server.client().preview(registered(server = server.base), observation()) }
            assertEquals(1, server.previews.get()); assertEquals(0, server.registrations.get())
        }
    }
    @Test fun malformedUtf8IsRejectedWithoutEchoingResponse() = runBlocking {
        Server().use { server ->
            server.previewBytes = byteArrayOf(0xc3.toByte(), 0x28)
            failure(ReplanError.INVALID_RESPONSE) { server.client().preview(registered(server = server.base), observation()) }
        }
    }
    @Test fun referenceExpiringDuringRequestCannotReturnReadyPreview() = runBlocking {
        Server().use { server ->
            val clock = TestClock(); val client = server.client(clock); server.stalled = true
            val job = async {
                failure(ReplanError.PLAN_NOT_FOUND_OR_EXPIRED) { client.preview(registered(server = server.base), observation()) }
            }
            withContext(Dispatchers.IO) { assertTrue(server.entered.await(2, TimeUnit.SECONDS)) }
            clock.at = now.plusSeconds(1800); server.release.countDown(); job.await()
            assertEquals(1, server.previews.get()); assertEquals(0, server.registrations.get())
        }
    }
    @Test fun concurrentRepositoryRequestsAreRejectedWithoutSecondPost() = runBlocking {
        Server().use { server ->
            val client = server.client(); server.stalled = true
            val job = async { client.preview(registered(server = server.base), observation()) }
            withContext(Dispatchers.IO) { assertTrue(server.entered.await(2, TimeUnit.SECONDS)) }
            try { client.preview(registered(server = server.base), observation()); fail("Expected duplicate guard") }
            catch (_: IllegalStateException) { }
            server.release.countDown(); job.await(); assertEquals(1, server.previews.get())
        }
    }
    @Test fun declaredAndChunkedOversizedResponsesAreBounded() = runBlocking {
        for (chunked in listOf(false, true)) Server().use { server ->
            server.previewBody = " ".repeat(2048); server.chunked = chunked
            failure(ReplanError.INVALID_RESPONSE) { server.client(limit = 1024).preview(registered(server = server.base), observation()) }
        }
    }
    @Test fun totalTimeoutIsBoundedAndDoesNotRetry() = runBlocking {
        Server().use { server ->
            server.stalled = true
            failure(ReplanError.TIMEOUT) { server.client(read = 2000, total = 200).preview(registered(server = server.base), observation()) }
            assertEquals(1, server.previews.get()); assertEquals(0, server.registrations.get())
        }
    }
    @Test fun cancellationDoesNotReturnALatePreview() = runBlocking {
        Server().use { server ->
            server.stalled = true
            val job = async { server.client().preview(registered(server = server.base), observation()) }
            withContext(Dispatchers.IO) { assertTrue(server.entered.await(2, TimeUnit.SECONDS)) }
            withTimeout(1000) { job.cancelAndJoin() }
            server.release.countDown(); assertTrue(job.isCancelled)
            assertEquals(1, server.previews.get()); assertEquals(0, server.registrations.get())
        }
    }
    @Test fun previewCannotSendTokenToAnotherServer() = runBlocking {
        Server().use { server ->
            try { server.client().preview(registered(), observation()); fail("Origin binding expected") }
            catch (_: IllegalArgumentException) { }
            assertEquals(0, server.previews.get())
        }
    }
    @Test fun nonLoopbackAddressesCredentialsAndPathsAreRejected() {
        for (base in listOf("http://10.0.2.2:8080", "http://example.com", "https://example.com", "http://user@127.0.0.1:8080",
            "http://127.0.0.1:8080/path", "http://127.0.0.1:8080?secret=x")) {
            try { DebugReplanHttp(base); fail("Expected local-only endpoint") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun repeatedPreviewsKeepSelectionAndValidationUnchanged() = runBlocking {
        Server().use { server ->
            val selected = selection(); val saved = selected.plan
            val client = server.client(); val plan = client.register(selected, true).plan!!
            val validation = plan.validation
            repeat(3) { client.preview(plan, observation()) }
            assertSame(saved, selected.plan); assertSame(validation, plan.validation)
            assertEquals(3, server.previews.get()); assertEquals(1, server.registrations.get())
            assertEquals(input.visits.map { it.stayMinutes }, validation.visits.map { it.stayMinutes })
        }
    }
}
