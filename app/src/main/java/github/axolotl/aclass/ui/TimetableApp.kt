package github.axolotl.aclass.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import github.axolotl.aclass.TimetableViewModel
import github.axolotl.aclass.data.*
import github.axolotl.aclass.ui.theme.AxolotlClassTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate
import java.time.Instant
import java.time.format.DateTimeFormatter

val DAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val SHORT_DATE = DateTimeFormatter.ofPattern("M/d")

@Composable
fun TimetableApp(model: TimetableViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    ReportDrawnWhen { !state.loading }
    AxolotlClassTheme(dynamicColor = state.settings.useDynamicColor, customPrimaryColor = Color(state.settings.customPrimaryColor.toInt())) {
        TimetableAppContent(model, state)
    }
}

@Composable
private fun TimetableAppContent(model: TimetableViewModel, state: AppState) {
    val selectedWeek by model.selectedWeek.collectAsStateWithLifecycle()
    val today by model.today.collectAsStateWithLifecycle()
    val now by model.now.collectAsStateWithLifecycle()
    val working by model.working.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var weekPickerOpen by rememberSaveable { mutableStateOf(false) }
    var detail by rememberSaveable(stateSaver = appearanceSelectionSaver) { mutableStateOf<AppearanceSelection?>(null) }
    var conflicts by remember { mutableStateOf<List<CourseRecord>>(emptyList()) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(model) { model.events.collect { snackbar.showSnackbar(it) } }
    val term = state.snapshot?.term ?: state.account?.term ?: defaultTerm(today)
    val start = state.settings.termStart(state.snapshot, term)
    val maxWeek = state.snapshot?.maxWeek ?: 20
    val currentWeek = weekOf(today, start)
    val week = (selectedWeek ?: currentWeek).coerceIn(1, maxWeek)
    val periodCount = visiblePeriodCount(state)
    val occurrences = remember(state.snapshot, state.settings.hiddenWeekendCourses, state.settings.periods, start) {
        courseOccurrences(state, start)
    }
    val nextCourses = remember(occurrences, now, state.settings.highlightMinutes) {
        nextCourseOccurrences(occurrences, now, state.settings.highlightMinutes)
    }
    fun showDetails(course: CourseRecord) {
        state.snapshot?.let { detail = AppearanceSelection(it.scopeKey, course, state.settings.appearance(it, course)) }
    }

    BackHandler(settingsOpen) { settingsOpen = false }
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            if (!settingsOpen) TimetableBackground(state.settings)
            if (settingsOpen) {
                SettingsScreen(state, today, working, model, Modifier.padding(padding), onBack = { settingsOpen = false })
            } else {
                TimetableScreen(
                    state, week, currentWeek, today, start, periodCount, Modifier.padding(padding),
                    onSettings = { settingsOpen = true }, onWeeks = { weekPickerOpen = true },
                    onCurrent = model::currentWeek,
                    onSelectWeek = model::selectWeek,
                    onCourses = { courses -> if (courses.size == 1) showDetails(courses.first()) else conflicts = courses },
                    nextCourses = nextCourses, now = now,
                )
            }
        }
    }
    if (weekPickerOpen) WeekPicker(state, week, currentWeek, start, periodCount,
        onSelect = { model.selectWeek(it); weekPickerOpen = false }, onDismiss = { weekPickerOpen = false })
    detail?.let { selection ->
        key(selection.scopeKey, selection.course.courseKey) {
            CourseDetails(selection, state, week, start, !working,
                onSave = { appearance, complete -> model.saveAppearance(selection.scopeKey, selection.course, appearance, complete) },
                onDismiss = { detail = null })
        }
    }
    if (conflicts.isNotEmpty()) ConflictPicker(conflicts,
        onSelect = { showDetails(it); conflicts = emptyList() }, onDismiss = { conflicts = emptyList() })
}

@Composable
private fun TimetableBackground(settings: AppSettings) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.background, colors.surface))))
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, settings.backgroundPath) {
        value = null
        val path = settings.backgroundPath
        if (path != null) value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }
    bitmap?.let {
        Image(it, contentDescription = null, contentScale = ContentScale.Crop,
            alpha = 1f - settings.backgroundTransparency, modifier = Modifier.fillMaxSize().testTag("timetable-background-image"))
    }
}

