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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import github.axolotl.aclass.data.CourseAppearance
import github.axolotl.aclass.data.CourseRecord
import github.axolotl.aclass.data.TimetableParser
import kotlinx.serialization.json.jsonObject

internal const val COURSE_CARD_ALPHA = 0.92f
internal fun courseInk(color: Color): Color =
    if (color.luminance() < 0.25f) Color.White else Color(0xFF172B42)

internal fun appearanceColor(hex: String): Long? = hex.removePrefix("#").takeIf {
    Regex("[0-9a-fA-F]{6}").matches(it)
}?.let { 0xFF000000L or it.toLong(16) }

@Stable
internal class AppearanceDraft(appearance: CourseAppearance) {
    val color = mutableLongStateOf(appearance.color)
    val emoji = mutableStateOf(appearance.emoji)
    val picker = mutableStateOf<ColorPickerDraft?>(null)
}

internal data class AppearanceSelection(val scopeKey: String, val course: CourseRecord, val appearance: CourseAppearance,
                                        val draft: AppearanceDraft = AppearanceDraft(appearance))
// Keep editor and picker drafts in the host registry, outside the sheet/dialog's own composition.
internal val appearanceSelectionSaver = Saver<AppearanceSelection?, List<String>>(
    save = { selection -> selection?.let {
        buildList {
            addAll(listOf(it.scopeKey, it.course.raw.toString(), it.appearance.color.toString(), it.appearance.emoji,
                it.draft.color.longValue.toString(), it.draft.emoji.value))
            val picker = it.draft.picker.value
            if (picker == null) add("closed") else addAll(listOf("open", picker.hex.value,
                picker.hue.floatValue.toString(), picker.saturation.floatValue.toString(), picker.brightness.floatValue.toString(),
                picker.invalid.value.toString()))
        }
    } },
    restore = { values -> runCatching {
        AppearanceSelection(values[0], TimetableParser.fromRaw(TimetableParser.json.parseToJsonElement(values[1]).jsonObject),
            CourseAppearance(values[2].toLong(), values[3])).also { selection ->
            values.getOrNull(4)?.toLongOrNull()?.let { selection.draft.color.longValue = it }
            values.getOrNull(5)?.let { selection.draft.emoji.value = it }
            if (values.getOrNull(6) == "open") selection.draft.picker.value = ColorPickerDraft(selection.draft.color.longValue).also {
                it.hex.value = values[7]; it.hue.floatValue = values[8].toFloat(); it.saturation.floatValue = values[9].toFloat()
                it.brightness.floatValue = values[10].toFloat(); it.invalid.value = values[11].toBoolean()
            }
        }
    }.getOrNull() },
)

/** The host supplies scrolling and decides whether a successful save closes the editor. */
@Composable
internal fun CourseAppearanceForm(
    course: CourseRecord,
    draft: AppearanceDraft,
    enabled: Boolean,
    onSave: (CourseAppearance, (Result<Unit>) -> Unit) -> Unit,
    onSaved: () -> Unit = {},
) {
    var color by draft.color
    var emoji by draft.emoji
    var picker by draft.picker
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val canEdit = enabled && !saving
    val preview = Color(color.toInt())
    val ink = courseInk(preview)
    val savedCallback by rememberUpdatedState(onSaved)
    fun changed() { error = null; saved = false }

    Column(Modifier.fillMaxWidth().testTag("appearance-form"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth().background(preview.copy(alpha = COURSE_CARD_ALPHA), RoundedCornerShape(8.dp))
            .padding(14.dp).testTag("appearance-preview"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(listOf(emoji, course.name).filter(String::isNotBlank).joinToString(" "),
                fontWeight = FontWeight.SemiBold, color = ink)
            Text(course.location.ifBlank { "地点待定" }, style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium, color = ink)
        }
        Row(Modifier.fillMaxWidth().testTag("appearance-controls"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(64.dp).clickable(enabled = canEdit, role = Role.Button,
                onClickLabel = "选择课程颜色") { picker = ColorPickerDraft(color) }
                .semantics { contentDescription = "当前颜色 #%06X，选择课程颜色".format(color and 0xFFFFFF) }
                .testTag("open-course-color").padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(40.dp).background(preview, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                Text("颜色", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedTextField(emoji, { emoji = it; changed() }, enabled = canEdit,
                label = { Text("emoji") }, supportingText = { Text("留空即清除") }, singleLine = true,
                modifier = Modifier.weight(1f).testTag("appearance-emoji"))
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { emoji = ""; changed() }, enabled = canEdit) { Text("清除 emoji") }
            Button(enabled = canEdit, modifier = Modifier.testTag("save-appearance"), onClick = {
                saving = true; error = null; saved = false
                onSave(CourseAppearance(color, emoji.trim())) { result ->
                    saving = false
                    result.fold(onSuccess = { saved = true; savedCallback() },
                        onFailure = { error = it.message ?: "保存失败，请重试" })
                }
            }) { Text(if (saving) "正在保存…" else "保存外观") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("appearance-save-error")) }
        if (saved) Text("课程外观已保存", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    }
    picker?.let { pickerDraft -> CourseColorPicker(pickerDraft, onDismiss = { picker = null }, onConfirm = {
        color = it; changed(); picker = null
    }) }
}

@Composable
internal fun AppearanceEditor(selection: AppearanceSelection, enabled: Boolean,
                              onSave: (CourseAppearance, (Result<Unit>) -> Unit) -> Unit, onDismiss: () -> Unit) {
    key(selection.scopeKey, selection.course.courseKey) {
        AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.imePadding(), title = { Text("课程外观") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CourseAppearanceForm(selection.course, selection.draft, enabled, onSave, onSaved = onDismiss)
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
    }
}
