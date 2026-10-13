package github.axolotl.aclass.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

data class CourseOccurrence(
    val course: CourseRecord,
    val week: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val startsAt: ZonedDateTime,
) {
    fun matches(block: CourseBlock, week: Int, day: Int): Boolean =
        this.week == week && course.day == day &&
            block.courses.any { it.id == course.id } &&
            block.start <= endPeriod && block.end >= startPeriod
}

/** Compute from original records, not drawing blocks split by overlapping occupants. */
fun courseOccurrences(state: AppState, termStart: LocalDate): List<CourseOccurrence> = buildList {
    visibleCourses(state).forEach { course ->
        val segments = mutableListOf<IntRange>()
        course.periods.distinct().sorted().forEach { period ->
            val previous = segments.lastOrNull()
            if (previous != null && previous.last + 1 == period) {
                segments[segments.lastIndex] = previous.first..period
            } else segments += period..period
        }
        segments.forEach segmentLoop@ { segment ->
            val start = state.settings.periods.getOrNull(segment.first - 1)?.start
                ?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return@segmentLoop
            course.weeks.forEach { week ->
                add(CourseOccurrence(course, week, segment.first, segment.last,
                    dateOf(termStart, week, course.day).atTime(start).atZone(SCHOOL_ZONE)))
            }
        }
    }
}.sortedWith(compareBy({ it.startsAt.toInstant() }, { it.course.name }, { it.course.id }, { it.startPeriod }))

fun nextCourseOccurrences(occurrences: List<CourseOccurrence>, now: Instant, highlightMinutes: Int): List<CourseOccurrence> {
    val minutes = highlightMinutes.takeIf { it in 0..60 } ?: DEFAULT_HIGHLIGHT_MINUTES
    val eligible = occurrences.filter { now.isBefore(it.startsAt.plusMinutes(minutes.toLong()).toInstant()) }
    val earliest = eligible.minOfOrNull { it.startsAt.toInstant() } ?: return emptyList()
    return eligible.filter { it.startsAt.toInstant() == earliest }
}
