package com.tripback.app.trip.replan

import com.tripback.app.trip.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import com.tripback.app.trip.replan.ReplanFixtures.now
import com.tripback.app.trip.replan.ReplanFixtures.token
import com.tripback.app.trip.replan.ReplanFixtures.registered
import com.tripback.app.trip.replan.ReplanFixtures.input
import com.tripback.app.trip.replan.ReplanFixtures.headers
import com.tripback.app.trip.replan.ReplanFixtures.verified
import com.tripback.app.trip.replan.ReplanFixtures.selection
import com.tripback.app.trip.replan.ReplanFixtures.request
import com.tripback.app.trip.replan.ReplanFixtures.recommendation
import com.tripback.app.trip.replan.ReplanFixtures.observation
import com.tripback.app.trip.replan.ReplanFixtures.preview
import com.tripback.app.trip.replan.ReplanFixtures.edit

class ReplanContractTest {
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected sanitized contract rejection") }
        catch (e: ReplanException) { assertFalse(e.message.orEmpty().contains(token)) }
    }
    @Test fun registeredHeadersBindAValidatedSnapshotWithoutExposingToken() {
        val plan = registered()
        assertTrue(plan.reference.isUsable(now)); assertEquals(1, plan.reference.revision)
        assertEquals(now.plusSeconds(1800), plan.reference.expiresAt)
        assertEquals(input, plan.validation.request)
        assertFalse(plan.toString().contains(token)); assertFalse(plan.reference.toString().contains(token))
    }
    @Test fun everyRegistrationHeaderIsRequiredAndCaseInsensitive() {
        for (key in headers().keys) {
            val h = headers(); h.remove(key)
            rejected { ReplanJson.registration(verified().toString(), h, selection(), "server", now) }
        }
        assertNotNull(ReplanJson.registration(verified().toString(), headers().mapKeys { it.key.lowercase() }, selection(), "server", now).plan)
    }
    @Test fun malformedAndDuplicateReferenceHeadersAreRejected() {
        for ((key, values) in listOf(
            "X-Tripback-Plan-Ref" to listOf("fake-plan-1"), "X-Tripback-Plan-Ref" to listOf(token, token),
            "X-Tripback-Plan-Revision" to listOf("0"), "X-Tripback-Plan-Revision" to listOf("01"),
            "X-Tripback-Plan-Revision" to listOf("2"),
            "X-Tripback-Plan-Revision" to listOf("9223372036854775808"),
            "X-Tripback-Plan-Expires-At" to listOf("invalid"), "X-Tripback-Plan-Registration" to listOf(" REGISTERED"))) {
            val h = headers(); h[key] = values
            rejected { ReplanJson.registration(verified().toString(), h, selection(), "server", now) }
        }
    }
    @Test fun expiredOrExcessiveTtlCannotIssueUsableReference() {
        for (at in listOf(now, now.minusSeconds(1), now.plusSeconds(1801))) {
            val h = headers(); h["X-Tripback-Plan-Expires-At"] = listOf(at.toString())
            rejected { ReplanJson.registration(verified().toString(), h, selection(), "server", now) }
        }
    }
    @Test fun nonRegisteredStatusNeverCarriesAReference() {
        for (status in listOf("NOT_ELIGIBLE", "CAPACITY_EXCEEDED", "DISABLED")) {
            val h = mutableMapOf("X-Tripback-Plan-Registration" to listOf(status))
            assertNull(ReplanJson.registration(verified().toString(), h, selection(), "server", now).plan)
            h["X-Tripback-Plan-Ref"] = listOf(token)
            rejected { ReplanJson.registration(verified().toString(), h, selection(), "server", now) }
        }
    }
    @Test fun demoBodyCannotBeRegisteredEvenWithSuccessHeaders() {
        rejected { ReplanJson.registration(TripValidationApiTest().fixture(input), headers(), selection(), "server", now) }
    }
    @Test fun demoRecommendationCannotBecomeARegistrationSelection() {
        val demo = Json.parseToJsonElement(TripValidationApiTest().fixture(input)).jsonObject
        rejected { ReplanSelection.fromRecommendation(recommendation(demo), request, "fake-plan-1") }
    }
    @Test fun changedIdOrderAndStayAreNotAccepted() {
        val v = verified()
        val visits = v.getValue("visits").jsonArray
        for (altered in listOf(JsonArray(visits.reversed()), JsonArray(listOf(
            visits[0].jsonObject.edit("stayMinutes" to JsonPrimitive(31)), visits[1])))) {
            rejected { ReplanJson.registration(v.edit("visits" to altered).toString(), headers(), selection(), "server", now) }
        }
    }
    @Test fun internallyConsistentChangedCoordinatesAreNotAccepted() {
        val v = verified()
        val old = v.getValue("visits").jsonArray[0].jsonObject.getValue("confirmedPlace").jsonObject.getValue("coordinates").toString()
        val changed = old.replace("36.0", "36.5")
        rejected { ReplanJson.registration(v.toString().replace(old, changed), headers(), selection(), "server", now) }
    }
    @Test fun alteredOriginDepartureDeadlineCannotBindToRecommendation() {
        for (r in listOf(request.copy(origin = ValidationPoint(0.0, 0.0)), request.copy(departAt = request.departAt.minusHours(1)),
            request.copy(returnDeadline = request.returnDeadline.minusHours(1)))) {
            rejected {
                val altered = ReplanSelection.fromRecommendation(recommendation(), r, "fake-plan-1")
                ReplanJson.registration(verified().toString(), headers(), altered, "server", now)
            }
        }
    }
    @Test fun callerChangesCannotSilentlyOverrideBoundBufferOrPreferences() {
        val interests = mutableListOf("CULTURE")
        val original = request.copy(interests = interests)
        val bound = ReplanSelection.fromRecommendation(recommendation(), original, "fake-plan-1")
        interests.add("FOOD")
        val changed = original.copy(safetyBufferMin = 45)
        assertEquals(listOf("CULTURE"), bound.request.interests)
        assertEquals(30, bound.validationRequest.safetyBufferMin)
        assertNotEquals(changed.safetyBufferMin, bound.validationRequest.safetyBufferMin)
    }
    @Test fun maximumOneWayAndOriginalPreferencesRemainBound() {
        val chosen = selection()
        assertEquals(request, chosen.request)
        val body = verified().toString().replace("\"durationSeconds\":600", "\"durationSeconds\":10860")
        rejected { ReplanJson.registration(body, headers(), chosen, "server", now) }
    }
    @Test fun requestUsesReferenceNotRecommendationIdAndContainsOnlyAllowedFields() {
        val raw = ReplanJson.request(registered(), observation(), now)
        val obj = Json.parseToJsonElement(raw).jsonObject
        assertEquals(token, obj.getValue("planRef").jsonPrimitive.content)
        assertFalse(raw.contains("fake-plan-1")); assertFalse(raw.contains("ETA")); assertFalse(raw.contains("source"))
        assertEquals(setOf("planRef", "revision", "currentVisitIndex", "phase", "observedAt", "elapsedStaySeconds", "requestedTotalStayMinutes"), obj.keys)
    }
    @Test fun feasibleAndInfeasibleResponsesPreserveBeforeAndAfter() {
        val plan = registered()
        val good = ReplanJson.preview(preview(plan).toString(), plan, observation(), now)
        assertEquals(ValidationFeasibility.FEASIBLE, good.after.feasibility)
        assertEquals(20L, good.before.currentRemainingStayMinutes); assertEquals(40L, good.after.currentRemainingStayMinutes)
        assertEquals(1200, Duration.between(good.before.predictedReturnAt, good.after.predictedReturnAt).seconds)
        val long = observation(1440)
        assertEquals(ValidationFeasibility.INFEASIBLE, ReplanJson.preview(preview(plan, long).toString(), plan, long, now).after.feasibility)
    }
    @Test fun staleEtaUnknownKeepsArithmeticAndOriginalEvidence() {
        val plan = registered(verified(now.minusSeconds(901)))
        val result = ReplanJson.preview(preview(plan).toString(), plan, observation(), now)
        assertEquals(ValidationFeasibility.UNKNOWN, result.after.feasibility)
        assertEquals(ValidationFeasibility.FEASIBLE, result.after.arithmeticFeasibility)
        assertNotNull(result.after.predictedReturnAt); assertTrue("STALE_ETA" in result.after.reasons)
        assertEquals(now.minusSeconds(901), result.after.remainingLegs.first().retrievedAt)
    }
    @Test fun missingEtaUnknownRetainsNullReturnAndMargins() {
        val plan = registered()
        val result = ReplanJson.preview(preview(plan, missing = true).toString(), plan, observation(), now)
        assertEquals(ValidationFeasibility.UNKNOWN, result.after.feasibility)
        assertNull(result.after.predictedReturnAt); assertNull(result.after.remainingMarginSeconds)
    }
    @Test fun previewStatusProgressAndBaseRevisionMustMatch() {
        val plan = registered(); val body = preview(plan)
        for ((key, value) in listOf("status" to JsonPrimitive("APPROVED"), "baseRevision" to JsonPrimitive(2),
            "progressVerification" to JsonPrimitive("VERIFIED_LIVE"), "currentVisitIndex" to JsonPrimitive(1))) {
            rejected { ReplanJson.preview(body.edit(key to value).toString(), plan, observation(), now) }
        }
    }
    @Test fun forgedMathSourceAndLiveCacheLabelsAreRejected() {
        val plan = registered(); val body = preview(plan).toString()
        for (bad in listOf(body.replace("\"cached\":true", "\"cached\":false"),
            body.replace("VERIFIED_CACHED", "VERIFIED_LIVE"), body.replace("KAKAO_MOBILITY", "FORGED"),
            body.replace("\"currentRemainingStayMinutes\":40", "\"currentRemainingStayMinutes\":41"))) {
            rejected { ReplanJson.preview(bad, plan, observation(), now) }
        }
    }
    @Test fun duplicateEscapedDuplicateTrailingAndDeepJsonAreRejected() {
        for (raw in listOf("{\"a\":1,\"a\":2}", "{\"a\":1,\"\\u0061\":2}", "{}{}", "[".repeat(33) + "0" + "]".repeat(33))) {
            try { ReplanJson.strict(raw); fail("Expected strict rejection") } catch (_: Exception) { }
        }
    }
    @Test fun referenceExpiryIsTerminalAndNoRawValueIsRetainedByDisplay() {
        val p = registered()
        assertFalse(p.reference.isUsable(now.plusSeconds(1800)))
        assertFalse(p.reference.isUsable(now)) // A backwards wall clock cannot resurrect a discarded token.
        rejected { ReplanJson.request(p, observation(), now) }
        assertFalse(p.reference.toString().contains(token))
    }
    @Test fun errorCodesAreAllowlistedAndExceptionTextIsSanitized() {
        assertEquals(ReplanError.PLAN_NOT_FOUND_OR_EXPIRED, ReplanJson.error("{\"code\":\"PLAN_NOT_FOUND_OR_EXPIRED\"}", 404))
        assertEquals(ReplanError.API_NOT_AVAILABLE, ReplanJson.error("{\"status\":404}", 404))
        assertEquals(ReplanError.HTTP_ERROR, ReplanJson.error("{\"code\":\"sensitive-marker\"}", 500))
        assertEquals(ReplanError.HTTP_ERROR, ReplanJson.error("{\"code\":\"PLAN_NOT_FOUND_OR_EXPIRED\"}", 400))
    }
}
