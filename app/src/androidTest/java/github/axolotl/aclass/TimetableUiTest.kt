package github.axolotl.aclass

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
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
import java.time.Clock
import java.time.Instant

class TimetableUiTest {
    @get:Rule val compose = createComposeRule()
    private val restoration = StateRestorationTester(compose)
    private var repository: TimetableRepository? = null
    private val modelStore = ViewModelStore()
    private var model: TimetableViewModel? = null
    private var storage: UiStorage? = null
    private val generatedFiles = mutableListOf<File>()

    private fun launch(snapshot: TimetableSnapshot?, compactLight: Boolean = false,
                       darkMode: Boolean? = null, settings: AppSettings = AppSettings(), clock: Clock = schoolClock()) {
        val storage = UiStorage(snapshot)
        storage.settings = settings
        this.storage = storage
        val repo = TimetableRepository(storage, object : SchoolGateway {
            override suspend fun fetch(account: AccountSettings): List<CourseRecord> = error("离线测试")
        })
        repository = repo
        val model = TimetableViewModel(repo, clock)
        this.model = model
        modelStore.put("test", model)
        restoration.setContent {
            if (compactLight || darkMode != null) {
                val configuration = android.content.res.Configuration(LocalConfiguration.current).apply {
                    uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (darkMode == true) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
                }
                val density = LocalDensity.current
                CompositionLocalProvider(LocalConfiguration provides configuration,
                    LocalDensity provides Density(density.density, if (compactLight) 1.3f else density.fontScale)) {
                    Box(if (compactLight) Modifier.size(360.dp, 640.dp) else Modifier) { TimetableApp(model) }
                }
            } else TimetableApp(model)
        }
        compose.waitUntil(5000) { !repo.state.value.loading }
        compose.waitForIdle()
    }

