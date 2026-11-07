package com.tripback.app
import org.junit.Assert.*
import org.junit.Test
class RouteTrialTest {
    @Test fun callReturnAndCompletedAloneAreNotSuccess() {
        val t = RouteTrial(); t.begin(); assertEquals("PENDING", t.result)
        t.event("completed"); t.unknown(); assertEquals("UNKNOWN", t.result)
    }
    @Test fun successRequiresEveryEvidence() {
        val t = RouteTrial(); t.begin(); t.event("started"); t.event("completed")
        t.event("routing"); t.event("guidance"); t.event("destination", false)
        assertEquals("PENDING", t.result)
        t.event("destination"); assertEquals("SUCCESS", t.result)
    }
    @Test fun timeoutIsTerminalAndLateCallbacksCannotSucceed() {
        val t = RouteTrial(); t.begin(); t.unknown()
        listOf("started","completed","routing","guidance","destination").forEach { t.event(it) }
        assertEquals("UNKNOWN", t.result)
    }
    @Test fun failureIsTerminal() {
        val t = RouteTrial(); t.begin(); t.event("failure"); t.unknown(); assertEquals("FAILURE", t.result)
    }
    @Test(expected = IllegalStateException::class) fun duplicateBeginIsRejected() { val t = RouteTrial(); t.begin(); t.begin() }
    @Test fun officialCoordinateOrderAndIdentityMustMatch() {
        assertTrue(matchesSample(37.48544722,127.03636666,"36khp37hh8gqcm5nqhe5","0"))
        assertFalse(matchesSample(127.03636666,37.48544722,"36khp37hh8gqcm5nqhe5","0"))
        assertFalse(matchesSample(37.48544722,127.03636666,"kakao-id","0"))
        assertFalse(matchesSample(Double.NaN,127.03636666,"36khp37hh8gqcm5nqhe5","0"))
        assertFalse(matchesSample(37.48544722,127.03636666,"36khp37hh8gqcm5nqhe5",null))
    }

    @Test fun everyPreflightRequirementMustPass() {
        fun allowed(consumed: Boolean = false, approved: Boolean = true, same: Boolean = true,
            age: Long = 0, sdk: Boolean = true, safety: Boolean = true, terms: Boolean = true) =
            canRequestTrial(consumed, approved, same, age, sdk, safety, terms)
        assertTrue(allowed())
        assertFalse(allowed(consumed = true))
        assertFalse(allowed(approved = false))
        assertFalse(allowed(same = false))
        assertFalse(allowed(age = 5001))
        assertFalse(allowed(age = -1))
        assertFalse(allowed(sdk = false))
        assertFalse(allowed(safety = false))
        assertFalse(allowed(terms = false))
    }

    @Test fun anotherRouteStartMakesAttributionUnknown() {
        val t = RouteTrial(); t.begin(); t.event("started"); t.event("started")
        t.event("completed"); t.event("destination"); t.event("routing"); t.event("guidance")
        assertEquals("UNKNOWN", t.result)
    }
    @Test fun changedDestinationInvalidatesPreviousEvidence() {
        val t = RouteTrial(); t.begin(); t.event("started"); t.event("completed")
        t.event("destination"); t.event("routing"); t.event("destination", false); t.event("guidance")
        assertEquals("PENDING", t.result)
    }
}
