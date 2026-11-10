package com.tripback.app

import org.junit.Assert.*
import org.junit.Test

class DestinationDiagnosticTest {
    private fun sample(id: String = "36khp37hh8gqcm5nqhe5", sub: String? = "0",
        lat: Double = 37.48544722, lon: Double = 127.03636666, name: String = "42dot",
        displayLat: Double? = null, displayLon: Double? = null) =
        diagnoseDestination(lat, lon, id, sub, name, displayLat, displayLon)

    @Test fun exactCoreIdentityMatches() {
        val d = sample()
        assertEquals(PoiIdComparison.EXACT, d.poiId)
        assertEquals(SubIdComparison.EXACT, d.poiSubId)
        assertEquals(DestinationComparison.MATCH, d.summary)
        assertTrue(d.nameExact); assertTrue(d.strictIdentityMatch)
    }
    @Test fun emptyIdIsInsufficient() {
        val d = sample(id = "")
        assertEquals(PoiIdComparison.EMPTY, d.poiId)
        assertEquals(DestinationComparison.INSUFFICIENT_DATA, d.summary)
    }
    @Test fun whitespaceIdIsInsufficient() {
        val d = sample(id = " \t\n")
        assertEquals(PoiIdComparison.WHITESPACE_ONLY, d.poiId)
        assertEquals(DestinationComparison.INSUFFICIENT_DATA, d.summary)
    }
    @Test fun trimOnlyIdDoesNotMatchStrictIdentity() {
        val d = sample(id = " 36khp37hh8gqcm5nqhe5 ")
        assertEquals(PoiIdComparison.TRIM_ONLY_MATCH, d.poiId)
        assertEquals(DestinationComparison.FIELDS_DIFFER, d.summary)
        assertFalse(d.strictIdentityMatch)
    }
    @Test fun differentIdAndMatchingNameDoNotMatch() {
        val d = sample(id = "synthetic-other-id")
        assertTrue(d.nameExact)
        assertEquals(PoiIdComparison.DIFFERENT, d.poiId)
        assertEquals(DestinationComparison.FIELDS_DIFFER, d.summary)
    }
    @Test fun nullSubIdIsInsufficient() {
        val d = sample(sub = null)
        assertEquals(SubIdComparison.NULL, d.poiSubId)
        assertEquals(DestinationComparison.INSUFFICIENT_DATA, d.summary)
    }
    @Test fun emptySubIdIsInsufficient() {
        val d = sample(sub = "")
        assertEquals(SubIdComparison.EMPTY, d.poiSubId)
        assertEquals(DestinationComparison.INSUFFICIENT_DATA, d.summary)
    }
    @Test fun subIdIsNotNumericallyNormalized() {
        for (s in listOf("00", " 0", "1")) {
            val d = sample(sub = s)
            assertEquals(SubIdComparison.DIFFERENT, d.poiSubId)
            assertEquals(DestinationComparison.FIELDS_DIFFER, d.summary)
            assertFalse(d.strictIdentityMatch)
        }
    }
    @Test fun blankSubIdIsNotEvidenceOfDifferentDestination() {
        val d = sample(sub = " ")
        assertEquals(SubIdComparison.DIFFERENT, d.poiSubId)
        assertEquals(DestinationComparison.INSUFFICIENT_DATA, d.summary)
    }
    @Test fun latitudeAndLongitudeAreDiagnosedIndependently() {
        val latitudeOnly = sample(lat = 37.48546722)
        assertEquals(CoordinateComparison.OUTSIDE_TOLERANCE, latitudeOnly.latitude)
        assertEquals(CoordinateComparison.WITHIN_TOLERANCE, latitudeOnly.longitude)
        val longitudeOnly = sample(lon = 127.03638666)
        assertEquals(CoordinateComparison.WITHIN_TOLERANCE, longitudeOnly.latitude)
        assertEquals(CoordinateComparison.OUTSIDE_TOLERANCE, longitudeOnly.longitude)
        assertEquals(DestinationComparison.FIELDS_DIFFER, longitudeOnly.summary)
    }
    @Test fun toleranceIsNotExpanded() {
        assertEquals(DestinationComparison.MATCH, sample(lat = 37.48544722 + 0.000009).summary)
        assertEquals(DestinationComparison.FIELDS_DIFFER, sample(lat = 37.48544722 + 0.000011).summary)
    }
    @Test fun nonFiniteOrOutOfRangeCoordinatesAreInsufficient() {
        for (v in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 91.0, -91.0)) {
            val d = sample(lat = v)
            assertEquals(CoordinateComparison.INVALID, d.latitude)
            assertEquals(DestinationComparison.INSUFFICIENT_DATA, d.summary)
        }
        assertEquals(CoordinateComparison.INVALID, sample(lon = 181.0).longitude)
        assertEquals(CoordinateComparison.INVALID, sample(lon = -181.0).longitude)
    }
    @Test fun displayCoordinatesAreOptionalAndNeverReplaceBaseCoordinates() {
        val absent = sample()
        assertEquals(CoordinateComparison.ABSENT, absent.displayLatitude)
        assertEquals(CoordinateComparison.ABSENT, absent.displayLongitude)
        val d = sample(lat = 37.5, displayLat = 37.48544722, displayLon = 127.03636666)
        assertEquals(CoordinateComparison.WITHIN_TOLERANCE, d.displayLatitude)
        assertEquals(CoordinateComparison.WITHIN_TOLERANCE, d.displayLongitude)
        assertEquals(DestinationComparison.FIELDS_DIFFER, d.summary)
    }
    @Test fun displayAxesCanBeAbsentInvalidNearOrFarIndependently() {
        val d = sample(displayLat = Double.NaN, displayLon = 0.0)
        assertEquals(CoordinateComparison.INVALID, d.displayLatitude)
        assertEquals(CoordinateComparison.OUTSIDE_TOLERANCE, d.displayLongitude)
        assertEquals(CoordinateComparison.ABSENT, sample(displayLon = 127.03636666).displayLatitude)
    }
    @Test fun nameIsExactAndSupplementary() {
        for (n in listOf("42DOT", " 42dot ", "synthetic-name", "")) {
            val d = sample(name = n)
            assertFalse(d.nameExact)
            assertEquals(DestinationComparison.MATCH, d.summary)
        }
        assertFalse(sample(name = " ").namePresent)
        assertFalse(sample(id = "", sub = null, name = "42dot").strictIdentityMatch)
    }
    @Test fun missingCoreDataTakesPriorityButOtherFieldDiagnosticsRemain() {
        val d = sample(id = "synthetic-other-id", sub = null, lon = 0.0)
        assertEquals(DestinationComparison.INSUFFICIENT_DATA, d.summary)
        assertEquals(PoiIdComparison.DIFFERENT, d.poiId)
        assertEquals(CoordinateComparison.OUTSIDE_TOLERANCE, d.longitude)
    }
    @Test fun diagnosticRenderingContainsCategoriesAndNeverInputValues() {
        val id = "sensitive-id-marker"
        val sub = "sensitive-sub-marker"
        val name = "sensitive-name-marker"
        val d = sample(id = id, sub = sub, name = name, lat = 12.3456789, lon = 98.7654321)
        for (output in listOf(d.comparisonLine(), d.toString())) {
            for (raw in listOf(id, sub, name, "12.3456789", "98.7654321")) assertFalse(output.contains(raw))
            assertTrue(output.contains("FIELDS_DIFFER"))
        }
    }
    @Test fun readOnlyQueryDoesNotRequireCompletedCallback() {
        assertEquals("inspect", manualDiagnosticPurpose(false, "NOT_REQUESTED"))
        val t = RouteTrial(); t.begin(); t.event("started")
        assertFalse(t.completed)
        assertEquals("evidence", manualDiagnosticPurpose(true, t.result))
        t.event("destination"); t.event("routing"); t.event("guidance")
        assertEquals("PENDING", t.result)
    }
    @Test fun postTimeoutDiagnosisCannotPromoteTrial() {
        val t = RouteTrial(); t.begin(); t.unknown()
        assertEquals("post_trial_diagnostic", manualDiagnosticPurpose(true, t.result))
        assertEquals(DestinationComparison.MATCH, sample().summary)
        listOf("started", "completed", "destination", "routing", "guidance").forEach { t.event(it) }
        assertEquals("UNKNOWN", t.result)
        assertEquals("post_trial_diagnostic", manualDiagnosticPurpose(true, "NOT_REQUESTED"))
    }
    @Test fun comparisonCategoriesDoNotRelaxExistingStrictMatch() {
        assertFalse(matchesSample(37.48544722,127.03636666," 36khp37hh8gqcm5nqhe5 ","0"))
        assertFalse(matchesSample(37.48544722,127.03636666,"36khp37hh8gqcm5nqhe5",null))
        val t = RouteTrial(); t.begin(); t.event("started"); t.event("completed")
        t.event("routing"); t.event("guidance")
        t.event("destination", sample(id = " ").strictIdentityMatch)
        assertEquals("PENDING", t.result)
    }
}
