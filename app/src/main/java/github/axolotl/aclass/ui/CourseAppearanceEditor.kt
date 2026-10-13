package github.axolotl.aclass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import github.axolotl.aclass.data.COURSE_PALETTE
import github.axolotl.aclass.data.CourseAppearance
import github.axolotl.aclass.data.CourseRecord

internal const val COURSE_CARD_ALPHA = 0.92f
internal fun courseInk(color: Color): Color =
    if (color.luminance() < 0.25f) Color.White else Color(0xFF172B42)

internal fun appearanceColor(hex: String): Long? = hex.removePrefix("#").takeIf {
    Regex("[0-9a-fA-F]{6}").matches(it)
}?.let { 0xFF000000L or it.toLong(16) }

internal data class AppearanceSelection(val scopeKey: String, val course: CourseRecord, val appearance: CourseAppearance)

/** The host supplies scrolling and decides whether a successful save closes the editor. */
@Composable
internal fun CourseAppearanceForm(
    course: CourseRecord,
    current: CourseAppearance,
    enabled: Boolean,
    onSave: (CourseAppearance, (Result<Unit>) -> Unit) -> Unit,
    onSaved: () -> Unit = {},
) {
    var hex by rememberSaveable { mutableStateOf("%06X".format(current.color and 0xFFFFFF)) }
    var emoji by rememberSaveable { mutableStateOf(current.emoji) }
    var invalidHex by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val canEdit = enabled && !saving
    val color = appearanceColor(hex)
    val preview = Color((color ?: current.color).toInt())
    val ink = courseInk(preview)
    val savedCallback by rememberUpdatedState(onSaved)
    fun changed() { invalidHex = false; error = null; saved = false }

    Column(Modifier.fillMaxWidth().testTag("appearance-form"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth().background(preview.copy(alpha = COURSE_CARD_ALPHA), RoundedCornerShape(8.dp))
            .padding(14.dp).testTag("appearance-preview"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(listOf(emoji, course.name).filter(String::isNotBlank).joinToString(" "),
                fontWeight = FontWeight.SemiBold, color = ink)
            Text(course.location.ifBlank { "地点待定" }, style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium, color = ink)
        }
        COURSE_PALETTE.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { swatch ->
                    val isSelected = color == swatch
                    Box(Modifier.size(48.dp)
                        .clickable(enabled = canEdit, role = Role.RadioButton, onClickLabel = "选择课程颜色") {
                            hex = "%06X".format(swatch and 0xFFFFFF); changed()
                        }
                        .semantics { contentDescription = "颜色 #%06X".format(swatch and 0xFFFFFF); selected = isSelected }
                        .testTag("appearance-color-${swatch and 0xFFFFFF}").padding(3.dp)
                        .background(Color(swatch.toInt()), CircleShape)
                        .border(2.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
                        contentAlignment = Alignment.Center) {
                        if (isSelected) Text("✓", color = courseInk(Color(swatch.toInt())), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        OutlinedTextField(hex, { hex = it; changed() }, enabled = canEdit,
            label = { Text("颜色 HEX") }, prefix = { Text("#") }, singleLine = true, isError = invalidHex,
            supportingText = if (invalidHex) ({ Text("请输入6位十六进制颜色") }) else null,
            modifier = Modifier.fillMaxWidth().testTag("appearance-hex"))
        OutlinedTextField(emoji, { emoji = it; changed() }, enabled = canEdit,
            label = { Text("emoji（留空即清除）") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("appearance-emoji"))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { emoji = ""; changed() }, enabled = canEdit) { Text("清除 emoji") }
            Button(enabled = canEdit, modifier = Modifier.testTag("save-appearance"), onClick = {
                val value = appearanceColor(hex)
                invalidHex = value == null
                if (value != null) {
                    saving = true; error = null; saved = false
                    onSave(CourseAppearance(value, emoji.trim())) { result ->
                        saving = false
                        result.fold(onSuccess = { saved = true; savedCallback() },
                            onFailure = { error = it.message ?: "保存失败，请重试" })
                    }
                }
            }) { Text(if (saving) "正在保存…" else "保存外观") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("appearance-save-error")) }
        if (saved) Text("课程外观已保存", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun AppearanceEditor(selection: AppearanceSelection, enabled: Boolean,
                              onSave: (CourseAppearance, (Result<Unit>) -> Unit) -> Unit, onDismiss: () -> Unit) {
    key(selection.scopeKey, selection.course.courseKey) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text("课程外观") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CourseAppearanceForm(selection.course, selection.appearance, enabled, onSave, onSaved = onDismiss)
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
    }
}
