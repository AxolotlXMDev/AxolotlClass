package github.axolotl.aclass.data

import org.junit.Assert.*
import org.junit.Test

class PairedPeriodSlotsTest {
    @Test fun eitherPeriodOrBothOccupyOneDot() {
        listOf("01", "02", "0102").forEach { periods ->
            val course = TimetableParser.fromRaw(testRaw(periods = periods))
            assertEquals(setOf(1 to 1), pairedPeriodSlots(listOf(course), 1, 10))
        }
    }

    @Test fun crossingPairsAndMultipleDaysKeepTheirOwnDots() {
        val crossing = TimetableParser.fromRaw(testRaw(periods = "0203"))
        val otherDay = TimetableParser.fromRaw(testRaw(day = "7", periods = "0910"))
        assertEquals(setOf(1 to 1, 1 to 2, 7 to 5), pairedPeriodSlots(listOf(crossing, otherDay), 1, 10))
    }

    @Test fun emptyAndUnscheduledWeeksHaveNoColoredDots() {
        assertTrue(pairedPeriodSlots(emptyList(), 1, 10).isEmpty())
        val course = TimetableParser.fromRaw(testRaw(weeks = "2"))
        assertTrue(pairedPeriodSlots(listOf(course), 1, 10).isEmpty())
        assertEquals(setOf(1 to 1), pairedPeriodSlots(listOf(course), 2, 10))
    }

    @Test fun oddFinalPeriodHasItsOwnPairWithoutDroppingExtraPeriods() {
        val last = TimetableParser.fromRaw(testRaw(periods = "11"))
        val preceding = TimetableParser.fromRaw(testRaw(periods = "10"))
        assertEquals(setOf(1 to 5, 1 to 6), pairedPeriodSlots(listOf(last, preceding), 1, 11))
    }

    @Test fun hiddenWeekendCourseDoesNotColorDotsButWeekdayCourseStillDoes() {
        val weekday = TimetableParser.fromRaw(testRaw())
        val weekend = TimetableParser.fromRaw(testRaw(day = "6"))
        val snapshot = testSnapshot().copy(courses = listOf(weekday, weekend))
        val state = AppState(snapshot = snapshot, settings = AppSettings(
            hiddenWeekendCourses = setOf("${snapshot.scopeKey}:${weekend.courseKey}")))
        assertEquals(setOf(1 to 1), pairedPeriodSlots(visibleCourses(state), 1, visiblePeriodCount(state)))
    }
}
