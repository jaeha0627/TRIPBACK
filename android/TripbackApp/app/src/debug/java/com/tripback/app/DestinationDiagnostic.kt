package com.tripback.app

internal enum class PoiIdComparison { EXACT, EMPTY, WHITESPACE_ONLY, TRIM_ONLY_MATCH, DIFFERENT }
internal enum class SubIdComparison { EXACT, NULL, EMPTY, DIFFERENT }
internal enum class CoordinateComparison { ABSENT, INVALID, WITHIN_TOLERANCE, OUTSIDE_TOLERANCE }
internal enum class DestinationComparison { MATCH, INSUFFICIENT_DATA, FIELDS_DIFFER }

/** Contains comparison categories only. Safe to render; never retain input values here. */
internal data class DestinationDiagnostic(
    val poiId: PoiIdComparison,
    val poiSubId: SubIdComparison,
    val latitude: CoordinateComparison,
    val longitude: CoordinateComparison,
    val namePresent: Boolean,
    val nameExact: Boolean,
    val displayLatitude: CoordinateComparison,
    val displayLongitude: CoordinateComparison,
    val summary: DestinationComparison
) {
    // Same identity requirement as the original matchesSample. Name/display are supplementary.
    val strictIdentityMatch get() = summary == DestinationComparison.MATCH
    fun comparisonLine(): String =
        "summary=$summary poiId=$poiId poiSubId=$poiSubId latitude=$latitude longitude=$longitude " +
            "namePresent=$namePresent nameExact=$nameExact displayLatitude=$displayLatitude displayLongitude=$displayLongitude"
}

private fun coordinateComparison(value: Double?, expected: Double, limit: Double): CoordinateComparison = when {
    value == null -> CoordinateComparison.ABSENT
    !value.isFinite() || value !in -limit..limit -> CoordinateComparison.INVALID
    kotlin.math.abs(value - expected) <= 0.00001 -> CoordinateComparison.WITHIN_TOLERANCE
    else -> CoordinateComparison.OUTSIDE_TOLERANCE
}

internal fun diagnoseDestination(
    latitude: Double, longitude: Double, poiId: String, poiSubId: String?, name: String,
    displayLatitude: Double? = null, displayLongitude: Double? = null
): DestinationDiagnostic {
    val id = when {
        poiId == "36khp37hh8gqcm5nqhe5" -> PoiIdComparison.EXACT
        poiId.isEmpty() -> PoiIdComparison.EMPTY
        poiId.isBlank() -> PoiIdComparison.WHITESPACE_ONLY
        poiId.trim() == "36khp37hh8gqcm5nqhe5" -> PoiIdComparison.TRIM_ONLY_MATCH
        else -> PoiIdComparison.DIFFERENT
    }
    val sub = when (poiSubId) {
        "0" -> SubIdComparison.EXACT
        null -> SubIdComparison.NULL
        "" -> SubIdComparison.EMPTY
        else -> SubIdComparison.DIFFERENT
    }
    val lat = coordinateComparison(latitude, 37.48544722, 90.0)
    val lon = coordinateComparison(longitude, 127.03636666, 180.0)
    val summary = when {
        poiId.isBlank() || poiSubId.isNullOrBlank() || lat == CoordinateComparison.INVALID || lon == CoordinateComparison.INVALID ->
            DestinationComparison.INSUFFICIENT_DATA
        id == PoiIdComparison.EXACT && sub == SubIdComparison.EXACT &&
            lat == CoordinateComparison.WITHIN_TOLERANCE && lon == CoordinateComparison.WITHIN_TOLERANCE -> DestinationComparison.MATCH
        else -> DestinationComparison.FIELDS_DIFFER
    }
    return DestinationDiagnostic(id, sub, lat, lon, name.isNotBlank(), name == "42dot",
        coordinateComparison(displayLatitude, 37.48544722, 90.0),
        coordinateComparison(displayLongitude, 127.03636666, 180.0), summary)
}
