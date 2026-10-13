package github.axolotl.aclass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import github.axolotl.aclass.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

class TimetableViewModel(private val repository: TimetableRepository) : ViewModel() {
    val state = repository.state
    val selectedWeek = MutableStateFlow<Int?>(null)
    val today = MutableStateFlow(LocalDate.now(SCHOOL_ZONE))
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()
    val working = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            while (isActive) {
                today.value = LocalDate.now(SCHOOL_ZONE)
                val now = ZonedDateTime.now(SCHOOL_ZONE)
                val midnight = now.toLocalDate().plusDays(1).atStartOfDay(SCHOOL_ZONE)
                delay(ChronoUnit.MILLIS.between(now, midnight).coerceAtLeast(1000))
            }
        }
    }

    fun onForeground() { today.value = LocalDate.now(SCHOOL_ZONE) }
    fun selectWeek(week: Int) { selectedWeek.value = week }
    fun currentWeek() { selectedWeek.value = null; onForeground() }

    fun login(studentId: String, password: String, term: String) = operation {
        when (val result = repository.refresh(AccountSettings(studentId.trim(), password, term.trim()))) {
            RefreshResult.Success -> { selectedWeek.value = null; "课表已更新" }
            RefreshResult.NoAccount -> "请先登录"
            is RefreshResult.Failure -> result.message
        }
    }

    fun refresh() = operation {
        when (val result = repository.refresh()) {
            RefreshResult.Success -> "课表已更新"
            RefreshResult.NoAccount -> "请先填写账号并登录"
            is RefreshResult.Failure -> result.message
        }
    }

    fun clearAccount() = operation { repository.clearAccount(); "登录信息已清除，离线课表仍可查看" }

    fun setTermStart(value: String) = operation {
        val date = runCatching { LocalDate.parse(value.trim()) }.getOrNull() ?: error("请输入 YYYY-MM-DD 格式的日期")
        require(date.dayOfWeek == DayOfWeek.MONDAY) { "请选择第一周的周一日期" }
        val current = state.value
        val key = current.snapshot?.scopeKey ?: ":${current.account?.term ?: defaultTerm(today.value)}"
        repository.updateSettings { it.copy(termStarts = it.termStarts + (key to date.toString())) }
        selectedWeek.value = null
        "第一周日期已保存"
    }

    fun resetTermStart() = operation {
        val current = state.value
        val key = current.snapshot?.scopeKey ?: ":${current.account?.term ?: defaultTerm(today.value)}"
        repository.updateSettings { it.copy(termStarts = it.termStarts - key) }
        selectedWeek.value = null
        "已恢复估算日期"
    }

    fun savePeriods(periods: List<PeriodTime>) = operation {
        require(periods.size in 10..99 && periods.all(::validPeriodTime)) { "时间应为 HH:mm，结束时间应晚于开始时间；未知时间可同时留空" }
        repository.updateSettings { it.copy(periods = periods) }
        "节次时间已保存"
    }

    fun saveAppearance(scopeKey: String, course: CourseRecord, appearance: CourseAppearance,
                       onComplete: (Result<Unit>) -> Unit) {
        if (working.value) {
            onComplete(Result.failure(IllegalStateException("正在处理其他操作，请稍后重试")))
            return
        }
        working.value = true
        viewModelScope.launch {
            val result = try {
                val key = "$scopeKey:${course.courseKey}"
                repository.updateSettings { it.copy(appearances = it.appearances + (key to appearance)) }
                Result.success(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            } finally {
                working.value = false
            }
            onComplete(result)
        }
    }

    fun setBackgroundTransparency(value: Float) = operation(showBusy = false) {
        repository.updateSettings { it.copy(backgroundTransparency = value.coerceIn(0f, 1f)) }
        null
    }

    fun setMainUiOpacity(value: Float) = operation(showBusy = false) {
        require(value.isFinite()) { "不透明度应为有效数值" }
        repository.updateSettings { it.copy(mainUiOpacity = value.coerceIn(0f, 1f)) }
        null
    }

    fun setDynamicColor(enabled: Boolean) = operation(showBusy = false) {
        repository.updateSettings { it.copy(useDynamicColor = enabled) }
        null
    }

    fun setThemeColor(color: Long) = operation {
        repository.updateSettings { it.copy(useDynamicColor = false, customPrimaryColor = color) }
        "自定义主题色已保存"
    }

    fun setWeekendCourseHidden(course: CourseRecord, hidden: Boolean) = operation(showBusy = false) {
        val snapshot = state.value.snapshot ?: return@operation null
        val key = "${snapshot.scopeKey}:${course.courseKey}"
        repository.updateSettings {
            it.copy(hiddenWeekendCourses = if (hidden) it.hiddenWeekendCourses + key else it.hiddenWeekendCourses - key)
        }
        null
    }

    fun importBackground(context: Context, uri: Uri) = operation {
        val file = withContext(Dispatchers.IO) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val ratio = minOf(1f, 1920f / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * ratio).toInt()), maxOf(1, (info.size.height * ratio).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val directory = File(context.filesDir, "backgrounds").apply { mkdirs() }
            val result = File(directory, "${UUID.randomUUID()}.png")
            try {
                result.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "图片保存失败" } }
            } finally { bitmap.recycle() }
            result
        }
        val previous = state.value.settings.backgroundPath
        try { repository.updateSettings { it.copy(backgroundPath = file.absolutePath) } }
        catch (error: Exception) { withContext(Dispatchers.IO) { file.delete() }; throw error }
        withContext(Dispatchers.IO) { previous?.let { File(it).delete() } }
        "背景已设置"
    }

    fun removeBackground() = operation {
        val previous = state.value.settings.backgroundPath
        repository.updateSettings { it.copy(backgroundPath = null) }
        withContext(Dispatchers.IO) { previous?.let { File(it).delete() } }
        "已恢复默认背景"
    }

    private fun operation(showBusy: Boolean = true, block: suspend () -> String?) {
        if (showBusy && working.value) return
        viewModelScope.launch {
            if (showBusy) working.value = true
            try { block()?.let { messages.send(it) } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { messages.send(error.message ?: "保存失败，请重试") }
            finally { if (showBusy) working.value = false }
        }
    }
}