    @After fun close() { modelStore.clear(); repository?.close(); generatedFiles.forEach { it.delete() } }

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
        assertCourseInformationCompact(course, 1)
        compose.onNodeWithTag("course-${course.id}-1").performClick()
        compose.onNodeWithTag("course-details").assertIsDisplayed()
        compose.onAllNodesWithText("二教302", useUnmergedTree = true)[0].assertExists()
        compose.onNodeWithText("任课教师").performScrollTo().assertIsDisplayed()
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
        compose.onNodeWithTag("today-column").assertDoesNotExist()
        compose.onNodeWithTag("week-title").assertTextContains("第 2 周", substring = true)
    }

    @Test fun conflictPickerListsEveryCourse() {
        val original = sampleSnapshot()
        val other = rawCourse("课程冲突示例", "0102")
        val courses = original.courses + TimetableParser.fromRaw(other)
        launch(original.copy(courses = courses), clock = Clock.fixed(
            java.time.LocalDate.now(SCHOOL_ZONE).atTime(8, 5).atZone(SCHOOL_ZONE).toInstant(), SCHOOL_ZONE))
        assertCourseInformationCompact(courses.minBy { it.id }, 1)
        compose.onNodeWithTag("next-course-badge", useUnmergedTree = true).assertContentDescriptionEquals("已开始，当前高亮课程").assertIsDisplayed()
        compose.onNodeWithTag("next-course-hint").assertContentDescriptionContains("2门重叠", substring = true)
        screenshot("highlight-conflict-started")
        compose.onNodeWithText("2门重叠", useUnmergedTree = true).performClick()
        compose.onNodeWithText("此时段有 2 门课程").assertIsDisplayed()
        compose.onAllNodesWithText("课程冲突示例").assertCountEquals(2)
    }

    @Test fun settingsCanSaveEmojiAndPeriodTime() {
        launch(sampleSnapshot())
        compose.onNodeWithTag("open-settings").performClick()
        expandSection("appearance")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("appearance-code:计算机网络"))
        compose.onNodeWithTag("appearance-code:计算机网络").performClick()
        compose.onNodeWithTag("appearance-emoji").performScrollTo().performTextInput("🌐")
        compose.onNodeWithTag("save-appearance").performScrollTo().performClick()
        compose.waitUntil(5000) { repository!!.state.value.settings.appearances.values.any { it.emoji == "🌐" } }
        assertEquals("🌐", repository!!.state.value.settings.appearances.values.single().emoji)
        expandSection("schedule")
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
        assertCourseInformationCompact(repository!!.state.value.snapshot!!.courses.first(), 1)
        val week = weekOf(java.time.LocalDate.now(SCHOOL_ZONE), estimateTermStart(defaultTerm(java.time.LocalDate.now(SCHOOL_ZONE))))
        compose.onNodeWithTag("period-$week-10").assertIsDisplayed()
        compose.onNodeWithTag("period-$week-end").assertIsDisplayed()
        screenshot("compact-light")
    }

    @Test fun compactSevenColumnSinglePeriodKeepsLongNameAndLocationVisible() {
        val original = sampleSnapshot()
        val longCourse = TimetableParser.fromRaw(JsonObject(rawCourse("大学生职业生涯与发展规划", "07").toMutableMap().apply {
            put("xq", JsonPrimitive(6)); put("jxcdmc", JsonPrimitive("实验教学楼A座302教室"))
        }))
        launch(original.copy(courses = listOf(longCourse)), compactLight = true,
            settings = AppSettings(termStarts = mapOf(original.scopeKey to "2026-10-12")),
            clock = Clock.fixed(Instant.parse("2026-10-16T23:00:00Z"), SCHOOL_ZONE))
        compose.onNodeWithTag("day-header-7").assertIsDisplayed()
        assertCourseInformationCompact(longCourse, 7)
        compose.onNodeWithTag("next-course-badge", useUnmergedTree = true).assertContentDescriptionEquals("下一节课程").assertIsDisplayed()
        val card = hasTestTag("course-${longCourse.id}-7")
        val title = compose.onNode(hasTestTag("course-title") and hasAnyAncestor(card), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val badge = compose.onNodeWithTag("next-course-badge", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Corner star must not cover a compact course title", title.right <= badge.left + 1f)
        screenshot("compact-seven-single-period")
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
        expandSection("weekend")
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
        expandSection("theme")
        compose.onNodeWithTag("custom-theme").performClick()
        compose.onNodeWithTag("theme-color-hex").performTextReplacement("427E89")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) { !repository!!.state.value.settings.useDynamicColor }
        assertEquals(0xFF427E89L, repository!!.state.value.settings.customPrimaryColor)
        compose.onNodeWithTag("dynamic-color-switch").performClick()
        compose.waitUntil(5000) { repository!!.state.value.settings.useDynamicColor }
        screenshot("settings")
    }

    @Test fun courseDetailsCanSaveClearAndDiscardAppearanceDrafts() {
        val original = sampleSnapshot()
        val course = original.courses.first()
        val other = TimetableParser.fromRaw(JsonObject(course.raw.toMutableMap().apply {
            put("xq", JsonPrimitive(if (course.day == 1) 2 else 1)); put("jxcdmc", JsonPrimitive("另一教室"))
        }))
        launch(original.copy(courses = listOf(course, other)))
        compose.onNodeWithTag("course-${course.id}-1").performClick()
        chooseHex("#18304A")
        compose.onNodeWithTag("appearance-emoji").performScrollTo().performTextReplacement("🧑‍💻")
        compose.onNodeWithTag("save-appearance").performScrollTo().performClick()
        compose.waitUntil(5000) { repository!!.state.value.settings.appearances.values.any { it.emoji == "🧑‍💻" } }
        compose.onNodeWithTag("course-details").assertIsDisplayed()
        val appearance = CourseAppearance(0xFF18304AL, "🧑‍💻")
        assertEquals(appearance, repository!!.state.value.settings.appearance(original, course))
        assertEquals(appearance, repository!!.state.value.settings.appearance(original, other))
        compose.onNodeWithTag("appearance-emoji").performScrollTo().performTextReplacement("未保存")
        dismissSheet()
        compose.onNodeWithTag("course-${course.id}-1").performClick()
        compose.onNodeWithTag("appearance-emoji").performScrollTo().assertTextContains("🧑‍💻")
        compose.onNodeWithText("清除 emoji").performScrollTo().performClick()
        compose.onNodeWithTag("save-appearance").performScrollTo().performClick()
        compose.waitUntil(5000) { repository!!.state.value.settings.appearances.values.single().emoji.isEmpty() }
        dismissSheet()
        compose.onNodeWithTag("course-${course.id}-1").assertIsDisplayed()
        compose.onAllNodesWithText("🧑‍💻").assertCountEquals(0)
    }

    @Test fun invalidColorAndFailedSaveKeepDraftForRetry() {
        launch(sampleSnapshot())
        val course = repository!!.state.value.snapshot!!.courses.first()
        compose.onNodeWithTag("course-${course.id}-1").performClick()
        compose.onNodeWithTag("open-course-color").performScrollTo().performClick()
        screenshot("circle-color-picker")
        compose.onNodeWithTag("appearance-hex").performScrollTo().performTextReplacement("invalid")
        compose.onNodeWithTag("confirm-course-color").performClick()
        compose.onNodeWithText("请输入6位十六进制颜色").performScrollTo().assertIsDisplayed()
        assertTrue(storage!!.settings.appearances.isEmpty())
        compose.onNodeWithTag("appearance-hex").performScrollTo().performTextReplacement("AABBCC")
        compose.onNodeWithTag("confirm-course-color").performClick()
        compose.onNodeWithTag("appearance-emoji").performScrollTo().performTextReplacement("🌐")
        storage!!.failSettingsSave = true
        compose.onNodeWithTag("save-appearance").performScrollTo().performClick()
        compose.onNodeWithTag("appearance-save-error").performScrollTo().assertTextContains("磁盘写入失败")
        assertTrue(repository!!.state.value.settings.appearances.isEmpty())
        compose.onNodeWithTag("appearance-emoji").performScrollTo().assertTextContains("🌐")
        storage!!.failSettingsSave = false
        compose.onNodeWithTag("save-appearance").performScrollTo().performClick()
        compose.waitUntil(5000) { repository!!.state.value.settings.appearances.values.any { it.emoji == "🌐" } }
        compose.onNodeWithTag("appearance-save-error").assertDoesNotExist()
    }

    @Test fun conflictSelectionEditsOnlyChosenCourse() {
        val original = sampleSnapshot()
        val other = TimetableParser.fromRaw(rawCourse("课程冲突示例", "0102"))
        launch(original.copy(courses = original.courses + other))
        compose.onNodeWithText("2门重叠", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("select-conflict-${other.id}").performClick()
        compose.onNodeWithTag("appearance-emoji").performScrollTo().performTextReplacement("📘")
        compose.onNodeWithTag("save-appearance").performScrollTo().performClick()
        compose.waitUntil(5000) { repository!!.state.value.settings.appearances.isNotEmpty() }
        assertEquals(setOf("${original.scopeKey}:${other.courseKey}"), repository!!.state.value.settings.appearances.keys)
    }

    @Test fun settingsAccordionKeepsManyCoursesCollapsedAndRetainsDrafts() {
        val original = sampleSnapshot()
        val courses = (1..80).map { TimetableParser.fromRaw(rawCourse("课程%02d".format(it), "0102")) }
        launch(original.copy(courses = courses))
        compose.onNodeWithTag("open-settings").performClick()
        compose.onAllNodes(hasTestTagPrefix("appearance-code:")).assertCountEquals(0)
        screenshot("settings-collapsed")
        expandSection("account")
        compose.onNodeWithTag("student-id").performTextReplacement("draft123")
        expandSection("appearance")
        compose.onNodeWithTag("student-id").assertDoesNotExist()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("appearance-code:课程80"))
        compose.onNodeWithTag("appearance-code:课程80").assertIsDisplayed()
        expandSection("account")
        compose.onAllNodes(hasTestTagPrefix("appearance-code:")).assertCountEquals(0)
        compose.onNodeWithTag("student-id").assertTextContains("draft123")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("student-id").assertTextContains("draft123")
        compose.onNodeWithTag("section-account").performClick()
        compose.onNodeWithTag("student-id").assertDoesNotExist()
    }

    @Test fun compactDetailsCanScrollToEditorAndSaveWithKeyboard() {
        launch(sampleSnapshot(), compactLight = true)
        val course = repository!!.state.value.snapshot!!.courses.first()
        compose.onNodeWithTag("course-${course.id}-1").performClick()
        compose.onNodeWithTag("appearance-emoji").performScrollTo().performClick().performTextReplacement("📚")
        compose.onNodeWithTag("save-appearance").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5000) { repository!!.state.value.settings.appearances.values.any { it.emoji == "📚" } }
        screenshot("compact-editor-keyboard")
    }

    @Test fun circularPickerSupportsCancelPresetsBrightnessHexAndRestoration() {
        val original = sampleSnapshot()
        val course = original.courses.single()
        launch(original, compactLight = true)
        compose.onNodeWithTag("course-${course.id}-1").performClick()
        compose.onNodeWithTag("open-course-color").performScrollTo().performClick()
        compose.onNodeWithTag("course-color-wheel").performTouchInput { swipe(center, centerRight) }
        compose.onNodeWithTag("course-color-brightness").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.4f) }
        compose.onNodeWithTag("appearance-color-15684432").performScrollTo().performClick() // EF5350
        compose.onNodeWithTag("cancel-course-color").performClick()
        compose.onNodeWithTag("save-appearance").performScrollTo().performClick()
        compose.waitUntil(5000) { storage!!.settings.appearances.isNotEmpty() }
        assertEquals(AppSettings().appearance(original, course), storage!!.settings.appearance(original, course))
        compose.onNodeWithTag("open-course-color").performScrollTo().performClick()
        compose.onNodeWithTag("appearance-hex").performScrollTo().performTextReplacement("123456")
        compose.onNodeWithTag("confirm-course-color").assertIsDisplayed()
        compose.onNodeWithTag("cancel-course-color").assertIsDisplayed()
        screenshot("compact-circle-picker-hex")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("appearance-hex").performScrollTo().assertTextContains("123456")
        compose.onNodeWithTag("confirm-course-color").performClick()
        compose.onNodeWithTag("appearance-emoji").performScrollTo().performTextReplacement("🧑‍💻")
        compose.onNodeWithTag("save-appearance").performScrollTo().performClick()
        compose.waitUntil(5000) { storage!!.settings.appearance(original, course).color == 0xFF123456L }
        screenshot("compact-circle-picker-saved")
    }

    @Test fun settingsCourseEditorSharesTheColorPicker() {
        val original = sampleSnapshot()
        val course = original.courses.single()
        launch(original)
        compose.onNodeWithTag("open-settings").performClick()
        expandSection("appearance")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("appearance-${course.courseKey}"))
        compose.onNodeWithTag("appearance-${course.courseKey}").performClick()
        chooseHex("18304A")
        compose.onNodeWithTag("appearance-emoji").performScrollTo().performTextReplacement("📚")
        compose.onNodeWithTag("save-appearance").performScrollTo().performClick()
        compose.waitUntil(5000) { storage!!.settings.appearance(original, course) == CourseAppearance(0xFF18304AL, "📚") }
        compose.onNodeWithTag("appearance-form").assertDoesNotExist()
    }

    @Test fun detailsShowChineseMetadataAndKeepExtraInformationCollapsed() {
        val original = sampleSnapshot()
        val course = TimetableParser.fromRaw(JsonObject(original.courses.single().raw.toMutableMap().apply {
            put("zxs", JsonPrimitive(1)); put("xs", JsonPrimitive(4)); put("xf", JsonPrimitive("2.5"))
            put("jxhjmc", JsonPrimitive("实践教学")); put("sknrjj", JsonPrimitive("实验内容简介"))
            put("jxcddm", JsonPrimitive("隐藏的场地代码"))
        }))
        launch(original.copy(courses = listOf(course)))
        compose.onNodeWithTag("course-${course.id}-1").performClick()
        compose.onNodeWithText("总学时").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("本次学时").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("学分").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("上课日期").assertDoesNotExist()
        compose.onNodeWithText("考试形式").assertDoesNotExist()
        compose.onNodeWithTag("course-more-information").performScrollTo().performClick()
        compose.onNodeWithText("实践教学").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("授课内容简介").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("隐藏的场地代码").assertDoesNotExist()
    }

    @Test fun nextCourseHintLocatesFutureWeekAndExpandsHiddenWeekendColumns() {
        val course = TimetableParser.fromRaw(JsonObject(rawCourse("下周实验", "0102").toMutableMap().apply {
            put("xq", JsonPrimitive(6)); put("zc", JsonPrimitive("2"))
        }))
        val snapshot = sampleSnapshot().copy(courses = listOf(course))
        val settings = AppSettings(termStarts = mapOf(snapshot.scopeKey to "2026-10-12"))
        launch(snapshot, settings = settings, clock = Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), SCHOOL_ZONE))
        compose.onNodeWithTag("week-title").assertTextContains("第 1 周", substring = true)
        compose.onNodeWithTag("next-course-hint").assertContentDescriptionContains("下周实验", substring = true)
        compose.onNodeWithTag("next-course-hint").performClick()
        compose.onNodeWithTag("week-title").assertTextContains("第 2 周", substring = true)
        compose.onNodeWithTag("day-header-6").assertIsDisplayed()
        compose.onNodeWithTag("course-${course.id}-1").assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "下一节课程"))
        compose.onNodeWithTag("next-course-badge", useUnmergedTree = true).assertContentDescriptionEquals("下一节课程").assertIsDisplayed()
        compose.onNodeWithTag("toggle-weekend").performClick()
        compose.onNodeWithTag("day-header-6").assertDoesNotExist()
        compose.onNodeWithTag("next-course-hint").performClick()
        compose.onNodeWithTag("day-header-6").assertIsDisplayed()
        screenshot("next-course-weekend")
    }

    @Test fun highlightSettingPersistsAndImmediatelyChangesTarget() {
        val course = TimetableParser.fromRaw(JsonObject(rawCourse("已开始课程", "0102").toMutableMap().apply {
            put("xq", JsonPrimitive(1)); put("zc", JsonPrimitive("1"))
        }))
        val snapshot = sampleSnapshot().copy(courses = listOf(course))
        launch(snapshot, settings = AppSettings(termStarts = mapOf(snapshot.scopeKey to "2026-10-12")),
            clock = Clock.fixed(Instant.parse("2026-10-12T00:05:00Z"), SCHOOL_ZONE))
        compose.onNodeWithTag("next-course-hint").assertContentDescriptionContains("已开始", substring = true)
        compose.onNodeWithTag("open-settings").performClick()
        expandSection("schedule")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("highlight-minutes"))
        compose.onNodeWithTag("highlight-minutes").performTextReplacement("0")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("save-highlight-minutes"))
        compose.onNodeWithTag("save-highlight-minutes").performClick()
        compose.waitUntil(5000) { storage!!.settings.highlightMinutes == 0 }
        compose.onNodeWithTag("close-settings").performClick()
        compose.onNodeWithTag("next-course-hint").assertDoesNotExist()
        compose.onNodeWithTag("open-settings").performClick()
        expandSection("schedule")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("highlight-minutes"))
        compose.onNodeWithTag("highlight-minutes").assertTextContains("0")
    }

    @Test fun clockDeadlineAdvancesHighlightWithoutChangingBrowsedWeek() {
        fun mondayCourse(name: String, periods: String) = TimetableParser.fromRaw(JsonObject(rawCourse(name, periods).toMutableMap().apply {
            put("xq", JsonPrimitive(1)); put("zc", JsonPrimitive("1"))
        }))
        val first = mondayCourse("早课", "0102")
        val later = mondayCourse("下一堂", "0304")
        val snapshot = sampleSnapshot().copy(courses = listOf(first, later))
        val clock = UiClock(Instant.parse("2026-10-12T00:09:59Z"))
        launch(snapshot, settings = AppSettings(termStarts = mapOf(snapshot.scopeKey to "2026-10-12")), clock = clock)
        compose.onNodeWithTag("course-${first.id}-1").assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已开始，当前高亮课程"))
        compose.onNodeWithTag("next-course-badge", useUnmergedTree = true).assertContentDescriptionEquals("已开始，当前高亮课程").assertIsDisplayed()
        compose.runOnIdle { model!!.selectWeek(2) }
        compose.onNodeWithTag("week-title").assertTextContains("第 2 周", substring = true)
        compose.runOnIdle {
            clock.value = Instant.parse("2026-10-12T00:10:00Z")
            model!!.onForeground()
        }
        compose.onNodeWithTag("next-course-hint").assertContentDescriptionContains("下一堂", substring = true)
        compose.onNodeWithTag("week-title").assertTextContains("第 2 周", substring = true)
        compose.onNodeWithTag("next-course-hint").performClick()
        compose.onNodeWithTag("week-title").assertTextContains("第 1 周", substring = true)
        compose.onNodeWithTag("course-${later.id}-3").assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "下一节课程"))
        compose.onNodeWithTag("next-course-badge", useUnmergedTree = true).assertContentDescriptionEquals("下一节课程").assertIsDisplayed()
    }

    private class UiClock(var value: Instant) : Clock() {
        override fun getZone(): java.time.ZoneId = SCHOOL_ZONE
        override fun withZone(zone: java.time.ZoneId): Clock = Clock.fixed(value, zone)
        override fun instant(): Instant = value
    }

    @Test fun lightWallpaperKeepsFiveColumnGridAndTodayReadable() { wallpaperScenario(dark = false, weekends = false) }
    @Test fun darkWallpaperKeepsSevenColumnGridAndTodayReadable() { wallpaperScenario(dark = true, weekends = true) }

    private fun wallpaperScenario(dark: Boolean, weekends: Boolean) {
        val original = sampleSnapshot()
        val courses = (1..if (weekends) 7 else 5).flatMap { day ->
            listOf("人工智能导论" to "0102", "程序设计基础" to "0304", "大学英语视听说（一）" to "0506", "大学生职业生涯与发展规划" to "07").map { (name, periods) ->
                TimetableParser.fromRaw(JsonObject(rawCourse(name, periods).toMutableMap().apply { put("xq", JsonPrimitive(day)) }))
            }
        }
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "visual-wallpaper-${if (dark) "dark" else "light"}.png")
        generatedFiles += file
        val bitmap = android.graphics.Bitmap.createBitmap(600, 1200, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 600f, 1200f,
                if (dark) intArrayOf(0xFF152A48.toInt(), 0xFF506783.toInt(), 0xFF1C2F44.toInt())
                else intArrayOf(0xFFE5F0FF.toInt(), 0xFF9EC6E8.toInt(), 0xFFFFE6BC.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, 600f, 1200f, paint)
        paint.shader = null
        paint.color = if (dark) 0x996A8CB0.toInt() else 0x99FFFFFF.toInt()
        for (i in 0..6) canvas.drawCircle((i * 137 % 600).toFloat(), (i * 173).toFloat(), 100f, paint)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val appearances = courses.associate { "${original.scopeKey}:${it.courseKey}" to CourseAppearance(0xFFADC9F2L, "📚") }
        launch(original.copy(courses = courses), darkMode = dark,
            settings = AppSettings(backgroundPath = file.absolutePath, backgroundTransparency = 0.05f, appearances = appearances,
                termStarts = mapOf(original.scopeKey to "2026-10-12")),
            clock = Clock.fixed(Instant.parse("2026-10-11T23:00:00Z"), SCHOOL_ZONE))
        compose.waitUntil(5000) { compose.onAllNodesWithTag("timetable-background-image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("today-header").assertIsDisplayed()
        compose.onNodeWithTag("today-column").assertIsDisplayed()
        if (weekends) compose.onNodeWithTag("day-header-7").assertIsDisplayed()
        compose.onNodeWithTag("next-course-badge", useUnmergedTree = true).assertContentDescriptionEquals("下一节课程").assertIsDisplayed()
        courses.filter { it.day == 1 }.forEach { assertCourseInformationCompact(it, it.periods.min()) }
        val highlightedCourse = courses.first { it.day == 1 && it.periods.min() == 1 }
        val normalCourse = courses.first { it.day == 2 && it.periods.min() == 1 }
        fun titleOffset(course: CourseRecord): Float {
            val card = hasTestTag("course-${course.id}-1")
            val title = compose.onNode(hasTestTag("course-title") and hasAnyAncestor(card), useUnmergedTree = true)
            title.assertTextEquals(course.name)
            return title.fetchSemanticsNode().boundsInRoot.top - compose.onNode(card).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals("Highlight must keep emoji and title positions", titleOffset(normalCourse), titleOffset(highlightedCourse), 1f)
        screenshot(if (dark) "wallpaper-dark-seven" else "wallpaper-light-five")
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
        compose.waitUntil(5000) { repository!!.state.value.settings.backgroundPath == null && !model!!.working.value }
        assertFalse(imported.exists())
        source.delete()
    }

    @Test fun mainUiOpacitySliderSavesAndSurvivesSettingsReentry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wallpaper = File(context.cacheDir, "opacity-wallpaper.png")
        generatedFiles += wallpaper
        val bitmap = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF9EC6E8.toInt())
        wallpaper.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        launch(sampleSnapshot(), settings = AppSettings(backgroundPath = wallpaper.absolutePath))
        compose.waitUntil(5000) { compose.onAllNodesWithTag("timetable-background-image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("open-settings").performClick()
        expandSection("background")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("main-ui-opacity"))
        compose.onNodeWithTag("main-ui-opacity").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.35f) }
        compose.waitUntil(5000) { repository!!.state.value.settings.mainUiOpacity == 0.35f }
        assertEquals(0.35f, storage!!.settings.mainUiOpacity)
        assertEquals(0.25f, storage!!.settings.backgroundTransparency)
        compose.onNodeWithTag("close-settings").performClick()
        compose.onNodeWithTag("today-header").assertIsDisplayed()
        screenshot("main-ui-opacity-35")
        compose.onNodeWithTag("open-settings").performClick()
        expandSection("background")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("main-ui-opacity"))
        compose.onNodeWithTag("main-ui-opacity").assertRangeInfoEquals(
            androidx.compose.ui.semantics.ProgressBarRangeInfo(0.35f, 0f..1f))
        screenshot("main-ui-opacity-settings")
        listOf(0f, 1f).forEach { value ->
            compose.onNodeWithTag("main-ui-opacity").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(value) }
            compose.waitUntil(5000) { repository!!.state.value.settings.mainUiOpacity == value }
            compose.onNodeWithTag("close-settings").performClick()
            compose.onNodeWithTag("today-header").assertIsDisplayed()
            compose.onNodeWithTag("week-title").assertIsDisplayed()
            assertCourseInformationCompact(repository!!.state.value.snapshot!!.courses.first(), 1)
            screenshot("main-ui-opacity-${(value * 100).toInt()}")
            compose.onNodeWithTag("open-settings").performClick()
            expandSection("background")
            compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("main-ui-opacity"))
        }
    }

    private fun assertCourseInformationCompact(course: CourseRecord, start: Int) {
        val card = hasTestTag("course-${course.id}-$start")
        val title = compose.onNode(hasTestTag("course-title") and hasAnyAncestor(card), useUnmergedTree = true)
        val location = compose.onNode(hasTestTag("course-location") and hasAnyAncestor(card), useUnmergedTree = true)
        title.assertIsDisplayed()
        location.assertIsDisplayed()
        val titleBounds = title.fetchSemanticsNode().boundsInRoot
        val locationBounds = location.fetchSemanticsNode().boundsInRoot
        val cardBounds = compose.onNode(card).fetchSemanticsNode().boundsInRoot
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertEquals(2f * density, locationBounds.top - titleBounds.bottom, 1f)
        assertTrue("Location must fit inside its card", locationBounds.bottom <= cardBounds.bottom)
    }

    private fun sampleSnapshot(): TimetableSnapshot {
        val today = java.time.LocalDate.now(SCHOOL_ZONE)
        val term = defaultTerm(today)
        val snapshot = TimetableSnapshot("test", term, 0, listOf(TimetableParser.fromRaw(rawCourse("计算机网络", "0102"))))
        return snapshot
    }

    private fun expandSection(id: String) {
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("section-$id"))
        compose.onNodeWithTag("section-$id").performClick()
    }

    private fun chooseHex(hex: String) {
        compose.onNodeWithTag("open-course-color").performScrollTo().performClick()
        compose.onNodeWithTag("appearance-hex").performScrollTo().performTextReplacement(hex)
        compose.onNodeWithTag("confirm-course-color").performClick()
    }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("test tag starts with $prefix") {
        it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith(prefix)
    }

    private fun dismissSheet() {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("course-details").assertDoesNotExist()
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
        @Volatile var failSettingsSave = false
        override fun loadSnapshot() = snapshot
        override fun saveSnapshot(snapshot: TimetableSnapshot) { this.snapshot = snapshot }
        override fun loadSettings() = settings
        override fun saveSettings(settings: AppSettings) {
            if (failSettingsSave) throw java.io.IOException("磁盘写入失败")
            this.settings = settings
        }
        override fun loadAccount(): AccountSettings? = null
        override fun saveAccount(account: AccountSettings?) = Unit
    }
}
