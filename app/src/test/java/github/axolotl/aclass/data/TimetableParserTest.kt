package github.axolotl.aclass.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

fun testRaw(name: String = "计算机网络", day: String = "1", periods: String = "0102", weeks: String = "1", location: String = "二教302"): JsonObject = buildJsonObject {
    put("kcmc", name); put("teaxms", "李老师"); put("jxcdmc", location); put("xq", day)
    put("jcdm", periods); put("zc", weeks); put("kcdm", name); put("xnxqdm", "202601"); put("xf", "3.5")
}

fun testSnapshot(week: String = "1"): TimetableSnapshot = TimetableSnapshot("student", "202601", 1234,
    listOf(TimetableParser.fromRaw(testRaw(weeks = week))))

class TimetableParserTest {
    @Test fun nestedRowsAndNumericPeriodKeepLeadingZeroMeaning() {
        val raw = JsonObject(testRaw().toMutableMap().apply { put("jcdm", JsonPrimitive(506)) })
        val courses = TimetableParser.parse("[[$raw]]")
        assertEquals(listOf(5, 6), courses.single().periods)
        assertEquals("3.5", courses.single().raw.text("xf"))
    }

    @Test fun wrappersFlatArraysAndEmptyTablesAreSupported() {
        val raw = testRaw()
        listOf("[$raw]", "{\"rows\":[$raw]}", "[{\"items\":[$raw]}]", "{\"data\":[[$raw]]}").forEach {
            assertEquals(1, TimetableParser.parse(it).size)
        }
        listOf("[]", "[[]]", "{\"rows\":[]}").forEach { assertTrue(TimetableParser.parse(it).isEmpty()) }
    }

    @Test fun malformedResponsesCannotClearCache() {
        listOf("", "<html>登录</html>", "{}", "{\"status\":\"n\"}", "[5]", "[[{}]]").forEach { text ->
            assertTrue(text, runCatching { TimetableParser.parse(text) }.isFailure)
        }
    }

    @Test fun exactDuplicatesCountOnlyOnce() {
        val raw = testRaw()
        assertEquals(1, TimetableParser.parse("[[$raw,$raw]]").size)
    }

    @Test fun sparsePeriodsRemainSparseAndRangesExpand() {
        assertEquals(listOf(1, 3), TimetableParser.parsePeriods("0103"))
        assertEquals(listOf(1, 2, 3, 5), TimetableParser.parsePeriods("1-3、5"))
        assertEquals(listOf(9, 10), TimetableParser.parsePeriods("0910"))
        assertEquals(listOf(1), TimetableParser.parsePeriods("1"))
    }

    @Test fun weekRangesListsAndOddEvenWork() {
        assertEquals(setOf(1, 3, 5, 7), TimetableParser.parseWeeks("第1-8周(单)"))
        assertEquals(setOf(2, 4, 6, 8, 12), TimetableParser.parseWeeks("1～8双周、12"))
        assertEquals(setOf(1, 2, 5, 6, 7), TimetableParser.parseWeeks("1,2,5-7"))
    }

    @Test fun invalidWeekAndPeriodFormatsFailClearly() {
        listOf("0", "100", "8-2", "all", "1-8单双周").forEach { assertTrue(runCatching { TimetableParser.parseWeeks(it) }.isFailure) }
        listOf("00", "100", "3-1", "unknown").forEach { assertTrue(runCatching { TimetableParser.parsePeriods(it) }.isFailure) }
    }

    @Test fun sundayBelongsToPreviousMondayAndDatesCrossYear() {
        assertEquals(LocalDate.of(2023, 8, 28), estimateTermStart("202301")) // Sep 1 Friday
        assertEquals(LocalDate.of(2024, 8, 26), estimateTermStart("202401")) // Sep 1 Sunday
        assertEquals(1, weekOf(LocalDate.of(2026, 9, 6), LocalDate.of(2026, 8, 31)))
        assertEquals(2, weekOf(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 8, 31)))
        assertEquals(0, weekOf(LocalDate.of(2026, 8, 30), LocalDate.of(2026, 8, 31)))
        assertEquals(LocalDate.of(2027, 1, 3), dateOf(LocalDate.of(2026, 12, 28), 1, 7))
    }

    @Test fun defaultTermHandlesJanuaryAndSecondSemester() {
        assertEquals("202601", defaultTerm(LocalDate.of(2027, 1, 8)))
        assertEquals("202602", defaultTerm(LocalDate.of(2027, 2, 8)))
        assertEquals("202701", defaultTerm(LocalDate.of(2027, 9, 8)))
    }

    @Test fun layoutDoesNotBridgeGapsAndPreservesConflicts() {
        val sparse = TimetableParser.fromRaw(testRaw(periods = "0103"))
        val other = TimetableParser.fromRaw(testRaw(name = "另一门课", periods = "0304"))
        val blocks = courseBlocks(listOf(sparse, other), 1, 1, 9)
        assertEquals(listOf(1, 3, 4), blocks.map { it.start })
        assertEquals(listOf(1, 3, 4), blocks.map { it.end })
        assertEquals(2, blocks[1].courses.size)
        assertTrue(courseBlocks(listOf(sparse), 2, 1, 9).isEmpty())
    }

    @Test fun contiguousClassesMergeButCountAsOneOccurrence() {
        val course = TimetableParser.fromRaw(testRaw(periods = "010203"))
        val blocks = courseBlocks(listOf(course), 1, 1, 9)
        assertEquals(1, blocks.size)
        assertEquals(3, blocks.single().end)
    }

    @Test fun extraPeriodsAndWeeksExpandBeyondDefaults() {
        val snapshot = TimetableSnapshot("student", "202601", 0, listOf(TimetableParser.fromRaw(testRaw(periods = "1011", weeks = "23"))))
        assertEquals(23, snapshot.maxWeek)
        assertEquals(11, visiblePeriodCount(AppState(snapshot = snapshot)))
    }

    @Test fun courseAppearanceKeyStaysStableAcrossWeeksAndRooms() {
        val first = TimetableParser.fromRaw(testRaw())
        val later = TimetableParser.fromRaw(testRaw(weeks = "10", location = "实验楼"))
        assertEquals(first.courseKey, later.courseKey)
        assertNotEquals(first.id, later.id)
    }

    @Test fun weekendExclusionsOnlyHideWeekendSlotsAndDontExpandWeek() {
        val weekday = TimetableParser.fromRaw(testRaw())
        val weekend = TimetableParser.fromRaw(testRaw(day = "6"))
        val snapshot = testSnapshot().copy(courses = listOf(weekday, weekend))
        val state = AppState(snapshot = snapshot)
        assertTrue(hasWeekendCourses(state, 1))
        val excluded = state.copy(settings = AppSettings(hiddenWeekendCourses = setOf("${snapshot.scopeKey}:${weekend.courseKey}")))
        assertFalse(hasWeekendCourses(excluded, 1))
        assertEquals(listOf(weekday), visibleCourses(excluded))
        assertEquals(2, excluded.snapshot!!.courses.size)
    }
}
