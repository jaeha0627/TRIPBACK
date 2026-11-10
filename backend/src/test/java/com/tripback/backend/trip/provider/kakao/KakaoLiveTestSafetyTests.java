package com.tripback.backend.trip.provider.kakao;

import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;
import static org.junit.jupiter.api.Assertions.*;

/** Calls only the pre-transport guards; ordinary test must never authorize a live request. */
class KakaoLiveTestSafetyTests {
    @Test void ordinaryTestAbortsPlaceLiveBeforeReadingCredentialsOrCallingAdapter() {
        assertFalse(Boolean.getBoolean("tripback.liveTests.allowed"));
        var aborted = assertThrows(TestAbortedException.class,
                () -> new KakaoPlaceSearchLiveTests().officialKeywordSearchReturnsValidatedLiveCandidates());
        assertTrue(aborted.getMessage().contains("LIVE_TASK_NOT_AUTHORIZED"));
    }
    @Test void ordinaryTestAbortsRouteLiveEvenIfEnvironmentOptInFlagsExist() {
        assertFalse(Boolean.getBoolean("tripback.liveTests.allowed"));
        var aborted = assertThrows(TestAbortedException.class,
                () -> new KakaoRoadRouteLiveTests().officialDirectionsReturnsValidatedLiveEtaAndPreservesRequestedEndpoints());
        assertTrue(aborted.getMessage().contains("LIVE_TASK_NOT_AUTHORIZED"));
    }
}
