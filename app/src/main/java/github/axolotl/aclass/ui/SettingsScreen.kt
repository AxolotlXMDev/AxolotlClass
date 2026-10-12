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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
    var studentId by remember(account?.studentId) { mutableStateOf(account?.studentId.orEmpty()) }
    var password by remember(account?.password) { mutableStateOf(account?.password.orEmpty()) }
    var inputTerm by remember(account?.term, state.snapshot?.term) { mutableStateOf(account?.term ?: term) }
    var passwordVisible by remember { mutableStateOf(false) }
    val start = state.settings.termStart(state.snapshot, term)
    var startInput by remember(start) { mutableStateOf(start.toString()) }
    var transparency by remember(state.settings.backgroundTransparency) { mutableFloatStateOf(state.settings.backgroundTransparency) }
    var periodsOpen by remember { mutableStateOf(false) }
    var appearanceCourse by remember { mutableStateOf<CourseRecord?>(null) }
    var themeEditorOpen by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) model.importBackground(context.applicationContext, uri)
    }
    val courseList = remember(state.snapshot?.courses) { state.snapshot?.courses.orEmpty().distinctBy { it.courseKey }.sortedBy { it.name } }
    val weekendCourses = remember(state.snapshot?.courses) { state.snapshot?.courses.orEmpty().filter { it.day >= 6 }.distinctBy { it.courseKey }.sortedBy { it.name } }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("close-settings")) { Text("‹ 课表") }
            Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (working || state.refreshing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
        }
        LazyColumn(Modifier.fillMaxSize().testTag("settings-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SettingsSection("主题与取色") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("系统莫奈取色", fontWeight = FontWeight.Medium)
                            Text(if (android.os.Build.VERSION.SDK_INT >= 31) "跟随系统壁纸配色，适配深浅色模式" else "此系统不支持莫奈，使用自定义主题色",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(state.settings.useDynamicColor, onCheckedChange = model::setDynamicColor,
                            modifier = Modifier.testTag("dynamic-color-switch"))
                    }
                    OutlinedButton(onClick = { themeEditorOpen = true }, modifier = Modifier.fillMaxWidth().testTag("custom-theme")) {
                        Box(Modifier.size(18.dp).background(Color(state.settings.customPrimaryColor.toInt()), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text("自定义主题色")
                    }
                    if (!state.settings.useDynamicColor) Text("当前使用自定义配色", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                SettingsSection("教务账号与数据") {
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
                    Text("最近成功刷新", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.snapshot?.let { Instant.ofEpochMilli(it.updatedAt).atZone(SCHOOL_ZONE)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) } ?: "尚未获取数据", style = MaterialTheme.typography.bodyMedium)
                    state.snapshot?.let { Text("当前课表：${it.studentId} · ${it.term}", style = MaterialTheme.typography.bodySmall) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = model::refresh, enabled = !working && account != null,
                            modifier = Modifier.weight(1f).testTag("manual-refresh")) { Text("手动刷新") }
                        TextButton(onClick = model::clearAccount, enabled = !working && account != null, modifier = Modifier.weight(1f)) { Text("清除登录信息") }
                    }
                    Text("打开应用时自动尝试更新，失败时继续使用本地课表。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SettingsSection("学期与作息") {
                    Text(termLabel(term), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(startInput, { startInput = it }, label = { Text("第一周周一${if (state.settings.isEstimated(state.snapshot, term)) "（估算）" else ""}") },
                        supportingText = { Text("YYYY-MM-DD，按学校校历校准") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { model.setTermStart(startInput) }, enabled = !working) { Text("保存日期") }
                        TextButton(onClick = model::resetTermStart, enabled = !working) { Text("恢复估算") }
                    }
                    OutlinedButton(onClick = { periodsOpen = true }, modifier = Modifier.fillMaxWidth().testTag("edit-periods")) {
                        Text("编辑节次时间 · ${visiblePeriodCount(state)}节")
                    }
                }
            }
            item {
                SettingsSection("周末课程") {
                    Text("默认只显示周一至周五；当周有课时自动展开周末，也可在课表左上角手动展开。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (weekendCourses.isEmpty()) Text("当前课表没有周末课程", style = MaterialTheme.typography.bodySmall)
                    weekendCourses.forEach { course ->
                        val key = "${state.snapshot!!.scopeKey}:${course.courseKey}"
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(course.name, fontWeight = FontWeight.Medium)
                                Text("隐藏此课程的周末排课", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(key in state.settings.hiddenWeekendCourses,
                                onCheckedChange = { model.setWeekendCourseHidden(course, it) }, modifier = Modifier.testTag("hide-weekend-${course.courseKey}"))
                        }
                    }
                }
            }
            item {
                SettingsSection("课表背景") {
                    Text(if (state.settings.backgroundPath == null) "使用默认柔和背景" else "已设置自选图片", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !working) { Text("选择图片") }
                        TextButton(onClick = model::removeBackground, enabled = !working && state.settings.backgroundPath != null) { Text("恢复默认") }
                    }
                    Text("背景透明度 ${(transparency * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                    Slider(transparency, { transparency = it }, onValueChangeFinished = { model.setBackgroundTransparency(transparency) },
                        modifier = Modifier.testTag("background-transparency"))
                    Text("0% 完全显示图片，100% 隐藏图片", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("课程颜色与 emoji", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (courseList.isEmpty()) "加载课表后即可配置课程外观" else "同一课程在各周使用相同外观，刷新后保留。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(courseList, key = { it.courseKey }) { course ->
                val appearance = state.settings.appearance(state.snapshot!!, course)
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp))
                    .clickable { appearanceCourse = course }.testTag("appearance-${course.courseKey}").padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(42.dp).background(Color(appearance.color.toInt()).copy(alpha = 0.75f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center) { Text(appearance.emoji.ifBlank { "Aa" }) }
                    Column(Modifier.weight(1f)) {
                        Text(course.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(course.teacher.ifBlank { "教师待定" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›")
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
    if (themeEditorOpen) ThemeColorEditor(state.settings.customPrimaryColor,
        onSave = { model.setThemeColor(it); themeEditorOpen = false }, onDismiss = { themeEditorOpen = false })
    if (periodsOpen) PeriodEditor(state.settings.periods, visiblePeriodCount(state),
        onSave = { model.savePeriods(it); periodsOpen = false }, onDismiss = { periodsOpen = false })
    appearanceCourse?.let { course ->
        val snapshot = state.snapshot
        if (snapshot != null) AppearanceEditor(course, state.settings.appearance(snapshot, course),
            onSave = { model.saveAppearance(course, it); appearanceCourse = null }, onDismiss = { appearanceCourse = null })
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
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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

@Composable
private fun AppearanceEditor(course: CourseRecord, current: CourseAppearance, onSave: (CourseAppearance) -> Unit, onDismiss: () -> Unit) {
    var hex by remember { mutableStateOf("%06X".format(current.color and 0xFFFFFF)) }
    var emoji by remember { mutableStateOf(current.emoji) }
    var error by remember { mutableStateOf(false) }
    val previewColor = runCatching { Color((0xFF000000L or hex.removePrefix("#").toLong(16)).toInt()) }.getOrDefault(Color(current.color.toInt()))
    AlertDialog(onDismissRequest = onDismiss, title = { Text("课程外观") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth().background(previewColor.copy(alpha = 0.75f), RoundedCornerShape(12.dp)).padding(14.dp)) {
                Text("$emoji ${course.name}".trim(), fontWeight = FontWeight.Bold, color = Color(0xFF172B42))
                Text(course.location, style = MaterialTheme.typography.bodySmall, color = Color(0xFF172B42))
            }
            COURSE_PALETTE.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { color ->
                        Box(Modifier.size(44.dp).background(Color(color.toInt()), CircleShape)
                            .border(2.dp, if (hex.equals("%06X".format(color and 0xFFFFFF), true)) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                            .clickable { hex = "%06X".format(color and 0xFFFFFF) })
                    }
                }
            }
            OutlinedTextField(hex, { hex = it; error = false }, label = { Text("颜色 HEX") }, prefix = { Text("#") }, singleLine = true)
            OutlinedTextField(emoji, { emoji = it }, label = { Text("emoji（留空即清除）") }, singleLine = true)
            TextButton(onClick = { emoji = "" }) { Text("清除 emoji") }
            if (error) Text("请输入6位十六进制颜色", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        val value = hex.removePrefix("#")
        error = !Regex("[0-9a-fA-F]{6}").matches(value)
        if (!error) onSave(CourseAppearance(0xFF000000L or value.toLong(16), emoji.trim()))
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
