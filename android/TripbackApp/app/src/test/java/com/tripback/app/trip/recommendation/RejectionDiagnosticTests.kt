package com.tripback.app.trip.recommendation

import org.junit.Assert.*
import org.junit.Test

class RejectionDiagnosticTests {
    @Test fun knownCodesHaveDistinctKoreanDescriptionsAndSafeCodes() {
        val codes = listOf("INVALID_TEXT", "UNSAFE_GENERATED_TEXT", "UNLISTED_PLACE_ID",
            "DUPLICATE_VISIT", "INVALID_VISIT_COUNT", "INTEGER_OUT_OF_RANGE", "DUPLICATE_ALTERNATIVE")
        assertEquals(codes.size, codes.map(::rejectionLabel).distinct().size)
        codes.forEach { code ->
            assertTrue(rejectionLabel(code).any { it in '가'..'힣' })
            assertEquals("거부안 index: 0 · reason: $code", rejectionDiagnosticText(0, code, true, true))
        }
        assertEquals("거부안 index: 1 · reason: INVALID_TEXT",
            rejectionDiagnosticText(1, "INVALID_TEXT", true, true))
    }
    @Test fun unknownOrSensitiveValuesNeverAppearInDiagnosticOrExplanation() {
        listOf("untrusted-title", "synthetic-address", "37.123,127.123", "place-secret",
            "Bearer synthetic-token", "INVALID_TEXT\ninjected", "").forEach { raw ->
            assertEquals("거부안 index: 미확인 · reason: UNKNOWN_REJECTION",
                rejectionDiagnosticText(99, raw, true, true))
            assertEquals("제안의 장소와 검증 결과를 확인하지 못했어요.", rejectionLabel(raw))
        }
    }
    @Test fun diagnosticsRequireBothDebugAndHttpAndBoundIndex() {
        assertNull(rejectionDiagnosticText(0, "INVALID_TEXT", false, true))
        assertNull(rejectionDiagnosticText(0, "INVALID_TEXT", true, false))
        assertNull(rejectionDiagnosticText(0, "INVALID_TEXT", false, false))
        assertEquals("거부안 index: 전체 · reason: INVALID_TEXT",
            rejectionDiagnosticText(-1, "INVALID_TEXT", true, true))
        assertEquals("거부안 index: 미확인 · reason: INVALID_TEXT",
            rejectionDiagnosticText(Int.MIN_VALUE, "INVALID_TEXT", true, true))
    }
}