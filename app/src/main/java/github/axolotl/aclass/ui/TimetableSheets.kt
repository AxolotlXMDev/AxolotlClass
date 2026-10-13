package github.axolotl.aclass.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import github.axolotl.aclass.data.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekPicker(state: AppState, selected: Int, current: Int, start: LocalDate, periodCount: Int,
               onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val maxWeek = state.snapshot?.maxWeek ?: 20
    val courses = remember(state.snapshot, state.settings.hiddenWeekendCourses) { visibleCourses(state) }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = ((selected - 1) / 3) * 3)
    val colors = MaterialTheme.colorScheme
    val groupCount = (periodCount + 1) / 2
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("选择周次", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { onSelect(current.coerceIn(1, maxWeek)) }) { Text("回到本周") }
        }
        Text("圆点按星期 × 两节一组排列（1–2、3–4…），组内有课即着色", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = colors.onSurfaceVariant)
        if (current < 1 || current > maxWeek) Text(if (current < 1) "尚未开学" else "学期已结束",
            style = MaterialTheme.typography.labelMedium, color = colors.primary, modifier = Modifier.padding(horizontal = 20.dp))
        LazyVerticalGrid(columns = GridCells.Fixed(3), state = gridState, modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)
            .testTag("week-picker"), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items((1..maxWeek).toList(), key = { it }) { week ->
                val weekCourses = remember(courses, week) { courses.filter { week in it.weeks } }
                val slots = remember(weekCourses, periodCount) { pairedPeriodSlots(weekCourses, week, periodCount) }
                Column(Modifier.fillMaxWidth().background(if (week == selected) colors.primaryContainer else colors.surfaceContainer,
                    RoundedCornerShape(12.dp)).border(if (week == selected) 2.dp else 0.dp,
                    if (week == selected) colors.primary else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable { onSelect(week) }.testTag("select-week-$week")
                    .semantics { contentDescription = "第${week}周，${weekCourses.size}次课${if (week == current) "，本周" else ""}${if (week == selected) "，已选中" else ""}" }
                    .padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("第${week}周${if (week == current) " · 本周" else ""}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text("${dateOf(start, week, 1).format(DateTimeFormatter.ofPattern("M/d"))} 起", style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant)
                    Canvas(Modifier.fillMaxWidth().height((groupCount * 12).coerceIn(60, 150).dp).padding(vertical = 6.dp)) {
                        val dx = size.width / 7
                        val dy = size.height / groupCount
                        val radius = minOf(dx, dy) * 0.28f
                        for (day in 1..7) for (group in 1..groupCount) {
                            drawCircle(if (day to group in slots) colors.primary else colors.outlineVariant.copy(alpha = 0.6f),
                                radius, Offset((day - 0.5f) * dx, (group - 0.5f) * dy))
                        }
                    }
                    Text("${weekCourses.size} 次课", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CourseDetails(selection: AppearanceSelection, state: AppState, week: Int, start: LocalDate,
                           enabled: Boolean, onSave: (CourseAppearance, (Result<Unit>) -> Unit) -> Unit, onDismiss: () -> Unit) {
    val course = selection.course
    var rawExpanded by remember { mutableStateOf(false) }
    val appearance = state.settings.appearances["${selection.scopeKey}:${course.courseKey}"] ?: selection.appearance
    val emoji = appearance.emoji
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
            .padding(bottom = 28.dp).testTag("course-details"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(listOf(emoji, course.name).filter(String::isNotBlank).joinToString(" "), style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
            CourseAppearanceForm(course, selection.appearance, enabled, onSave)
            HorizontalDivider()
            DetailRow("任课教师", course.teacher.ifBlank { "待定" })
            DetailRow("上课地点", course.location.ifBlank { "待定" })
            DetailRow("上课日期", "${dateOf(start, week, course.day)} · ${DAY_NAMES[course.day - 1]}")
            DetailRow("节次", "第${compactNumbers(course.periods)}节")
            val times = course.periods.map { period ->
                val time = state.settings.periods.getOrNull(period - 1)
                "第${period}节 " + if (time != null && time.start.isNotBlank()) "${time.start}～${time.end}" else "时间待设置"
            }
            DetailRow("具体时间", times.joinToString("\n"))
            DetailRow("课程周次", "第${compactNumbers(course.weeks)}周")
            DetailRow("学年学期", termLabel(state.snapshot?.term ?: course.raw.text("xnxqdm")))
            HorizontalDivider()
            TextButton(onClick = { rawExpanded = !rawExpanded }, modifier = Modifier.fillMaxWidth()) {
                Text(if (rawExpanded) "收起其他信息 ▴" else "其他信息与原始字段 ▾")
            }
            if (rawExpanded) course.raw.forEach { (key, value) ->
                DetailRow(RAW_LABELS[key] ?: key, (value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: value.toString())
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictPicker(courses: List<CourseRecord>, onSelect: (CourseRecord) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("此时段有 ${courses.size} 门课程", style = MaterialTheme.typography.titleLarge)
            courses.forEach { course ->
                OutlinedButton(onClick = { onSelect(course) }, modifier = Modifier.fillMaxWidth().testTag("select-conflict-${course.id}")) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(course.name, fontWeight = FontWeight.Bold)
                        Text("${course.teacher} · ${course.location}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private val RAW_LABELS = mapOf(
    "kcmc" to "课程名称", "teaxms" to "任课教师", "jxcdmc" to "上课地点", "xq" to "星期",
    "jcdm" to "节次代码", "zc" to "周次", "xnxqdm" to "学期代码", "xh" to "学号",
    "kcdm" to "课程代码", "kcbh" to "课程编号", "xf" to "学分", "kcxz" to "课程性质",
    "kclb" to "课程类别", "jxbmc" to "教学班", "zxs" to "总学时", "kkbm" to "开课单位", "bz" to "备注",
)
