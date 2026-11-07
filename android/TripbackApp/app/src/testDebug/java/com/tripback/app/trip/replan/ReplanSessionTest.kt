package com.tripback.app.trip.replan

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.*

class ReplanSessionTest {
    private class Fake : DebugReplanRepository {
        var registrations = 0; var previews = 0
        var holdRegister = false; var holdPreview = false
        var failure: ReplanError? = null
        var pendingRegistration: Continuation<ReplanRegistration>? = null
        var pendingPreview: Continuation<ReplanPreview>? = null
        fun registration(): ReplanRegistration {
            val plan = ReplanFixtures.registered()
            return ReplanRegistration(PlanRegistrationStatus.REGISTERED, plan.validation, plan)
        }
        fun result() = ReplanJson.preview(ReplanFixtures.preview(ReplanFixtures.registered()).toString(),
            ReplanFixtures.registered(), ReplanFixtures.observation(), ReplanFixtures.now)
        override suspend fun register(selection: ReplanSelection, explicitlyRequested: Boolean): ReplanRegistration {
            registrations++
            return if (holdRegister) suspendCoroutine { pendingRegistration = it } else registration()
        }
        override suspend fun preview(plan: RegisteredReplanPlan, observation: ReplanObservation): ReplanPreview {
            previews++; failure?.let { throw ReplanException(it) }
            return if (holdPreview) suspendCoroutine { pendingPreview = it } else result()
        }
    }
    private fun run(block: (ReplanPreviewSession, Fake, ReplanFixtures.TestClock) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val fake = Fake(); val clock = ReplanFixtures.TestClock()
        val session = ReplanPreviewSession(scope, fake, clock)
        try { block(session, fake, clock) } finally { session.close(); scope.cancel() }
    }
    @Test fun idleRegistrationAndPreviewStatesAreSeparate() = run { session, fake, _ ->
        assertEquals(ReplanRequestPhase.IDLE, session.state.value.phase)
        assertFalse(session.preview(ReplanFixtures.observation())); assertEquals(0, fake.registrations)
        session.register(ReplanFixtures.selection(), true)
        assertEquals(ReplanRequestPhase.IDLE, session.state.value.phase)
        assertEquals(PlanRegistrationStatus.REGISTERED, session.state.value.registrationStatus)
        session.preview(ReplanFixtures.observation())
        assertEquals(ReplanRequestPhase.PREVIEW_READY, session.state.value.phase)
        assertEquals(7, session.state.value.preview!!.localRevision)
    }
    @Test fun noImplicitRegistrationOrDuplicateRequest() = run { session, fake, _ ->
        assertFalse(session.register(ReplanFixtures.selection(), false)); assertEquals(0, fake.registrations)
        fake.holdRegister = true
        assertTrue(session.register(ReplanFixtures.selection(), true))
        assertEquals(ReplanRequestPhase.LOADING, session.state.value.phase)
        assertFalse(session.register(ReplanFixtures.selection(), true)); assertFalse(session.preview(ReplanFixtures.observation()))
        assertEquals(1, fake.registrations)
        fake.pendingRegistration!!.resume(fake.registration())
    }
    @Test fun cancelDiscardsLateRegistrationAndItsToken() = run { session, fake, _ ->
        fake.holdRegister = true; session.register(ReplanFixtures.selection(), true)
        session.cancel(); val late = fake.registration(); fake.pendingRegistration!!.resume(late)
        assertEquals(ReplanRequestPhase.IDLE, session.state.value.phase)
        assertFalse(session.preview(ReplanFixtures.observation()))
        assertFalse(late.plan!!.reference.isUsable(ReplanFixtures.now))
    }
    @Test fun oldPreviewCannotReplaceNewContext() = run { session, fake, _ ->
        session.register(ReplanFixtures.selection(), true); fake.holdPreview = true
        session.preview(ReplanFixtures.observation())
        session.invalidateContext(); fake.pendingPreview!!.resume(fake.result())
        assertEquals(ReplanRequestPhase.IDLE, session.state.value.phase); assertNull(session.state.value.preview)
        assertEquals(1, fake.registrations)
    }
    @Test fun fixedClockExpiryClearsReferenceAndNeverRegistersAgain() = run { session, fake, clock ->
        session.register(ReplanFixtures.selection(), true)
        clock.at = ReplanFixtures.now.plusSeconds(1800); session.checkExpiry()
        assertEquals(ReplanRequestPhase.EXPIRED, session.state.value.phase)
        assertFalse(session.preview(ReplanFixtures.observation())); assertEquals(1, fake.registrations); assertEquals(0, fake.previews)
    }
    @Test fun serverRestart404ExpiresWithoutRetry() = run { session, fake, _ ->
        session.register(ReplanFixtures.selection(), true); fake.failure = ReplanError.PLAN_NOT_FOUND_OR_EXPIRED
        session.preview(ReplanFixtures.observation())
        assertEquals(ReplanRequestPhase.EXPIRED, session.state.value.phase)
        assertFalse(session.preview(ReplanFixtures.observation())); assertEquals(1, fake.previews); assertEquals(1, fake.registrations)
    }
    @Test fun conflictStopsUseAndCannotAutoRegister() = run { session, fake, _ ->
        session.register(ReplanFixtures.selection(), true); fake.failure = ReplanError.PLAN_REVISION_MISMATCH
        session.preview(ReplanFixtures.observation())
        assertEquals(ReplanRequestPhase.ERROR, session.state.value.phase)
        assertEquals(ReplanError.PLAN_REVISION_MISMATCH, session.state.value.error)
        assertFalse(session.preview(ReplanFixtures.observation())); assertEquals(1, fake.registrations)
    }
    @Test fun networkFailurePreservesBaselineButDoesNotRetry() = run { session, fake, _ ->
        session.register(ReplanFixtures.selection(), true); fake.failure = ReplanError.NETWORK
        session.preview(ReplanFixtures.observation())
        assertEquals(ReplanRequestPhase.ERROR, session.state.value.phase)
        assertEquals(1, fake.previews); assertEquals(1, fake.registrations)
    }
}
