package com.tripback.app.trip

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TripValidationSessionTest {
    private val fixture = TripValidationApiTest()
    @Test fun blocksDuplicateRequestsWhileLoading() = runBlocking {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val session = TripValidationSession(this) { request -> calls++; release.await(); TripValidationJson.response(fixture.fixture(request), request) }
        session.submit(fixture.input()); session.submit(fixture.input()); yield()
        assertEquals(1, calls); assertTrue(session.state.value.loading)
        release.complete(Unit); yield()
        assertFalse(session.state.value.loading); assertNotNull(session.state.value.result)
    }
    @Test fun editingOrLeavingInvalidatesLateResponsesWithoutOverwritingNewResult() = runBlocking {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val session = TripValidationSession(this) { request ->
            calls++
            if (calls == 1) withContext(NonCancellable) { release.await() }
            TripValidationJson.response(fixture.fixture(request), request)
        }
        session.submit(fixture.input()); yield(); session.cancel()
        assertNull(session.state.value.result); assertFalse(session.state.value.loading)
        session.submit(fixture.input(3)); yield()
        assertEquals(3, session.state.value.result!!.visits.size)
        release.complete(Unit); yield(); yield()
        assertEquals(3, session.state.value.result!!.visits.size)
        session.cancel(); assertNull(session.state.value.result)
    }
    @Test fun newFailedRequestDoesNotKeepPriorSuccessAndCancelClearsError() = runBlocking {
        var fail = false
        val session = TripValidationSession(this) { request ->
            if (fail) throw ValidationHttpException(404)
            TripValidationJson.response(fixture.fixture(request), request)
        }
        session.submit(fixture.input()); yield(); assertNotNull(session.state.value.result)
        fail = true; session.submit(fixture.input()); assertNull(session.state.value.result); yield()
        assertNull(session.state.value.result); assertTrue(session.state.value.error!!.contains("비활성"))
        session.cancel(); assertNull(session.state.value.error)
    }
}
