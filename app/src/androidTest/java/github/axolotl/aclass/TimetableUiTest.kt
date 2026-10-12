package github.axolotl.aclass

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import github.axolotl.aclass.data.*
import github.axolotl.aclass.ui.TimetableApp
import github.axolotl.aclass.ui.theme.AxolotlClassTheme
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import androidx.lifecycle.ViewModelStore
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

class TimetableUiTest {
    @get:Rule val compose = createComposeRule()
    private var repository: TimetableRepository? = null
    private val modelStore = ViewModelStore()
    private var model: TimetableViewModel? = null

    private fun launch(snapshot: TimetableSnapshot?, compactLight: Boolean = false) {
        val storage = UiStorage(snapshot)
        val repo = TimetableRepository(storage, object : SchoolGateway {
            override suspend fun fetch(account: AccountSettings): List<CourseRecord> = error("离线测试")
        })
        repository = repo
        val model = TimetableViewModel(repo)
        this.model = model
        modelStore.put("test", model)
        compose.setContent {
            if (compactLight) {
                val configuration = android.content.res.Configuration(LocalConfiguration.current).apply {
                    uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or android.content.res.Configuration.UI_MODE_NIGHT_NO
                }
                val density = LocalDensity.current
                CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides Density(density.density, 1.3f)) {
                    Box(Modifier.size(360.dp, 640.dp)) { TimetableApp(model) }
                }
            } else TimetableApp(model)
        }
        compose.waitUntil(5000) { !repo.state.value.loading }
        compose.waitForIdle()
    }

    @After fun close() { modelStore.clear(); repository?.close() }

    @Test fun firstLaunchOpensTimetableAndCanEnterSettings() {
        launch(null)
        compose.onNodeWithTag("timetable-grid").assertIsDisplayed()
        compose.onNodeWithText("前往设置登录").performClick()
        compose.onNodeWithTag("student-id").assertIsDisplayed()
        compose.onNodeWithTag("manual-refresh").assertIsNotEnabled()
        compose.onNodeWithTag("close-settings").performClick()
        compose.onNodeWithTag("timetable-grid").assertIsDisplayed()
    }

    @Test fun cachedTimetableShowsTodayAndCourseDetails() {
        launch(sampleSnapshot())
        compose.onNodeWithTag("today-header").assertIsDisplayed()
        val course = repository!!.state.value.snapshot!!.courses.first()
        compose.onNodeWithTag("course-${course.id}-1").performClick()
        compose.onNodeWithTag("course-details").assertIsDisplayed()
        compose.onNodeWithText("二教302", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("任课教师").assertIsDisplayed()
        screenshot("course-details")
    }

    @Test fun selectingAnotherWeekRemovesTodayHighlight() {
        launch(sampleSnapshot())
        compose.onNodeWithTag("week-title").performClick()
        compose.onNodeWithTag("week-picker").assertIsDisplayed()
        screenshot("week-picker")
        compose.onNodeWithTag("week-picker").performScrollToNode(hasTestTag("select-week-2"))
        compose.onNodeWithTag("select-week-2").performClick()
        compose.onNodeWithTag("today-header").assertDoesNotExist()
        compose.onNodeWithTag("week-title").assertTextContains("第 2 周", substring = true)
    }

    @Test fun conflictPickerListsEveryCourse() {
        val original = sampleSnapshot()
        val other = rawCourse("课程冲突示例", "0102")
        launch(original.copy(courses = original.courses + TimetableParser.fromRaw(other)))
        compose.onNodeWithText("2门重叠", useUnmergedTree = true).performClick()
        compose.onNodeWithText("此时段有 2 门课程").assertIsDisplayed()
        compose.onAllNodesWithText("课程冲突示例").assertCountEquals(2)
    }

    @Test fun settingsCanSaveEmojiAndPeriodTime() {
        launch(sampleSnapshot())
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("appearance-code:计算机网络"))
        compose.onNodeWithTag("appearance-code:计算机网络").performClick()
        compose.onNodeWithText("emoji（留空即清除）").performTextInput("🌐")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) { repository!!.state.value.settings.appearances.values.any { it.emoji == "🌐" } }
        assertEquals("🌐", repository!!.state.value.settings.appearances.values.single().emoji)
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("edit-periods"))
        compose.onNodeWithTag("edit-periods").performClick()
        compose.onAllNodesWithText("开始")[0].performTextReplacement("08:05")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) { repository!!.state.value.settings.periods.first().start == "08:05" }
    }

    @Test fun gridScreenshotWithCachedCourses() {
        val original = sampleSnapshot()
        val days = listOf(1, 2, 3, 4, 5)
        val courses = days.flatMap { day ->
            listOf("计算机网络" to "0102", "数据结构与算法" to "0304", "大学英语" to "0506").map { (name, periods) ->
                TimetableParser.fromRaw(JsonObject(rawCourse(name, periods).toMutableMap().apply { put("xq", JsonPrimitive(day)) }))
            }
        }
        launch(original.copy(courses = courses))
        screenshot("timetable")
    }

    @Test fun smallViewportAndLargeFontsKeepTenthAndEndRowsVisible() {
        launch(sampleSnapshot(), compactLight = true)
        val week = weekOf(java.time.LocalDate.now(SCHOOL_ZONE), estimateTermStart(defaultTerm(java.time.LocalDate.now(SCHOOL_ZONE))))
        compose.onNodeWithTag("period-$week-10").assertIsDisplayed()
        compose.onNodeWithTag("period-$week-end").assertIsDisplayed()
        screenshot("compact-light")
    }

    @Test fun allPeriodsFitAndHorizontalSwipesChangeWeekOnly() {
        launch(sampleSnapshot())
        val before = model!!.selectedWeek.value ?: weekOf(java.time.LocalDate.now(SCHOOL_ZONE), estimateTermStart(defaultTerm(java.time.LocalDate.now(SCHOOL_ZONE))))
        compose.onNodeWithTag("period-$before-10").assertIsDisplayed()
        compose.onNodeWithTag("period-$before-end").assertIsDisplayed()
        compose.onNodeWithTag("week-pager").performTouchInput { swipeLeft() }
        compose.waitUntil(5000) { model!!.selectedWeek.value == before + 1 }
        compose.onNodeWithTag("period-${before + 1}-end").assertIsDisplayed()
        compose.onNodeWithTag("week-pager").performTouchInput { swipeUp() }
        compose.onNodeWithTag("period-${before + 1}-end").assertIsDisplayed()
        assertEquals(before + 1, model!!.selectedWeek.value)
        compose.onNodeWithTag("week-pager").performTouchInput { swipeRight() }
        compose.waitUntil(5000) { model!!.selectedWeek.value == before }
    }

    @Test fun weekdaysAreDefaultWeekendCanExpandAndExcludedClassesStayHidden() {
        launch(sampleSnapshot())
        compose.onNodeWithTag("day-header-6").assertDoesNotExist()
        compose.onNodeWithTag("toggle-weekend").performClick()
        compose.onNodeWithTag("day-header-6").assertIsDisplayed()
        compose.onNodeWithTag("day-header-7").assertIsDisplayed()
        compose.onNodeWithTag("toggle-weekend").performClick()
        compose.onNodeWithTag("day-header-6").assertDoesNotExist()
    }

    @Test fun weekendClassAutomaticallyExpandsAndCanBeExcludedInSettings() {
        val original = sampleSnapshot()
        val weekend = TimetableParser.fromRaw(JsonObject(rawCourse("周末测试课程", "0910").toMutableMap().apply { put("xq", JsonPrimitive(6)) }))
        launch(original.copy(courses = original.courses + weekend))
        compose.onNodeWithTag("day-header-6").assertIsDisplayed()
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("hide-weekend-code:周末测试课程"))
        compose.onNodeWithTag("hide-weekend-code:周末测试课程").performClick()
        compose.waitUntil(5000) { repository!!.state.value.settings.hiddenWeekendCourses.isNotEmpty() }
        compose.onNodeWithTag("close-settings").performClick()
        compose.onNodeWithTag("day-header-6").assertDoesNotExist()
        compose.onNodeWithTag("toggle-weekend").performClick()
        compose.onNodeWithTag("day-header-6").assertIsDisplayed()
        compose.onNodeWithTag("course-${weekend.id}-9").assertDoesNotExist()
    }

    @Test fun monetCanSwitchToCustomAndBack() {
        launch(sampleSnapshot())
        assertTrue(repository!!.state.value.settings.useDynamicColor)
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("custom-theme").performClick()
        compose.onNodeWithTag("theme-color-hex").performTextReplacement("427E89")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) { !repository!!.state.value.settings.useDynamicColor }
        assertEquals(0xFF427E89L, repository!!.state.value.settings.customPrimaryColor)
        compose.onNodeWithTag("dynamic-color-switch").performClick()
        compose.waitUntil(5000) { repository!!.state.value.settings.useDynamicColor }
        screenshot("settings")
    }

    @Test fun backgroundImportScalesAndPersistsAndCanRestoreDefault() {
        launch(sampleSnapshot())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "background-test.png")
        val bitmap = android.graphics.Bitmap.createBitmap(2200, 1200, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(170, 200, 240))
        source.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        compose.runOnIdle { model!!.importBackground(context, android.net.Uri.fromFile(source)) }
        compose.waitUntil(10000) { repository!!.state.value.settings.backgroundPath != null }
        val imported = File(repository!!.state.value.settings.backgroundPath!!)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(imported.absolutePath, bounds)
        assertTrue(maxOf(bounds.outWidth, bounds.outHeight) <= 1920)
        compose.runOnIdle { model!!.setBackgroundTransparency(0.6f) }
        compose.waitUntil(5000) { repository!!.state.value.settings.backgroundTransparency == 0.6f }
        compose.runOnIdle { model!!.removeBackground() }
        compose.waitUntil(5000) { repository!!.state.value.settings.backgroundPath == null }
        assertFalse(imported.exists())
        source.delete()
    }

    private fun sampleSnapshot(): TimetableSnapshot {
        val today = java.time.LocalDate.now(SCHOOL_ZONE)
        val term = defaultTerm(today)
        val snapshot = TimetableSnapshot("test", term, 0, listOf(TimetableParser.fromRaw(rawCourse("计算机网络", "0102"))))
        return snapshot
    }

    private fun rawCourse(name: String, periods: String): JsonObject = buildJsonObject {
        put("kcmc", name); put("kcdm", name); put("teaxms", "李老师"); put("jxcdmc", "二教302")
        put("xq", java.time.LocalDate.now(SCHOOL_ZONE).dayOfWeek.value); put("jcdm", periods); put("zc", "1-20")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots/$name.png")
        file.parentFile!!.mkdirs()
        instrumentation.uiAutomation.takeScreenshot().useBitmap { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private inline fun android.graphics.Bitmap.useBitmap(block: (android.graphics.Bitmap) -> Unit) { try { block(this) } finally { recycle() } }

    private class UiStorage(var snapshot: TimetableSnapshot?) : TimetableStorage {
        var settings = AppSettings()
        override fun loadSnapshot() = snapshot
        override fun saveSnapshot(snapshot: TimetableSnapshot) { this.snapshot = snapshot }
        override fun loadSettings() = settings
        override fun saveSettings(settings: AppSettings) { this.settings = settings }
        override fun loadAccount(): AccountSettings? = null
        override fun saveAccount(account: AccountSettings?) = Unit
    }
}