@Composable
fun TimetableScreen(
    state: AppState, week: Int, currentWeek: Int, today: LocalDate, start: LocalDate, periodCount: Int,
    modifier: Modifier = Modifier,
    onSettings: () -> Unit, onWeeks: () -> Unit, onCurrent: () -> Unit,
    onSelectWeek: (Int) -> Unit, onCourses: (List<CourseRecord>) -> Unit,
    nextCourses: List<CourseOccurrence> = emptyList(), now: Instant = Instant.EPOCH,
) {
    val colors = MaterialTheme.colorScheme
    val maxWeek = state.snapshot?.maxWeek ?: 20
    var weekendOverrideWeek by rememberSaveable { mutableStateOf(week) }
    var weekendOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val weekendsExpanded = weekendOverride.takeIf { weekendOverrideWeek == week } ?: hasWeekendCourses(state, week)
    val pager = rememberPagerState(initialPage = week - 1, pageCount = { maxWeek })
    LaunchedEffect(week, maxWeek) {
        if (pager.currentPage != week - 1) pager.scrollToPage(week - 1)
    }
    val selectLatest by rememberUpdatedState(onSelectWeek)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.drop(1).distinctUntilChanged().collect { selectLatest(it + 1) }
    }
    Column(modifier.fillMaxSize().padding(horizontal = 3.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp).background(colors.surface.copy(alpha = state.settings.mainUiOpacity), RoundedCornerShape(12.dp)),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { weekendOverrideWeek = week; weekendOverride = !weekendsExpanded }, contentPadding = PaddingValues(4.dp),
                modifier = Modifier.width(52.dp).testTag("toggle-weekend")) {
                Text(if (weekendsExpanded) "收起" else "周末＋", fontSize = 11.sp)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("第 $week 周 ⌄", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.clickable(onClick = onWeeks).testTag("week-title").padding(horizontal = 12.dp, vertical = 8.dp))
            }
            IconButton(onClick = onSettings, modifier = Modifier.testTag("open-settings").semantics { contentDescription = "设置" }) {
                Text("⚙", fontSize = 22.sp, color = colors.primary)
            }
        }
        if (nextCourses.isNotEmpty()) NextCourseHint(nextCourses, now, state.settings.mainUiOpacity) {
            val target = nextCourses.first()
            weekendOverrideWeek = target.week
            weekendOverride = if (target.course.day >= 6) true else null
            onSelectWeek(target.week)
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth().testTag("week-pager"),
            key = { it }, verticalAlignment = Alignment.Top) { page ->
            val expanded = if (page + 1 == week) weekendsExpanded else hasWeekendCourses(state, page + 1)
            WeekGrid(state, page + 1, today, start, periodCount, if (expanded) 7 else 5, onCourses, onSettings, nextCourses, now)
        }
    }
}

