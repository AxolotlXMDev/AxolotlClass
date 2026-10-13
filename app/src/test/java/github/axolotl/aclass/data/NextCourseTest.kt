package github.axolotl.aclass.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class NextCourseTest {
    private val monday = LocalDate.of(2026, 10, 12)
    private fun course(name: String = "数学", periods: String = "0102", day: Int = 1, weeks: String = "1"): CourseRecord =
        TimetableParser.fromRaw(buildJsonObject {
            put("kcmc", name); put("kcdm", name); put("xq", day); put("jcdm", periods); put("zc", weeks)
        })
    private fun state(vararg courses: CourseRecord, settings: AppSettings = AppSettings()) =
        AppState(snapshot = TimetableSnapshot("test", "202601", 0, courses.toList()), settings = settings, loading = false)
    private fun at(time: String, date: LocalDate = monday): Instant = date.atTime(java.time.LocalTime.parse(time)).atZone(SCHOOL_ZONE).toInstant()
    private fun next(state: AppState, time: String, date: LocalDate = monday) =
        nextCourseOccurrences(courseOccurrences(state, monday), at(time, date), state.settings.highlightMinutes)

    @Test fun retainsFirstClassUntilExactDeadlineThenAdvances() {
        val morning = course()
        val later = course("英语", "0304")
        val state = state(morning, later)
        listOf("07:59:59", "08:00", "08:09:59").forEach { assertEquals(morning, next(state, it).single().course) }
        assertEquals(later, next(state, "08:10").single().course)
        assertTrue(next(state, "10:10").isEmpty())
        assertEquals(later, next(state.copy(settings = AppSettings(highlightMinutes = 0)), "08:00").single().course)
        assertEquals(morning, next(state.copy(settings = AppSettings(highlightMinutes = 30)), "08:29:59").single().course)
        assertEquals(later, next(state.copy(settings = AppSettings(highlightMinutes = 30)), "08:30").single().course)
    }

    @Test fun consecutiveClassesAreOneOccurrenceButSeparateSegmentsAreIndependent() {
        val continuous = course(periods = "01020304")
        val separated = course("分段", "01,03")
        val all = courseOccurrences(state(continuous, separated), monday)
        assertEquals(listOf(1 to 4), all.filter { it.course == continuous }.map { it.startPeriod to it.endPeriod })
        assertEquals(listOf(1 to 1, 3 to 3), all.filter { it.course == separated }.map { it.startPeriod to it.endPeriod })
        assertEquals(separated, next(state(continuous, separated), "08:10").single().course)
        assertEquals(3, next(state(continuous, separated), "08:10").single().startPeriod)
    }

    @Test fun overlappingDrawingBlocksDoNotManufactureLaterStarts() {
        val long = course(periods = "01020304")
        val middle = course("重叠", "0203")
        val state = state(long, middle)
        assertEquals(3, courseBlocks(visibleCourses(state), 1, 1, 10).size)
        assertEquals(2, courseOccurrences(state, monday).size)
        assertEquals(middle, next(state, "08:10").single().course)
        assertTrue(next(state, "09:05").isEmpty())
    }

    @Test fun simultaneousClassesMatchAllOfTheirSplitCardsOnlyOnCorrectDate() {
        val first = course()
        val other = course("冲突", "010203")
        val targets = next(state(first, other), "07:00")
        assertEquals(2, targets.size)
        val blocks = courseBlocks(listOf(first, other), 1, 1, 10)
        assertTrue(blocks.all { block -> targets.any { it.matches(block, 1, 1) } })
        assertFalse(targets.any { it.matches(blocks.first(), 2, 1) })
        assertFalse(targets.any { it.matches(blocks.first(), 1, 2) })
        assertFalse(targets.any { it.matches(CourseBlock(5, 6, listOf(first)), 1, 1) })
    }

    @Test fun weekendFilteringPreservesWeekdaySameCourseAndSearchesAcrossWeeks() {
        val weekend = course(day = 6)
        val weekday = course(weeks = "2")
        val saturdayState = state(weekend, weekday)
        assertEquals(weekend, next(saturdayState, "18:00", monday.plusDays(4)).single().course)
        val hidden = saturdayState.copy(settings = AppSettings(hiddenWeekendCourses = setOf("test:202601:${weekend.courseKey}")))
        assertEquals(weekday, next(hidden, "18:00", monday.plusDays(4)).single().course)
        assertEquals(2, next(hidden, "18:00", monday.plusDays(4)).single().week)
        assertTrue(next(hidden, "08:10", monday.plusWeeks(1)).isEmpty())
    }

    @Test fun missingFirstPeriodTimeSkipsWholeSegmentAndEmptyOrEndedTermsHaveNoTarget() {
        val record = course()
        val settings = AppSettings(periods = DEFAULT_PERIODS.toMutableList().apply { set(0, PeriodTime()) })
        assertTrue(next(state(record, settings = settings), "07:00").isEmpty())
        assertTrue(next(state(), "07:00").isEmpty())
        assertTrue(courseOccurrences(AppState(), monday).isEmpty())
        assertEquals(record, next(state(record), "07:00", monday.minusWeeks(1)).single().course)
        assertTrue(next(state(record), "07:00", monday.plusWeeks(1)).isEmpty())
    }

    @Test fun customPeriodTimesAndYearBoundaryUseShanghaiTime() {
        val state = state(course(day = 2, weeks = "2"), settings = AppSettings(periods = DEFAULT_PERIODS.toMutableList().apply {
            set(0, PeriodTime("08:20", "09:05"))
        }))
        val start = LocalDate.of(2026, 12, 28)
        val result = courseOccurrences(state, start).single()
        assertEquals(LocalDate.of(2027, 1, 5), result.startsAt.toLocalDate())
        assertEquals(Instant.parse("2027-01-05T00:20:00Z"), result.startsAt.toInstant())
    }
}
