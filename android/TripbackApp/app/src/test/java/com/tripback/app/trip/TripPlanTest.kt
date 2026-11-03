package com.tripback.app.trip

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.junit.Assert.*
import org.junit.Test

class TripPlanTest {
    @Test fun providesTwoDistinctExplicitlySimulatedAlternativesWithUnknownTiming() {
        val results = demoTripPlanResults(TripInput())
        assertEquals(listOf("여유형", "알찬형"), results.alternatives.map { it.title })
        assertEquals(2, results.alternatives.map { it.planId }.distinct().size)
        assertNotEquals(results.alternatives[0].stops, results.alternatives[1].stops)
        results.alternatives.forEach { plan ->
            assertEquals(PlanDataMode.DEMO_SIMULATED, plan.dataMode)
            assertNull(plan.predictedReturnAt)
            assertNull(plan.travelMinutes)
            assertNull(plan.isFeasible)
            assertEquals(plan.stops.size, plan.stops.map { it.stopId }.distinct().size)
            assertTrue(plan.stops.all { it.stayMinutes > 0 && it.name.startsWith("시연 ") && it.dataMode == PlanDataMode.DEMO_SIMULATED })
        }
    }

    @Test fun selectionSwitchesDetailsWithoutChangingInputOrOriginalState() {
        val input = TripInput(region = "남해안", companionType = "SOLO", childAgeGroup = null, interests = setOf("PHOTO"))
        val initial = demoTripPlanResults(input)
        assertNull(initial.selectedPlan)
        val first = initial.select(initial.alternatives[0].planId)
        assertEquals(initial.alternatives[0], first.selectedPlan)
        val second = first.select(initial.alternatives[1].planId)
        assertEquals(initial.alternatives[1], second.selectedPlan)
        assertEquals(input, second.input)
        assertEquals(initial.alternatives, second.alternatives)
        assertNull(initial.selectedPlanId)
        assertEquals(second, second.select(second.selectedPlanId!!))
    }

    @Test fun unknownSelectionDoesNotClearExistingSelection() {
        val results = demoTripPlanResults(TripInput()).select("demo-full")
        assertEquals(results, results.select("unknown"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidInputCannotOpenResults() {
        demoTripPlanResults(TripInput(departTime = "08:00", returnDeadline = "10:00"))
    }

    @Test fun demoContentIsFixedRatherThanClaimingOptimizationForInput() {
        assertEquals(demoTripPlanResults(TripInput()).alternatives,
            demoTripPlanResults(TripInput(region = "호수·산", interests = emptySet())).alternatives)
    }

    @Test fun savedStateRoundTripPreservesInputAndSelection() {
        val original = demoTripPlanResults(TripInput(region = "동해안")).select("demo-full")
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(original) }
        val restored = ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() as TripPlanResults }
        assertEquals(original, restored)
        assertEquals(original.selectedPlan, restored.selectedPlan)
    }
}
