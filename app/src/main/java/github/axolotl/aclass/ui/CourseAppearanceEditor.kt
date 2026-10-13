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
import github.axolotl.aclass.TimetableViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

@Composable
internal fun CourseAppearanceForm(
    selection: AppearanceSelection,
    model: TimetableViewModel,
) {
    val course = selection.course
    val draft = remember(model, selection.scopeKey, course.courseKey) {
        model.appearanceDraft(selection.scopeKey, course, selection.appearance)
    }
    AutoSaveBinding(draft)
    val status by draft.state.collectAsStateWithLifecycle()
    val color = status.value.color
    val emoji = status.value.emoji
    var picker by selection.draft.picker
    val restored = remember(selection) { CourseAppearance(selection.draft.color.longValue, selection.draft.emoji.value) }
    LaunchedEffect(selection) {
        if (restored != selection.appearance && draft.state.value.value == selection.appearance) draft.change(restored)
    }
    SideEffect {
        selection.draft.color.longValue = color
        selection.draft.emoji.value = emoji
    }
    val preview = Color(color.toInt())
    val ink = courseInk(preview)

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
            Column(Modifier.width(64.dp).clickable(role = Role.Button,
                onClickLabel = "选择课程颜色") { picker = ColorPickerDraft(color) }
                .semantics { contentDescription = "当前颜色 #%06X，选择课程颜色".format(color and 0xFFFFFF) }
                .testTag("open-course-color").padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(40.dp).background(preview, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                Text("颜色", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedTextField(emoji, { draft.change(draft.state.value.value.copy(emoji = it)) },
                label = { Text("emoji") }, supportingText = { Text("留空即清除") }, singleLine = true,
                modifier = Modifier.weight(1f).flushOnBlur(draft).testTag("appearance-emoji"))
        }
        AutoSaveError(status, draft, "appearance-save-error")
    }
    picker?.let { pickerDraft -> CourseColorPicker(pickerDraft, onDismiss = { picker = null }, onConfirm = {
        draft.change(draft.state.value.value.copy(color = it), immediate = true); picker = null
    }) }
}

@Composable
internal fun AppearanceEditor(selection: AppearanceSelection, model: TimetableViewModel, onDismiss: () -> Unit) {
    key(selection.scopeKey, selection.course.courseKey) {
        AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.imePadding(), title = { Text("课程外观") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CourseAppearanceForm(selection, model)
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
    }
}
