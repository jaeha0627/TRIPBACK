package com.tripback.app

/** Unit return or search-complete alone never proves guidance. Main-thread confined. */
internal class RouteTrial {
    var result = "NOT_REQUESTED"; private set
    var started = false; private set
    var completed = false; private set
    private var matching = false
    private var routing = false
    private var guidance = false
    fun begin() { check(result == "NOT_REQUESTED"); result = "PENDING" }
    fun event(kind: String, value: Boolean = true) {
        if (result != "PENDING") return
        when (kind) {
            "started" -> if (started) { result = "UNKNOWN" } else { started = true }
            "completed" -> if (started) completed = true
            "destination" -> matching = value
            "routing" -> routing = value
            "guidance" -> guidance = value
            "failure" -> result = "FAILURE"
        }
        if (result == "PENDING" && started && completed && matching && routing && guidance) result = "SUCCESS"
    }
    fun unknown() { if (result == "PENDING") result = "UNKNOWN" }
}
/** Compatibility entry point: strict identity is unchanged; diagnostics never normalize for success. */
internal fun matchesSample(latitude: Double, longitude: Double, id: String, subId: String?): Boolean =
    diagnoseDestination(latitude, longitude, id, subId, name = "").strictIdentityMatch

/** Fresh complete SDK replies and explicit consent are required; any unknown blocks. */
internal fun canRequestTrial(consumed: Boolean, approved: Boolean, sameSnapshot: Boolean,
    responseAgeMs: Long, sdkReady: Boolean, parkedAndUnrestricted: Boolean, termsAccepted: Boolean): Boolean =
    !consumed && approved && sameSnapshot && responseAgeMs in 0..5000 && sdkReady &&
        parkedAndUnrestricted && termsAccepted

/** Manual reads do not depend on completion; a finished/restored trial gets diagnostics only. */
internal fun manualDiagnosticPurpose(consumed: Boolean, result: String): String = when {
    !consumed -> "inspect"
    result == "PENDING" -> "evidence"
    else -> "post_trial_diagnostic"
}
