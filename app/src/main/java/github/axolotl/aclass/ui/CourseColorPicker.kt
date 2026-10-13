package github.axolotl.aclass.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import github.axolotl.aclass.data.COURSE_COLOR_PRESETS
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Stable
internal class ColorPickerDraft(initial: Long) {
    private val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toInt(), it) }
    val hue = mutableFloatStateOf(hsv[0])
    val saturation = mutableFloatStateOf(hsv[1])
    val brightness = mutableFloatStateOf(hsv[2])
    val hex = mutableStateOf("%06X".format(initial and 0xFFFFFF))
    val invalid = mutableStateOf(false)
}

@Composable
internal fun CourseColorPicker(draft: ColorPickerDraft, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var hue by draft.hue
    var saturation by draft.saturation
    var brightness by draft.brightness
    var hex by draft.hex
    var invalid by draft.invalid
    fun hsvChanged(h: Float = hue, s: Float = saturation, v: Float = brightness) {
        hue = h; saturation = s; brightness = v
        hex = "%06X".format(Color.hsv(h, s, v).toArgb() and 0xFFFFFF)
        invalid = false
    }
    fun selectColor(color: Long) {
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(color.toInt(), it) }
        hsvChanged(hsv[0], hsv[1], hsv[2])
        hex = "%06X".format(color and 0xFFFFFF)
    }
    val updateWheel by rememberUpdatedState<(Offset, Float) -> Unit>({ position, radius ->
        if (radius > 0f) {
            val h = ((Math.toDegrees(atan2(position.y, position.x).toDouble()) + 360) % 360).toFloat()
            hsvChanged(h, (position.getDistance() / radius).coerceIn(0f, 1f))
        }
    })
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.imePadding(),
        title = { Text("选择课程颜色") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())
            .testTag("course-color-picker"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp).testTag("course-color-wheel")
                .semantics { contentDescription = "圆形色盘，点击或拖动选择色相和饱和度"; stateDescription = "颜色 #$hex" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val radius = minOf(size.width, size.height) / 2f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        updateWheel(down.position - center, radius)
                        down.consume()
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.pressed) updateWheel(change.position - center, radius)
                            change.consume()
                        } while (event.changes.any { it.id == down.id && it.pressed })
                    }
                }) {
                val radius = minOf(size.width, size.height) / 2f
                drawCircle(Brush.sweepGradient((0..6).map { Color.hsv((it * 60f).coerceAtMost(360f), 1f, 1f) }, center))
                drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, radius))
                drawCircle(Color.Black.copy(alpha = 1f - brightness))
                val radians = Math.toRadians(hue.toDouble())
                val marker = center + Offset(cos(radians).toFloat(), sin(radians).toFloat()) * (radius * saturation)
                drawCircle(Color.Black, 7.dp.toPx(), marker, style = Stroke(3.dp.toPx()))
                drawCircle(Color.White, 7.dp.toPx(), marker, style = Stroke(1.5.dp.toPx()))
            }
            Text("明度 ${(brightness * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            Slider(brightness, { hsvChanged(v = it) }, modifier = Modifier.testTag("course-color-brightness")
                .semantics { contentDescription = "颜色明度" })
            Text("预设颜色", style = MaterialTheme.typography.labelMedium)
            COURSE_COLOR_PRESETS.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { color ->
                        val isSelected = appearanceColor(hex) == color
                        Box(Modifier.size(48.dp).clickable(role = Role.RadioButton) { selectColor(color) }
                            .semantics { contentDescription = "预设颜色 #%06X".format(color and 0xFFFFFF); selected = isSelected }
                            .testTag("appearance-color-${color and 0xFFFFFF}").padding(4.dp)
                            .background(Color(color.toInt()), CircleShape)
                            .border(if (isSelected) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                            contentAlignment = Alignment.Center) {
                            if (isSelected) Text("✓", color = courseInk(Color(color.toInt())))
                        }
                    }
                }
            }
            OutlinedTextField(hex, { value ->
                hex = value; invalid = false
                appearanceColor(value)?.let { color ->
                    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(color.toInt(), it) }
                    hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]
                }
            }, label = { Text("颜色 HEX") }, prefix = { Text("#") }, singleLine = true, isError = invalid,
                supportingText = if (invalid) ({ Text("请输入6位十六进制颜色") }) else null,
                modifier = Modifier.fillMaxWidth().testTag("appearance-hex"))
        }
    }, confirmButton = {
        TextButton(onClick = {
            val color = appearanceColor(hex)
            invalid = color == null
            if (color != null) onConfirm(color)
        }, modifier = Modifier.testTag("confirm-course-color")) { Text("确定") }
    }, dismissButton = {
        TextButton(onClick = onDismiss, modifier = Modifier.testTag("cancel-course-color")) { Text("取消") }
    })
}
