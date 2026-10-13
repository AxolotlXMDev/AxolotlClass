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
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

class TimetableViewModel(private val repository: TimetableRepository, private val clock: Clock = schoolClock()) : ViewModel() {
    val state = repository.state
    val selectedWeek = MutableStateFlow<Int?>(null)
    // Only explicit navigation changes this token; gesture acknowledgements must not start a scroll.
    val weekNavigationId = MutableStateFlow(0L)
    val now = MutableStateFlow(clock.instant())
    val today = MutableStateFlow(now.value.atZone(SCHOOL_ZONE).toLocalDate())
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()
    val working = MutableStateFlow(false)

    data class SaveFailure(val message: String, val retry: () -> Unit)
    private val saveFailures = Channel<SaveFailure>(Channel.BUFFERED)
    val autoSaveFailures = saveFailures.receiveAsFlow()
    private val drafts = mutableMapOf<String, AutoSaveDraft<*>>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> draft(key: String, initial: T, validate: (T) -> String? = { null },
                          write: suspend (T) -> Unit): AutoSaveDraft<T> =
        (drafts.getOrPut(key) {
            AutoSaveDraft(initial, viewModelScope, validate, write) { message, retry ->
                saveFailures.trySend(SaveFailure(message, retry))
            }
        } as AutoSaveDraft<T>).also { it.refreshInitial(initial) }

    fun appearanceDraft(scopeKey: String, course: CourseRecord, initial: CourseAppearance) =
        draft("appearance:$scopeKey:${course.courseKey}", initial) { appearance ->
            val key = "$scopeKey:${course.courseKey}"
            repository.updateSettings { it.copy(appearances = it.appearances + (key to appearance.copy(emoji = appearance.emoji.trim()))) }
        }

    fun termStartDraft(scopeKey: String, initial: String) = draft("term-start:$scopeKey", initial, { value ->
        val date = runCatching { LocalDate.parse(value.trim()) }.getOrNull()
        when {
            date == null -> "请输入 YYYY-MM-DD 格式的日期"
            date.dayOfWeek != DayOfWeek.MONDAY -> "请选择第一周的周一日期"
            else -> null
        }
    }) { value ->
        repository.updateSettings { it.copy(termStarts = it.termStarts + (scopeKey to LocalDate.parse(value.trim()).toString())) }
        requestWeek(null)
    }

    fun highlightDraft() = draft("highlight", state.value.settings.highlightMinutes.toString(), { value ->
        if (value.trim().toIntOrNull() in 0..60) null else "请输入 0～60 的整数分钟"
    }) { value -> repository.updateSettings { it.copy(highlightMinutes = value.trim().toInt()) } }

    fun themeDraft() = draft("theme", "%06X".format(state.value.settings.customPrimaryColor and 0xFFFFFF), { value ->
        if (Regex("[0-9a-fA-F]{6}").matches(value.removePrefix("#"))) null else "请输入6位十六进制颜色"
    }) { value ->
        val color = 0xFF000000L or value.removePrefix("#").toLong(16)
        repository.updateSettings { it.copy(useDynamicColor = false, customPrimaryColor = color) }
    }

    fun periodsDraft(observedCount: Int): AutoSaveDraft<List<PeriodTime>> {
        val periods = state.value.settings.periods
        val result = draft("periods", List(maxOf(periods.size, observedCount)) { periods.getOrNull(it) ?: PeriodTime() }, { value ->
            if (value.size in 10..99 && value.all(::validPeriodTime)) null else "时间应为 HH:mm，结束时间应晚于开始时间；未知时间可同时留空"
        }) { value -> repository.updateSettings { it.copy(periods = value) } }
        if (result.state.value.value.size < observedCount) {
            result.change(result.state.value.value + List(observedCount - result.state.value.value.size) { PeriodTime() }, immediate = true)
        }
        return result
    }

    fun flushAutoSaves() { drafts.values.forEach { it.flush() } }

    private var clockJob: Job? = null

    private fun updateTime() {
        now.value = clock.instant()
        today.value = now.value.atZone(SCHOOL_ZONE).toLocalDate()
    }

    fun onForeground() {
        updateTime()
        if (clockJob?.isActive == true) return
        clockJob = viewModelScope.launch {
            while (isActive) {
                val current = clock.instant()
                val nextMinute = current.truncatedTo(ChronoUnit.MINUTES).plusSeconds(60)
                delay(ChronoUnit.MILLIS.between(current, nextMinute).coerceAtLeast(1))
                updateTime()
            }
        }
    }

    fun onBackground() { flushAutoSaves(); clockJob?.cancel(); clockJob = null }
    private fun requestWeek(week: Int?) {
        selectedWeek.value = week
        weekNavigationId.value += 1
    }

    fun selectWeek(week: Int) { requestWeek(week) }
    fun currentWeek() { onForeground(); requestWeek(null) }

    fun settleWeek(week: Int, navigationId: Long) {
        if (navigationId == weekNavigationId.value) selectedWeek.value = week
    }

    fun login(studentId: String, password: String, term: String) = operation {
        when (val result = repository.refresh(AccountSettings(studentId.trim(), password, term.trim()))) {
            RefreshResult.Success -> { requestWeek(null); "课表已更新" }
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

    fun resetTermStart() {
        val current = state.value
        val term = current.snapshot?.term ?: current.account?.term ?: defaultTerm(today.value)
        val key = current.snapshot?.scopeKey ?: ":$term"
        val estimated = estimateTermStart(term).toString()
        termStartDraft(key, estimated).restore(estimated) {
            repository.updateSettings { it.copy(termStarts = it.termStarts - key) }
            requestWeek(null)
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