@Composable
private fun NextCourseHint(courses: List<CourseOccurrence>, now: Instant, opacity: Float, onClick: () -> Unit) {
    val target = courses.first()
    val status = if (now >= target.startsAt.toInstant()) "已开始" else "下一节"
    val names = courses.joinToString("、") { it.course.name }
    val locations = courses.map { it.course.location.ifBlank { "地点待定" } }.distinct().joinToString("、")
    val date = target.startsAt.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
    val timeAndPlace = "$date · ${DAY_NAMES[target.course.day - 1]} · $locations"
    val title = "$status：$names${if (courses.size > 1) "（${courses.size}门重叠）" else ""}"
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)
        .background(MaterialTheme.colorScheme.surface.copy(alpha = opacity), RoundedCornerShape(10.dp))
        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "定位到课程所在周", onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = "$title，$timeAndPlace，点击定位" }
        .testTag("next-course-hint").padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(timeAndPlace, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun WeekGrid(state: AppState, week: Int, today: LocalDate, start: LocalDate, periodCount: Int, dayCount: Int,
                     onCourses: (List<CourseRecord>) -> Unit, onSettings: () -> Unit,
                     nextCourses: List<CourseOccurrence>, now: Instant) {
    val colors = MaterialTheme.colorScheme
    val courses = remember(state.snapshot, state.settings.hiddenWeekendCourses) { visibleCourses(state) }
    val weekCourses = remember(courses, week) { courses.filter { week in it.weeks } }
    val fontScale = LocalDensity.current.fontScale
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(vertical = 3.dp)
            .background(colors.surface.copy(alpha = state.settings.mainUiOpacity), RoundedCornerShape(10.dp))) {
            Box(Modifier.width(38.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text("${dateOf(start, week, 1).monthValue}\n月", fontSize = 11.sp, lineHeight = 14.sp,
                    color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            for (day in 1..dayCount) {
                val date = dateOf(start, week, day)
                val isToday = date == today
                Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 2.dp)
                    .clip(RoundedCornerShape(8.dp)).background(if (isToday) colors.primary else Color.Transparent)
                    .semantics { contentDescription = "${DAY_NAMES[day - 1]} ${date}${if (isToday) " 今天" else ""}" }
                    .testTag(if (isToday) "today-header" else "day-header-$day"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(DAY_NAMES[day - 1], fontSize = 12.sp, lineHeight = 16.sp,
                        color = if (isToday) colors.onPrimary else colors.onSurface,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                    Text(date.format(SHORT_DATE), fontSize = 10.sp, lineHeight = 14.sp,
                        color = if (isToday) colors.onPrimary else colors.onSurfaceVariant, maxLines = 1)
                    if (isToday) Text("今天", fontSize = 8.sp, lineHeight = 10.sp, color = colors.onPrimary,
                        fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("timetable-grid")) {
            val rowHeight = maxHeight / (periodCount + 1)
            val labelScale = (rowHeight.value / 48f).coerceAtMost(1f) / fontScale.coerceAtLeast(1f)
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(38.dp).fillMaxHeight()
                    .background(colors.surface.copy(alpha = state.settings.mainUiOpacity), RoundedCornerShape(8.dp))) {
                    for (index in 0 until periodCount) {
                        val period = state.settings.periods.getOrNull(index)
                        Column(Modifier.height(rowHeight).fillMaxWidth().testTag("period-$week-${index + 1}")
                            .padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${index + 1}", fontSize = (14f * labelScale).sp,
                                lineHeight = (17f * labelScale).sp, color = colors.onSurfaceVariant, fontWeight = FontWeight.Medium)
                            if (period != null && period.start.isNotBlank()) {
                                Text(period.start, fontSize = (8f * labelScale).sp, lineHeight = (10f * labelScale).sp, color = colors.onSurfaceVariant, maxLines = 1)
                                Text(period.end, fontSize = (8f * labelScale).sp, lineHeight = (10f * labelScale).sp, color = colors.onSurfaceVariant, maxLines = 1)
                            } else Text("待设置", fontSize = (8f * labelScale).sp, lineHeight = (10f * labelScale).sp, color = colors.onSurfaceVariant, maxLines = 1)
                        }
                    }
                    Column(Modifier.height(rowHeight).fillMaxWidth().testTag("period-$week-end").padding(top = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("*", fontSize = (14f * labelScale).sp, lineHeight = (17f * labelScale).sp, color = colors.onSurfaceVariant)
                        Text("21:20", fontSize = (8f * labelScale).sp, lineHeight = (10f * labelScale).sp, color = colors.onSurfaceVariant)
                        Text("end", fontSize = (8f * labelScale).sp, lineHeight = (10f * labelScale).sp, color = colors.onSurfaceVariant)
                    }
                }
                for (day in 1..dayCount) {
                    val blocks = remember(courses, week, day, periodCount) { courseBlocks(courses, week, day, periodCount) }
                    val isToday = dateOf(start, week, day) == today
                    Box(Modifier.weight(1f).fillMaxHeight().background(
                        if (isToday) colors.primary.copy(alpha = 0.12f) else Color.Transparent)
                        .then(if (isToday) Modifier.testTag("today-column") else Modifier)) {
                        Canvas(Modifier.matchParentSize()) {
                            for (row in 1..periodCount) drawLine(colors.outlineVariant.copy(alpha = 0.1f),
                                androidx.compose.ui.geometry.Offset(0f, row * rowHeight.toPx()),
                                androidx.compose.ui.geometry.Offset(size.width, row * rowHeight.toPx()))
                            if (isToday) {
                                val stroke = 2.dp.toPx()
                                drawLine(colors.primary.copy(alpha = 0.7f),
                                    androidx.compose.ui.geometry.Offset(stroke / 2, 0f),
                                    androidx.compose.ui.geometry.Offset(stroke / 2, size.height), strokeWidth = stroke)
                                drawLine(colors.primary.copy(alpha = 0.7f),
                                    androidx.compose.ui.geometry.Offset(size.width - stroke / 2, 0f),
                                    androidx.compose.ui.geometry.Offset(size.width - stroke / 2, size.height), strokeWidth = stroke)
                            }
                        }
                        blocks.forEach { block ->
                            val course = block.courses.first()
                            val appearance = state.snapshot?.let { state.settings.appearance(it, course) } ?: CourseAppearance(COURSE_PALETTE.first())
                            val highlighted = nextCourses.filter { it.matches(block, week, day) }
                            CourseCard(block, appearance, rowHeight * (block.end - block.start + 1),
                                compact = dayCount == 7,
                                Modifier.offset(y = rowHeight * (block.start - 1))
                                    .height(rowHeight * (block.end - block.start + 1)).fillMaxWidth()
                                    .padding(if (dayCount == 7) 1.5.dp else 2.dp)
                                    .testTag("course-${course.id}-${block.start}"), onClick = { onCourses(block.courses) },
                                highlightLabel = if (highlighted.isEmpty()) null else
                                    if (now >= highlighted.first().startsAt.toInstant()) "已开始，当前高亮课程" else "下一节课程")
                        }
                    }
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter).testTag("loading-cache"))
            else if (state.snapshot == null) {
                Column(Modifier.align(Alignment.Center).background(colors.surface.copy(alpha = 0.94f), RoundedCornerShape(14.dp))
                    .padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有课表", style = MaterialTheme.typography.bodyMedium)
                    Text("在设置中登录教务账号即可获取", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onSettings) { Text("前往设置登录") }
                }
            } else if (weekCourses.isEmpty()) {
                Text("本周没有课程", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).background(colors.surface.copy(alpha = 0.8f), RoundedCornerShape(12.dp)).padding(12.dp))
            }
        }
    }
}

@Composable
private fun CourseCard(block: CourseBlock, appearance: CourseAppearance, height: androidx.compose.ui.unit.Dp,
                       compact: Boolean, modifier: Modifier, onClick: () -> Unit, highlightLabel: String? = null) {
    val base = Color(appearance.color.toInt())
    val ink = courseInk(base)
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val textSize = minOf(12f, maxOf(8f, height.value / 8f)) / scale
    val compactHeight = height.value < 70
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(base.copy(alpha = COURSE_CARD_ALPHA)).clickable(onClick = onClick)
        .then(if (highlightLabel != null) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
            .border(1.dp, ink, RoundedCornerShape(8.dp)) else Modifier)
        .semantics {
            contentDescription = block.courses.joinToString("，") { "${it.name}，${it.location}" }
            if (highlightLabel != null) stateDescription = highlightLabel
        }
        .padding(horizontal = if (compact) 3.dp else 5.dp, vertical = if (height.value < 60) 3.dp else 7.dp)) {
        if (block.courses.size > 1) Text("${block.courses.size}门重叠", color = ink,
            fontSize = (8f / scale).sp, lineHeight = (10f / scale).sp, fontWeight = FontWeight.Bold, maxLines = 1)
        else if (!compactHeight && appearance.emoji.isNotBlank()) Text(appearance.emoji, fontSize = (13f / scale).sp,
            lineHeight = (16f / scale).sp, maxLines = 1)
        val title = if (compactHeight && block.courses.size == 1 && appearance.emoji.isNotBlank())
            "${appearance.emoji} ${block.courses.first().name}" else block.courses.first().name
        Text(title, fontSize = textSize.sp, lineHeight = (textSize * 1.25f).sp,
            fontWeight = FontWeight.SemiBold, color = ink,
            maxLines = if (block.end > block.start) 5 else 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).testTag("course-title"))
        Spacer(Modifier.height(2.dp))
        Text(block.courses.first().location.ifBlank { "地点待定" }, fontSize = (10f / scale).sp,
            lineHeight = (12f / scale).sp, color = ink, fontWeight = FontWeight.Medium,
            maxLines = if (height.value > 75) 2 else 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("course-location"))
    }
}
