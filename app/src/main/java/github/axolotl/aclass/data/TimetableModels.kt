package github.axolotl.aclass.data

import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

val SCHOOL_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
fun schoolClock(): Clock = Clock.system(SCHOOL_ZONE)
fun stableHash(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

data class CourseRecord(
    val id: String,
    val courseKey: String,
    val name: String,
    val teacher: String,
    val location: String,
    val day: Int,
    val periods: List<Int>,
    val weeks: Set<Int>,
    val raw: JsonObject,
)

data class TimetableSnapshot(
    val studentId: String,
    val term: String,
    val updatedAt: Long,
    val courses: List<CourseRecord>,
) {
    val maxWeek: Int get() = maxOf(20, courses.maxOfOrNull { it.weeks.maxOrNull() ?: 1 } ?: 1)
    val maxPeriod: Int get() = courses.maxOfOrNull { it.periods.maxOrNull() ?: 1 } ?: 9
    val scopeKey: String get() = "$studentId:$term"
}

data class AccountSettings(val studentId: String, val password: String, val term: String)
data class PeriodTime(val start: String = "", val end: String = "")
data class CourseAppearance(val color: Long, val emoji: String = "")
const val DEFAULT_MAIN_UI_OPACITY = 0.82f

data class AppSettings(
    val termStarts: Map<String, String> = emptyMap(),
    val appearances: Map<String, CourseAppearance> = emptyMap(),
    val periods: List<PeriodTime> = DEFAULT_PERIODS,
    val backgroundPath: String? = null,
    val backgroundTransparency: Float = 0.25f,
    val useDynamicColor: Boolean = true,
    val customPrimaryColor: Long = 0xFF4E709BL,
    val hiddenWeekendCourses: Set<String> = emptySet(),
    val mainUiOpacity: Float = DEFAULT_MAIN_UI_OPACITY,
) {
    fun termStart(snapshot: TimetableSnapshot?, fallbackTerm: String): LocalDate =
        termStarts[snapshot?.scopeKey ?: ":$fallbackTerm"]?.let(LocalDate::parse)
            ?: estimateTermStart(snapshot?.term ?: fallbackTerm)

    fun isEstimated(snapshot: TimetableSnapshot?, fallbackTerm: String): Boolean =
        !termStarts.containsKey(snapshot?.scopeKey ?: ":$fallbackTerm")

    fun appearance(snapshot: TimetableSnapshot, course: CourseRecord): CourseAppearance =
        appearances["${snapshot.scopeKey}:${course.courseKey}"]
            ?: CourseAppearance(COURSE_PALETTE[Math.floorMod(course.courseKey.hashCode(), COURSE_PALETTE.size)])
}

data class AppState(
    val snapshot: TimetableSnapshot? = null,
    val settings: AppSettings = AppSettings(),
    val account: AccountSettings? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
)

val DEFAULT_PERIODS = listOf(
    PeriodTime("08:00", "08:45"), PeriodTime("08:55", "09:40"),
    PeriodTime("10:00", "10:45"), PeriodTime("10:55", "11:40"),
    PeriodTime("14:30", "15:15"), PeriodTime("15:25", "16:10"),
    PeriodTime("16:20", "17:00"), PeriodTime("17:00", "17:50"),
    PeriodTime("19:40", "20:25"),
    PeriodTime("20:35", "21:20"),
)
val COURSE_PALETTE = listOf(
    0xFFADC9F2L, 0xFFAEDCCC, 0xFFF2CAADL, 0xFFC8BBE8L,
    0xFFEFB8CCL, 0xFFA7D4E0L, 0xFFECD89EL, 0xFFC3DCABL,
    0xFFB8BFE9L, 0xFFE2C4ADL, 0xFFC6D1DDL, 0xFFE1BDE1L,
)

fun defaultTerm(today: LocalDate): String {
    val year = if (today.monthValue >= 9) today.year else today.year - 1
    return "$year${if (today.monthValue in 2..8) "02" else "01"}"
}

fun validTerm(term: String): Boolean = Regex("\\d{4}(01|02)").matches(term)
fun estimateTermStart(term: String): LocalDate {
    require(validTerm(term)) { "学期格式应为 202601 或 202602" }
    val year = term.take(4).toInt()
    val anchor = if (term.endsWith("02")) LocalDate.of(year + 1, 2, 20) else LocalDate.of(year, 9, 1)
    return anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}

fun weekOf(today: LocalDate, start: LocalDate): Int = Math.floorDiv(ChronoUnit.DAYS.between(start, today), 7L).toInt() + 1
fun dateOf(start: LocalDate, week: Int, day: Int): LocalDate = start.plusDays((week - 1) * 7L + day - 1)
fun visiblePeriodCount(state: AppState): Int = maxOf(10, state.settings.periods.size, state.snapshot?.maxPeriod ?: 10)
fun visibleCourses(state: AppState): List<CourseRecord> {
    val snapshot = state.snapshot ?: return emptyList()
    return snapshot.courses.filter { it.day <= 5 || "${snapshot.scopeKey}:${it.courseKey}" !in state.settings.hiddenWeekendCourses }
}
fun hasWeekendCourses(state: AppState, week: Int): Boolean = visibleCourses(state).any { it.day >= 6 && week in it.weeks }
fun termLabel(term: String): String = if (validTerm(term)) {
    "${term.take(4)}—${term.take(4).toInt() + 1}学年 · 第${if (term.endsWith("01")) "一" else "二"}学期"
} else term

data class CourseBlock(val start: Int, val end: Int, val courses: List<CourseRecord>)

/** One occupied dot per day and pair of periods (1–2, 3–4, ...). */
fun pairedPeriodSlots(courses: List<CourseRecord>, week: Int, periodCount: Int): Set<Pair<Int, Int>> = buildSet {
    courses.filter { week in it.weeks }.forEach { course ->
        course.periods.filter { it in 1..periodCount }.forEach { period -> add(course.day to (period + 1) / 2) }
    }
}

/** Consecutive slots with identical occupants share a card; conflicts never hide a course. */
fun courseBlocks(courses: List<CourseRecord>, week: Int, day: Int, periodCount: Int): List<CourseBlock> {
    val dayCourses = courses.filter { week in it.weeks && it.day == day }.sortedBy { it.id }
    val result = mutableListOf<CourseBlock>()
    for (period in 1..periodCount) {
        val occupants = dayCourses.filter { period in it.periods }
        if (occupants.isEmpty()) continue
        val previous = result.lastOrNull()
        if (previous != null && previous.end == period - 1 && previous.courses == occupants) {
            result[result.lastIndex] = previous.copy(end = period)
        } else result += CourseBlock(period, period, occupants)
    }
    return result
}

fun compactNumbers(numbers: Collection<Int>): String {
    val sorted = numbers.distinct().sorted()
    if (sorted.isEmpty()) return "—"
    val chunks = mutableListOf<String>()
    var start = sorted.first()
    var end = start
    for (next in sorted.drop(1)) {
        if (next == end + 1) end = next else {
            chunks += if (start == end) "$start" else "$start—$end"
            start = next
            end = next
        }
    }
    chunks += if (start == end) "$start" else "$start—$end"
    return chunks.joinToString("、")
}
