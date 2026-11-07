package com.tripback.app.trip.replan

import com.tripback.app.trip.ValidationFeasibility
import com.tripback.app.trip.progress.*
import com.tripback.app.trip.recommendation.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import kotlin.coroutines.*

class ReplanDebugControllerTest {
    @Test fun httpControllerEntryAndUnapprovedActionsNeverPost() {
        ReplanFixtures.Server().use { server ->
            Context(repository = server.client()).use { c ->
                val original = c.journey.snapshot()
                repeat(3) { assertTrue(c.controller.view().canRegister) }
                assertFalse(c.controller.register(false))
                assertFalse(c.controller.preview())
                c.controller.stop(); c.controller.dismiss()
                assertEquals(0, server.registrations.get())
                assertEquals(0, server.previews.get())
                assertEquals(original, c.journey.snapshot())
            }
        }
    }
    @Test fun httpControllerAlsoRejectsDemoBeforeAnyPost() {
        ReplanFixtures.Server().use { server ->
            val request = ReplanFixtures.request.copy(mustVisitPlaceIds = emptyList())
            val raw = RecommendationFixtures.response(request, FakeScenario.ONE)
            val id = RecommendationJson.response(raw, request).alternatives.first().planId
            Context(raw, request, suppliedId = id, repository = server.client()).use { c ->
                assertFalse(c.controller.view().canRegister)
                assertFalse(c.controller.register(true))
                assertEquals(0, server.registrations.get())
            }
        }
    }
    private class Time : Clock(), JourneyClock {
        var at = ReplanFixtures.now.minusSeconds(600)
        var mono = 100000L
        override fun instant() = at
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun wall() = at.toEpochMilli()
        override fun elapsed() = mono
        fun advance(seconds: Long) { at = at.plusSeconds(seconds); mono += seconds * 1000 }
    }
    private class Context(raw: String = ReplanFixtures.recommendation(),
        request: RecommendationRequest = ReplanFixtures.request,
        suppliedRaw: String = raw, suppliedId: String = "fake-plan-1",
        repository: DebugReplanRepository? = null) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val clock = Time()
        val plan = RecommendationJson.response(raw, request).alternatives.first()
        val journey = TripJourney(plan, request, clock)
        val fake = FakeReplanUiRepository(clock, 0)
        val controller: ReplanDebugController
        init {
            journey.depart(true); journey.manualAdvance(JourneyPhase.DEPARTURE, journey.state.value.revision)
            journey.manualAdvance(JourneyPhase.TRAVELING, journey.state.value.revision); journey.startStay()
            clock.advance(600)
            controller = ReplanDebugController(scope, journey, request, suppliedRaw, suppliedId, repository ?: fake, clock)
        }
        override fun close() { controller.close(); scope.cancel() }
    }
    @Test fun fakeRegistrationRequiresExplicitConfirmationAndDoesNotTouchJourney() {
        Context().use { c ->
            val original = c.journey.snapshot()
            assertFalse(c.controller.preview()); assertFalse(c.controller.register(false))
            assertEquals(0, c.fake.registrationCalls)
            assertTrue(c.controller.register(true)); assertTrue(c.controller.view().canPreview)
            assertEquals(1, c.fake.registrationCalls); assertEquals(original, c.journey.snapshot())
        }
    }
    @Test fun thirtyToFiftyComparesTwentyToFortyRemainingAndTwentyMinuteDelay() {
        Context().use { c ->
            val original = c.journey.snapshot()
            assertEquals(30L, c.controller.view().originalStayMinutes); assertEquals(600L, c.controller.view().elapsedSeconds)
            c.controller.register(true); c.controller.changeTotal(50); c.controller.preview()
            val result = c.controller.view().requestState.preview!!
            assertEquals(20L, result.before.currentRemainingStayMinutes); assertEquals(40L, result.after.currentRemainingStayMinutes)
            assertEquals(1200, Duration.between(result.before.predictedReturnAt, result.after.predictedReturnAt).seconds)
            assertEquals(ValidationFeasibility.FEASIBLE, result.after.feasibility)
            assertEquals("PREVIEW_ONLY", result.status)
            assertTrue(result.after.remainingLegs.all { it.dataMode?.name == "DEMO_SIMULATED" && it.verification == "UNVERIFIED" })
            assertEquals(original, c.journey.snapshot())
        }
    }
    @Test fun longStayShowsInfeasibleWithoutApplyingIt() {
        Context().use { c ->
            val stays = c.journey.state.value.stays
            c.controller.register(true); c.controller.changeTotal(1440); c.controller.preview()
            assertEquals(ValidationFeasibility.INFEASIBLE, c.controller.view().requestState.preview!!.after.feasibility)
            assertEquals(stays, c.journey.state.value.stays)
        }
    }
    @Test fun staleEtaShowsUnknownAndPreservesAssumptionResult() {
        Context().use { c ->
            c.controller.register(true); c.controller.scenario(ReplanUiScenario.STALE); c.controller.preview()
            val result = c.controller.view().requestState.preview!!
            assertEquals(ValidationFeasibility.UNKNOWN, result.after.feasibility)
            assertTrue("STALE_ETA" in result.after.reasons)
            assertEquals(c.clock.instant().minusSeconds(901), result.after.remainingLegs.first().retrievedAt)
        }
    }
    @Test fun expiryErrorAndConflictNeverAutoRegisterOrMutatePlan() {
        for ((scenario, phase) in listOf(ReplanUiScenario.EXPIRED to ReplanRequestPhase.EXPIRED,
            ReplanUiScenario.SERVER_ERROR to ReplanRequestPhase.ERROR, ReplanUiScenario.REVISION_CONFLICT to ReplanRequestPhase.ERROR)) {
            Context().use { c ->
                val original = c.journey.snapshot()
                c.controller.register(true); c.controller.scenario(scenario); c.controller.preview()
                assertEquals(phase, c.controller.view().requestState.phase)
                assertNull(c.controller.view().requestState.preview)
                assertEquals(1, c.fake.registrationCalls); assertEquals(1, c.fake.previewCalls)
                assertEquals(original, c.journey.snapshot())
                if (scenario != ReplanUiScenario.SERVER_ERROR) assertFalse(c.controller.view().canPreview)
            }
        }
    }
    @Test fun thirtyMinuteReferenceExpiryDoesNotRenew() {
        Context().use { c ->
            c.controller.register(true); c.clock.advance(1800)
            assertEquals(ReplanRequestPhase.EXPIRED, c.controller.view().requestState.phase)
            assertFalse(c.controller.preview()); assertEquals(1, c.fake.registrationCalls); assertEquals(0, c.fake.previewCalls)
        }
    }
    @Test fun unchangedReferenceStillGetsUnknownAfterFifteenMinuteEtaLimit() {
        Context().use { c ->
            c.controller.register(true); c.clock.advance(901); c.controller.changeTotal(50); c.controller.preview()
            assertEquals(ValidationFeasibility.UNKNOWN, c.controller.view().requestState.preview!!.after.feasibility)
            assertEquals(1, c.fake.registrationCalls)
        }
    }
    @Test fun displayedFeasiblePreviewIsInvalidatedWhenEtaAgesWithoutAutoRequest() {
        Context().use { c ->
            c.controller.register(true); c.controller.preview(); assertNotNull(c.controller.view().requestState.preview)
            c.clock.advance(901)
            val view = c.controller.view()
            assertNull(view.requestState.preview); assertNotNull(view.etaNotice)
            assertEquals(1, c.fake.previewCalls); assertEquals(1, c.fake.registrationCalls)
        }
    }
    @Test fun closeAndRejectOnlyDismissPreviewAndKeepOriginalSchedule() {
        Context().use { c ->
            val original = c.journey.snapshot()
            c.controller.register(true); c.controller.changeTotal(50); c.controller.preview(); c.controller.dismiss()
            assertNull(c.controller.view().requestState.preview); assertEquals(original, c.journey.snapshot())
            c.controller.close(); assertEquals(original, c.journey.snapshot())
        }
    }
    @Test fun localShortenAndOptionalOmissionStillWorkButBlockServerBaseline() {
        for (omit in listOf(false, true)) Context(request = ReplanFixtures.request.copy(mustVisitPlaceIds = emptyList())).use { c ->
            c.controller.register(true); c.controller.preview()
            val proposal = if (omit) c.journey.omitProposal()!! else c.journey.shortenProposal()!!
            c.journey.decide(proposal, true)
            assertNotNull(c.controller.view().blockedReason); assertNull(c.controller.view().requestState.preview)
            assertFalse(c.controller.register(true)); assertFalse(c.controller.preview())
            if (omit) assertEquals(setOf(1), c.journey.state.value.omitted) else assertEquals(1200L, c.journey.state.value.stays[0])
        }
    }
    @Test fun phaseAndLocalRevisionChangeInvalidatePreviewWithoutRequestReplay() {
        Context().use { c ->
            c.controller.register(true); c.controller.preview(); c.journey.finishStay(true)
            assertNull(c.controller.view().requestState.preview); assertFalse(c.controller.view().canPreview)
            assertEquals(1, c.fake.previewCalls); assertEquals(1, c.fake.registrationCalls)
        }
    }
    @Test fun editedCandidateCancelsOldComparisonWithoutChangingJourney() {
        Context().use { c ->
            c.controller.register(true); c.controller.preview(); c.controller.changeTotal(50)
            assertNull(c.controller.view().requestState.preview); assertEquals(30L, c.journey.state.value.stays[0] / 60)
        }
    }
    @Test fun wrongSelectedIdOrOriginalSnapshotBlocksRegistration() {
        Context(suppliedId = "different-plan").use { assertFalse(it.controller.register(true)); assertEquals(0, it.fake.registrationCalls) }
        Context(suppliedRaw = "{}").use { assertFalse(it.controller.view().canRegister) }
    }
    @Test fun demoRecommendationCannotBePromotedToServerValidationForUi() {
        val raw = RecommendationFixtures.response(ReplanFixtures.request.copy(mustVisitPlaceIds = emptyList()), FakeScenario.ONE)
        val request = ReplanFixtures.request.copy(mustVisitPlaceIds = emptyList())
        val actualId = RecommendationJson.response(raw, request).alternatives.first().planId
        Context(raw, request, suppliedId = actualId).use { c ->
            assertNotNull(c.controller.view().blockedReason); assertFalse(c.controller.register(true)); assertEquals(0, c.fake.registrationCalls)
        }
    }
    private class HeldRepository : DebugReplanRepository {
        var registrations = 0; var previews = 0
        var registerReply: Continuation<ReplanRegistration>? = null
        var previewReply: Continuation<ReplanPreview>? = null
        override suspend fun register(selection: ReplanSelection, explicitlyRequested: Boolean): ReplanRegistration {
            registrations++; return suspendCoroutine { registerReply = it }
        }
        override suspend fun preview(plan: RegisteredReplanPlan, observation: ReplanObservation): ReplanPreview {
            previews++; return suspendCoroutine { previewReply = it }
        }
        fun registered(): ReplanRegistration {
            val p = ReplanFixtures.registered(); return ReplanRegistration(PlanRegistrationStatus.REGISTERED, p.validation, p)
        }
    }
    @Test fun duplicateAndCancelledRegistrationCannotInstallLateReference() {
        val held = HeldRepository()
        Context(repository = held).use { c ->
            assertTrue(c.controller.register(true)); assertFalse(c.controller.register(true)); assertEquals(1, held.registrations)
            c.controller.stop(); val late = held.registered(); held.registerReply!!.resume(late)
            assertFalse(c.controller.view().canPreview); assertFalse(late.plan!!.reference.isUsable(ReplanFixtures.now))
        }
    }
    @Test fun latePreviewCannotSurviveProgressChange() {
        val held = HeldRepository()
        Context(repository = held).use { c ->
            c.controller.register(true); held.registerReply!!.resume(held.registered()); c.controller.preview()
            c.journey.finishStay(true)
            val p = ReplanFixtures.registered()
            held.previewReply!!.resume(ReplanJson.preview(ReplanFixtures.preview(p).toString(), p,
                ReplanFixtures.observation(), ReplanFixtures.now))
            assertNull(c.controller.view().requestState.preview); assertEquals(1, held.previews)
        }
    }
}
