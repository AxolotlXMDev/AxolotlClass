package github.axolotl.aclass.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import github.axolotl.aclass.TimetableViewModel
import github.axolotl.aclass.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen(state: AppState, today: LocalDate, working: Boolean, model: TimetableViewModel,
                   modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val account = state.account
    val term = state.snapshot?.term ?: account?.term ?: defaultTerm(today)
    var studentId by rememberSaveable(account?.studentId) { mutableStateOf(account?.studentId.orEmpty()) }
    var password by rememberSaveable(account?.password) { mutableStateOf(account?.password.orEmpty()) }
    var inputTerm by rememberSaveable(account?.term, state.snapshot?.term) { mutableStateOf(account?.term ?: term) }
    var passwordVisible by remember { mutableStateOf(false) }
    val start = state.settings.termStart(state.snapshot, term)
    var startInput by rememberSaveable(start.toString()) { mutableStateOf(start.toString()) }
    var highlightInput by rememberSaveable(state.settings.highlightMinutes) { mutableStateOf(state.settings.highlightMinutes.toString()) }
    var transparency by remember(state.settings.backgroundTransparency) { mutableFloatStateOf(state.settings.backgroundTransparency) }
    var mainUiOpacity by remember(state.settings.mainUiOpacity) { mutableFloatStateOf(state.settings.mainUiOpacity) }
    var expanded by rememberSaveable { mutableStateOf(if (!state.loading && state.snapshot == null) "account" else "") }
    var initialized by rememberSaveable { mutableStateOf(!state.loading) }
    LaunchedEffect(state.loading) {
        if (!state.loading && !initialized) {
            expanded = if (state.snapshot == null) "account" else ""
            initialized = true
        }
    }
    var periodsOpen by remember { mutableStateOf(false) }
    var appearanceSelection by rememberSaveable(stateSaver = appearanceSelectionSaver) { mutableStateOf<AppearanceSelection?>(null) }
    var themeEditorOpen by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) model.importBackground(context.applicationContext, uri)
    }
    val courseList = remember(state.snapshot?.courses) { state.snapshot?.courses.orEmpty().distinctBy { it.courseKey }.sortedBy { it.name } }
    val weekendCourses = remember(state.snapshot?.courses) { state.snapshot?.courses.orEmpty().filter { it.day >= 6 }.distinctBy { it.courseKey }.sortedBy { it.name } }
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize().background(colors.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("close-settings")) { Text("‹ 课表") }
            Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (working || state.refreshing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
        }
        LazyColumn(Modifier.fillMaxSize().imePadding().testTag("settings-list"), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item("account-header") {
                SettingsHeader("account", "教务账号与数据", state.snapshot?.let { "${it.studentId} · ${it.term}" } ?: "登录后获取课表",
                    expanded == "account") { expanded = if (expanded == "account") "" else "account" }
            }
            if (expanded == "account") item("account-body") {
                SettingsBody {
                    OutlinedTextField(studentId, { studentId = it }, label = { Text("学号") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("student-id"), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { TextButton(onClick = { passwordVisible = !passwordVisible }) { Text(if (passwordVisible) "隐藏" else "显示") } },
                        modifier = Modifier.fillMaxWidth().testTag("password"), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    OutlinedTextField(inputTerm, { inputTerm = it }, label = { Text("学年学期") }, placeholder = { Text("例如 202601") },
                        supportingText = { Text("年份 + 01（第一学期）或 02（第二学期）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("term"), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Button(onClick = { model.login(studentId, password, inputTerm) }, enabled = !working && !state.loading,
                        modifier = Modifier.fillMaxWidth().testTag("login")) { Text(if (working) "正在处理…" else "登录并获取课表") }
                    HorizontalDivider()
                    Text("最近成功刷新", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Text(state.snapshot?.let { Instant.ofEpochMilli(it.updatedAt).atZone(SCHOOL_ZONE)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) } ?: "尚未获取数据", style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = model::refresh, enabled = !working && account != null,
                            modifier = Modifier.weight(1f).testTag("manual-refresh")) { Text("手动刷新") }
                        TextButton(onClick = model::clearAccount, enabled = !working && account != null, modifier = Modifier.weight(1f)) { Text("清除登录信息") }
                    }
                    Text("打开应用时自动尝试更新，失败时继续使用本地课表。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            item("schedule-header") {
                SettingsHeader("schedule", "学期与作息", "第一周 $start · ${visiblePeriodCount(state)} 节${if (state.settings.isEstimated(state.snapshot, term)) " · 日期待校准" else ""}",
                    expanded == "schedule") { expanded = if (expanded == "schedule") "" else "schedule" }
            }
            if (expanded == "schedule") item("schedule-body") {
                SettingsBody {
                    Text(termLabel(term), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(startInput, { startInput = it }, label = { Text("第一周周一${if (state.settings.isEstimated(state.snapshot, term)) "（估算）" else ""}") },
                        supportingText = { Text("YYYY-MM-DD，按学校校历校准") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("term-start"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { model.setTermStart(startInput) }, enabled = !working) { Text("保存日期") }
                        TextButton(onClick = model::resetTermStart, enabled = !working) { Text("恢复估算") }
                    }
                    OutlinedButton(onClick = { periodsOpen = true }, modifier = Modifier.fillMaxWidth().testTag("edit-periods")) {
                        Text("编辑节次时间 · ${visiblePeriodCount(state)}节")
                    }
                    HorizontalDivider()
                    OutlinedTextField(highlightInput, { highlightInput = it }, label = { Text("开课后继续高亮（分钟）") },
                        supportingText = { Text("0～60 分钟；到达截止时间后高亮下一节课") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().testTag("highlight-minutes"))
                    OutlinedButton(onClick = { model.setHighlightMinutes(highlightInput) }, enabled = !working,
                        modifier = Modifier.testTag("save-highlight-minutes")) { Text("保存高亮时长") }
                }
            }
            item("appearance-header") {
                SettingsHeader("appearance", "课程外观", "${courseList.size} 门课程 · 也可直接点击课表课程修改",
                    expanded == "appearance") { expanded = if (expanded == "appearance") "" else "appearance" }
            }
            if (expanded == "appearance") {
                if (courseList.isEmpty()) item("appearance-empty") {
                    SettingsBody { Text("加载课表后即可配置课程颜色与 emoji", style = MaterialTheme.typography.bodySmall) }
                }
                items(courseList, key = { "appearance-${it.courseKey}" }) { course ->
                    val snapshot = state.snapshot!!
                    val appearance = state.settings.appearance(snapshot, course)
                    Row(Modifier.fillMaxWidth().background(colors.surfaceContainerLow, RoundedCornerShape(14.dp))
                        .clickable { appearanceSelection = AppearanceSelection(snapshot.scopeKey, course, appearance) }
                        .testTag("appearance-${course.courseKey}").padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val base = Color(appearance.color.toInt())
                        Box(Modifier.size(42.dp).background(base.copy(alpha = COURSE_CARD_ALPHA), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center) { Text(appearance.emoji.ifBlank { "Aa" }, color = courseInk(base)) }
                        Column(Modifier.weight(1f)) {
                            Text(course.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text(course.teacher.ifBlank { "教师待定" }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        Text("›", color = colors.onSurfaceVariant)
                    }
                }
            }
            item("weekend-header") {
                val hiddenCount = weekendCourses.count { "${state.snapshot?.scopeKey}:${it.courseKey}" in state.settings.hiddenWeekendCourses }
                SettingsHeader("weekend", "周末课程", "${weekendCourses.size} 门课程 · 已隐藏 $hiddenCount 门",
                    expanded == "weekend") { expanded = if (expanded == "weekend") "" else "weekend" }
            }
            if (expanded == "weekend") {
                item("weekend-info") {
                    SettingsBody { Text(if (weekendCourses.isEmpty()) "当前课表没有周末课程" else
                        "当周有课时自动展开周末；隐藏只影响该课程的周末排课。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                }
                items(weekendCourses, key = { "weekend-${it.courseKey}" }) { course ->
                    val hidden = "${state.snapshot!!.scopeKey}:${course.courseKey}" in state.settings.hiddenWeekendCourses
                    Row(Modifier.fillMaxWidth().background(colors.surfaceContainerLow, RoundedCornerShape(14.dp)).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(course.name, fontWeight = FontWeight.Medium)
                            Text("隐藏周末排课", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        Switch(hidden, onCheckedChange = { model.setWeekendCourseHidden(course, it) }, modifier = Modifier.testTag("hide-weekend-${course.courseKey}"))
                    }
                }
            }
            item("theme-header") {
                SettingsHeader("theme", "主题与取色", if (state.settings.useDynamicColor && android.os.Build.VERSION.SDK_INT >= 31) "系统莫奈取色" else "自定义主题色",
                    expanded == "theme") { expanded = if (expanded == "theme") "" else "theme" }
            }
            if (expanded == "theme") item("theme-body") {
                SettingsBody {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("系统莫奈取色", fontWeight = FontWeight.Medium)
                            Text(if (android.os.Build.VERSION.SDK_INT >= 31) "跟随系统壁纸配色，适配深浅色模式" else "此系统不支持莫奈，使用自定义主题色",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        Switch(state.settings.useDynamicColor, onCheckedChange = model::setDynamicColor, modifier = Modifier.testTag("dynamic-color-switch"))
                    }
                    OutlinedButton(onClick = { themeEditorOpen = true }, modifier = Modifier.fillMaxWidth().testTag("custom-theme")) {
                        Box(Modifier.size(18.dp).background(Color(state.settings.customPrimaryColor.toInt()), CircleShape))
                        Spacer(Modifier.width(8.dp)); Text("自定义主题色")
                    }
                }
            }
            item("background-header") {
                SettingsHeader("background", "课表背景", if (state.settings.backgroundPath == null) "默认柔和背景" else "自选图片 · 透明度 ${(state.settings.backgroundTransparency * 100).toInt()}%",
                    expanded == "background") { expanded = if (expanded == "background") "" else "background" }
            }
            if (expanded == "background") item("background-body") {
                SettingsBody {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !working) { Text("选择图片") }
                        TextButton(onClick = model::removeBackground, enabled = !working && state.settings.backgroundPath != null) { Text("恢复默认") }
                    }
                    Text("背景透明度 ${(transparency * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                    Slider(transparency, { transparency = it }, onValueChangeFinished = { model.setBackgroundTransparency(transparency) }, modifier = Modifier.testTag("background-transparency"))
                    Text("0% 完全显示图片，100% 隐藏图片", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    HorizontalDivider()
                    Text("主界面不透明度 ${(mainUiOpacity * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                    Slider(mainUiOpacity, { mainUiOpacity = it },
                        onValueChangeFinished = { model.setMainUiOpacity(mainUiOpacity) },
                        modifier = Modifier.testTag("main-ui-opacity"))
                    Text("调节周次栏、星期日期栏和节次时间栏的底色。0% 完全透明，100% 完全不透明",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
    if (themeEditorOpen) ThemeColorEditor(state.settings.customPrimaryColor,
        onSave = { model.setThemeColor(it); themeEditorOpen = false }, onDismiss = { themeEditorOpen = false })
    if (periodsOpen) PeriodEditor(state.settings.periods, visiblePeriodCount(state),
        onSave = { model.savePeriods(it); periodsOpen = false }, onDismiss = { periodsOpen = false })
    appearanceSelection?.let { selection ->
        AppearanceEditor(selection, !working,
            onSave = { appearance, complete -> model.saveAppearance(selection.scopeKey, selection.course, appearance, complete) },
            onDismiss = { if (appearanceSelection === selection) appearanceSelection = null })
    }
}
@Composable
private fun ThemeColorEditor(current: Long, onSave: (Long) -> Unit, onDismiss: () -> Unit) {
    var hex by remember { mutableStateOf("%06X".format(current and 0xFFFFFF)) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("自定义主题色") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("保存后使用自定义配色，可随时切回系统莫奈。", style = MaterialTheme.typography.bodySmall)
            listOf(0xFF4E709BL, 0xFF507A69L, 0xFF966681L, 0xFF886846L, 0xFF67579EL, 0xFF427E89L).chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { color ->
                        Box(Modifier.size(48.dp).background(Color(color.toInt()), CircleShape)
                            .border(2.dp, if (hex.equals("%06X".format(color and 0xFFFFFF), true)) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                            .clickable { hex = "%06X".format(color and 0xFFFFFF) })
                    }
                }
            }
            OutlinedTextField(hex, { hex = it; error = false }, label = { Text("主题色 HEX") }, prefix = { Text("#") }, singleLine = true,
                modifier = Modifier.testTag("theme-color-hex"))
            if (error) Text("请输入6位十六进制颜色", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        val value = hex.removePrefix("#")
        error = !Regex("[0-9a-fA-F]{6}").matches(value)
        if (!error) onSave(0xFF000000L or value.toLong(16))
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun SettingsHeader(id: String, title: String, summary: String, expanded: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(colors.surfaceContainerLow, RoundedCornerShape(18.dp))
        .clickable(role = Role.Button, onClickLabel = if (expanded) "收起$title" else "展开$title", onClick = onToggle)
        .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
        .testTag("section-$id").heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Text(if (expanded) "⌃" else "⌄", color = colors.primary)
    }
}

@Composable
private fun SettingsBody(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        content()
    }
}

@Composable
private fun PeriodEditor(current: List<PeriodTime>, observedCount: Int, onSave: (List<PeriodTime>) -> Unit, onDismiss: () -> Unit) {
    var periods by remember { mutableStateOf(List(maxOf(current.size, observedCount)) { current.getOrNull(it) ?: PeriodTime() }) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("节次时间") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("时间格式 HH:mm，未知时间可留空。", style = MaterialTheme.typography.bodySmall)
            periods.forEachIndexed { index, period ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${index + 1}", Modifier.width(22.dp))
                    OutlinedTextField(period.start, { value -> periods = periods.toMutableList().apply { set(index, period.copy(start = value)) } },
                        label = { Text("开始") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(period.end, { value -> periods = periods.toMutableList().apply { set(index, period.copy(end = value)) } },
                        label = { Text("结束") }, singleLine = true, modifier = Modifier.weight(1f))
                }
            }
            TextButton(onClick = { if (periods.size < 99) periods = periods + PeriodTime() }) { Text("＋ 添加一节") }
            if (periods.size > maxOf(10, observedCount)) TextButton(onClick = { periods = periods.dropLast(1) }) { Text("删除末节") }
            if (error) Text("请检查时间格式及开始、结束顺序", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { error = periods.any { !validPeriodTime(it) }; if (!error) onSave(periods) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
