package com.tripback.app.trip.replan

import com.tripback.app.trip.*
import com.tripback.app.trip.progress.*
import com.tripback.app.trip.recommendation.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import kotlin.coroutines.*

class ReplanUiFixtureTest {
    private class Harness(case: ReplanFixtureCase = ReplanFixtureCase.NORMAL,
        repository: DebugReplanRepository? = null) : AutoCloseable {
        var monotonic = 1000L
        val fixture = ReplanUiFixture.create(case, ReplanFixtureClock { monotonic })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val fake = FakeReplanUiRepository(fixture.clock, 0)
        val controller = ReplanDebugController(scope, fixture.journey, fixture.request, fixture.raw,
            fixture.plan.planId, repository ?: fake, fixture.clock)
        fun advance(seconds: Long) { monotonic += seconds * 1000 }
        override fun close() { controller.close(); scope.cancel() }
    }
    @Test fun fixtureStartsAtThirtyMinutesWithTenElapsedAndNoRequests() {
        Harness().use { h ->
            val view = h.controller.view()
            assertEquals(JourneyPhase.STAYING, view.phase); assertEquals(30L, view.originalStayMinutes)
            assertEquals(600L, view.elapsedSeconds); assertNull(view.blockedReason)
            assertTrue(view.canRegister); assertFalse(view.canPreview)
            assertEquals(0, h.fake.registrationCalls); assertEquals(0, h.fake.previewCalls)
            assertTrue(h.fixture.plan.planId.startsWith(REPLAN_FIXTURE_PLAN_PREFIX))
        }
    }
    @Test fun backendFixtureArithmeticIsReproducedWithoutCountingCompletedIncomingLeg() {
        Harness().use { h ->
            assertFalse(h.controller.register(false)); assertFalse(h.controller.preview())
            assertTrue(h.controller.register(true)); h.controller.changeTotal(50); assertTrue(h.controller.preview())
            val result = h.controller.view().requestState.preview!!
            assertEquals(20L, result.before.currentRemainingStayMinutes); assertEquals(40L, result.after.currentRemainingStayMinutes)
            assertEquals(ReplanFixtureClock.START.plusSeconds(70 * 60), result.before.predictedReturnAt!!.toInstant())
            assertEquals(ReplanFixtureClock.START.plusSeconds(90 * 60), result.after.predictedReturnAt!!.toInstant())
            assertEquals(ValidationFeasibility.FEASIBLE, result.after.feasibility)
            assertEquals(listOf(1, 2, 3), result.after.remainingLegs.map { it.originalLegIndex })
            assertEquals(listOf(5L, 7L, 8L), result.after.remainingLegs.map { it.minutes })
            assertTrue(result.after.remainingLegs.all { it.dataMode == ValidationDataMode.DEMO_SIMULATED && it.verification == "UNVERIFIED" })
        }
    }
    @Test fun bufferShortfallCaseGoesFromFeasibleToInfeasibleWithoutApplying() {
        Harness(ReplanFixtureCase.BUFFER_SHORTFALL).use { h ->
            val before = h.fixture.journey.snapshot()
            h.controller.register(true); h.controller.changeTotal(50); h.controller.preview()
            val result = h.controller.view().requestState.preview!!
            assertEquals(ValidationFeasibility.FEASIBLE, result.before.feasibility)
            assertEquals(ValidationFeasibility.INFEASIBLE, result.after.feasibility)
            assertTrue(result.after.returnDeadlineMet!!); assertFalse(result.after.safetyBufferSatisfied!!)
            assertEquals(15L, result.after.remainingMarginMinutes)
            assertEquals(before, h.fixture.journey.snapshot())
        }
    }
    @Test fun staleFixtureShowsUnknownWithOriginalSchedulePreserved() {
        Harness().use { h ->
            val before = h.fixture.journey.snapshot()
            h.controller.register(true); h.controller.changeTotal(50); h.controller.scenario(ReplanUiScenario.STALE); h.controller.preview()
            val result = h.controller.view().requestState.preview!!
            assertEquals(ValidationFeasibility.UNKNOWN, result.after.feasibility)
            assertTrue("STALE_ETA" in result.after.reasons); assertEquals(before, h.fixture.journey.snapshot())
        }
    }
    @Test fun expiryServerErrorAndConflictAreSelectableWithoutAutomaticRequests() {
        for ((scenario, expected) in listOf(ReplanUiScenario.EXPIRED to ReplanError.PLAN_NOT_FOUND_OR_EXPIRED,
            ReplanUiScenario.SERVER_ERROR to ReplanError.INTERNAL_ERROR,
            ReplanUiScenario.REVISION_CONFLICT to ReplanError.PLAN_REVISION_MISMATCH)) {
            Harness().use { h ->
                h.controller.register(true); h.controller.scenario(scenario)
                assertEquals(0, h.fake.previewCalls)
                h.controller.preview()
                assertEquals(expected, h.controller.view().requestState.error)
                assertNull(h.controller.view().requestState.preview); assertEquals(1, h.fake.registrationCalls)
                if (scenario != ReplanUiScenario.SERVER_ERROR) assertFalse(h.controller.preview())
            }
        }
    }
    @Test fun fixtureClockMaintainsFifteenAndThirtyMinuteLimits() {
        Harness().use { h ->
            h.controller.register(true); h.controller.changeTotal(50); h.advance(901); h.controller.preview()
            assertEquals(ValidationFeasibility.UNKNOWN, h.controller.view().requestState.preview!!.after.feasibility)
            h.advance(899)
            assertEquals(ReplanRequestPhase.EXPIRED, h.controller.view().requestState.phase)
            assertFalse(h.controller.preview()); assertEquals(1, h.fake.registrationCalls)
        }
    }
    @Test fun closingFixtureDoesNotChangeItOrIndependentOrdinaryDemo() {
        Harness().use { h ->
            val r = h.fixture.request.copy(mustVisitPlaceIds = emptyList())
            val raw = RecommendationFixtures.response(r, FakeScenario.ONE)
            val plan = RecommendationJson.response(raw, r).alternatives.single()
            val ordinary = TripJourney(plan, r, h.fixture.clock)
            val ordinaryBefore = ordinary.snapshot(); val fixtureBefore = h.fixture.journey.snapshot()
            assertNotSame(ordinary, h.fixture.journey)
            h.controller.register(true); h.controller.changeTotal(50); h.controller.preview(); h.controller.dismiss(); h.controller.close()
            assertEquals(ordinaryBefore, ordinary.snapshot()); assertEquals(fixtureBefore, h.fixture.journey.snapshot())
            assertEquals(JourneyPhase.READY, ordinary.state.value.phase)
            val normalController = ReplanDebugController(h.scope, ordinary, r, raw, plan.planId, h.fake, h.fixture.clock)
            try { assertFalse(normalController.register(true)); assertNotNull(normalController.view().blockedReason) }
            finally { normalController.close() }
        }
    }
    @Test fun recreatedFixtureHasNoReferenceAndNeverAutoRegisters() {
        Harness().use { h -> h.controller.register(true); h.controller.preview() }
        Harness().use { h ->
            assertNull(h.controller.view().requestState.expiresAt); assertFalse(h.controller.preview())
            assertEquals(0, h.fake.registrationCalls)
        }
    }
    @Test fun fixtureSelectionCannotReachRealHttpRegistrationEvenWhenExplicit() = runBlocking {
        ReplanFixtures.Server().use { server ->
            val fixture = ReplanUiFixture.create(clock = ReplanFixtureClock { 1000L })
            val selection = ReplanSelection.fromRecommendation(fixture.raw, fixture.request, fixture.plan.planId)
            try { server.client().register(selection, true); fail("Runtime fixture must never be registered over HTTP") }
            catch (e: ReplanException) { assertEquals(ReplanError.SELECTION_MISMATCH, e.error) }
            assertEquals(0, server.registrations.get()); assertEquals(0, server.previews.get())
        }
    }
    private class Held : DebugReplanRepository {
        var calls = 0
        var continuation: Continuation<ReplanRegistration>? = null
        override suspend fun register(selection: ReplanSelection, explicitlyRequested: Boolean): ReplanRegistration {
            calls++; return suspendCoroutine { continuation = it }
        }
        override suspend fun preview(plan: RegisteredReplanPlan, observation: ReplanObservation): ReplanPreview = error("No preview authorized")
    }
    @Test fun duplicateAndCancelledFixtureRegistrationCannotInstallLateReference() {
        val held = Held()
        Harness(repository = held).use { h ->
            assertTrue(h.controller.register(true)); assertFalse(h.controller.register(true)); assertEquals(1, held.calls)
            val before = h.fixture.journey.snapshot(); h.controller.dismiss()
            val selection = ReplanSelection.fromRecommendation(h.fixture.raw, h.fixture.request, h.fixture.plan.planId)
            val late = runBlocking { FakeReplanUiRepository(h.fixture.clock, 0).register(selection, true) }
            held.continuation!!.resume(late)
            assertFalse(h.controller.view().canPreview); assertFalse(late.plan!!.reference.isUsable(h.fixture.clock.instant()))
            assertEquals(before, h.fixture.journey.snapshot())
        }
    }
}
