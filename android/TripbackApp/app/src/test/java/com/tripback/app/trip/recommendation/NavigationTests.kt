package com.tripback.app.trip.recommendation

import com.tripback.app.trip.*
import org.junit.Assert.*
import org.junit.Test

class NavigationTests {
    private fun plan(): RecommendationAlternative {
        val request=RecommendationForm(parked=true,date="2026-11-07").request()
        val fixture=RecommendationJson.response(RecommendationFixtures.response(request,FakeScenario.ONE),request).alternatives.first()
        val modes=setOf(ValidationDataMode.VERIFIED_CACHED)
        val visits=fixture.validation.visits.map { v -> v.copy(confirmedPlace=v.confirmedPlace!!.let { p ->
            p.copy(data=p.data.copy(dataMode=ValidationDataMode.VERIFIED_CACHED,verification="VALIDATED_PROVIDER_DATA")) }) }
        return fixture.copy(dataModes=modes,validation=fixture.validation.copy(dataModes=modes,
            dataCoverage=ValidationCoverage.ALL_PROVIDER_DATA_DECLARED_VALIDATED,visits=visits))
    }
    @Test fun validatedFirstDestinationRetainsKakaoIdentityAndCoordinates() {
        val p=plan();val d=NavigationDestinationValidator.first(p)
        assertEquals(p.orderedVisits.first().id,d.sourceId)
        assertEquals(p.validation.visits.first().confirmedPlace!!.coordinates,d.coordinates)
        assertEquals("KAKAO_LOCAL",d.sourceId.provider)
    }
    @Test fun unknownAndInfeasibleBlocked() {
        for(f in listOf(ValidationFeasibility.UNKNOWN,ValidationFeasibility.INFEASIBLE)) {
            assertThrows(Exception::class.java) { NavigationDestinationValidator.first(plan().copy(feasibility=f)) }
        }
    }
    @Test fun demoAndUnverifiedIdentityBlocked() {
        val p=plan()
        assertThrows(Exception::class.java) { NavigationDestinationValidator.first(p.copy(dataModes=setOf(ValidationDataMode.DEMO_SIMULATED))) }
        val v=p.validation.visits.toMutableList();v[0]=v[0].copy(identityStatus="UNCONFIRMED")
        assertThrows(Exception::class.java) { NavigationDestinationValidator.first(p.copy(validation=p.validation.copy(visits=v))) }
    }
    @Test fun wrongIdAndCoordinatesBlocked() {
        val p=plan();val visits=p.validation.visits.toMutableList();val first=visits[0]
        visits[0]=first.copy(confirmedPlace=first.confirmedPlace!!.copy(id=ValidationPlaceId("KAKAO_LOCAL","other")))
        assertThrows(Exception::class.java) { NavigationDestinationValidator.first(p.copy(validation=p.validation.copy(visits=visits))) }
        visits[0]=first.copy(confirmedPlace=first.confirmedPlace.copy(coordinates=ValidationPoint(0.0,0.0)))
        assertThrows(Exception::class.java) { NavigationDestinationValidator.first(p.copy(validation=p.validation.copy(visits=visits))) }
        assertThrows(Exception::class.java) { ValidationPoint(Double.NaN,127.0) }
        assertThrows(Exception::class.java) { ValidationPoint(91.0,127.0) }
    }
    @Test fun approvalAndUxStateRequiredThenDuplicateBlocked() {
        val adapter=FakeNavigationAdapter();val departure=NavigationDeparture(adapter)
        departure.approve(plan(),false,true);assertEquals(0,adapter.requests)
        departure.approve(plan(),true,false);assertEquals(0,adapter.requests)
        departure.approve(null,true,true);assertEquals(0,adapter.requests)
        departure.approve(plan(),true,true);departure.approve(plan(),true,true)
        assertEquals(1,adapter.requests)
        assertTrue(departure.state.value.message.contains("DEMO_SIMULATED"))
    }
    @Test fun missingPermissionAndUnsupportedSdkNeverRequest() {
        val adapter=FakeNavigationAdapter();val departure=NavigationDeparture(adapter)
        adapter.capability.value=NavigationCapability(true,false,"permission missing")
        departure.approve(plan(),true,true);assertEquals(0,adapter.requests)
        adapter.capability.value=NavigationCapability(false,true,"unsupported")
        departure.approve(plan(),true,true);assertEquals(0,adapter.requests)
        assertEquals(NavigationStatus.BLOCKED,departure.state.value.status)
    }
    @Test fun permissionExceptionAndRequestFailureDoNotRetry() {
        for(failure in listOf(SecurityException(),IllegalStateException())) {
            var calls=0
            val adapter=object:NavigationAdapter {
                override val capability=kotlinx.coroutines.flow.MutableStateFlow(NavigationCapability(true,true,"test only"))
                override fun request(destination:NavigationDestination,event:(NavigationState)->Unit) {calls++;throw failure}
            }
            val departure=NavigationDeparture(adapter)
            departure.approve(plan(),true,true);departure.approve(plan(),true,true)
            assertEquals(1,calls);assertEquals(NavigationStatus.FAILED,departure.state.value.status)
        }
    }
}
