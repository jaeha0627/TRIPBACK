package com.tripback.app.trip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripInputTest {
    @Test fun acceptsDefaultAndMoreThanTwoHours() {
        assertTrue(TripInput().validationErrors().isEmpty())
        assertTrue(TripInput(departTime = "08:00", returnDeadline = "10:01").validationErrors().isEmpty())
    }

    @Test fun rejectsTwoHoursOrLess() {
        listOf("10:00", "09:59").forEach {
            assertFalse(TripInput(departTime = "08:00", returnDeadline = it).validationErrors().isEmpty())
        }
    }

    @Test fun rejectsMalformedAndOutOfRangeTimes() {
        listOf("8:30", "24:00", "12:60", "", "ab:cd", "08:30 ").forEach {
            assertFalse(TripInput(departTime = it).validationErrors().isEmpty())
            assertFalse(TripInput(returnDeadline = it).validationErrors().isEmpty())
        }
    }

    @Test fun rejectsOvernightAndEqualTimes() {
        assertFalse(TripInput(departTime = "23:00", returnDeadline = "03:00").validationErrors().isEmpty())
        assertFalse(TripInput(departTime = "08:30", returnDeadline = "08:30").validationErrors().isEmpty())
        assertTrue(TripInput(departTime = "00:00", returnDeadline = "23:59").validationErrors().isEmpty())
    }

    @Test fun summaryReflectsOptionalValuesAndHidesChildAgeForOtherCompanions() {
        val summary = TripInput(companionType = "SOLO", budgetPerPersonKrw = 50000,
            mealPreferences = setOf("VEGETARIAN"), preferIndoorWhenRaining = true).summary()
        assertTrue(summary.contains("혼자"))
        assertFalse(summary.contains("초등학생"))
        assertTrue(summary.contains("50000원 이하"))
        assertTrue(summary.contains("채식"))
        assertTrue(summary.contains("우천 시 실내 선호: 예"))
    }
}
