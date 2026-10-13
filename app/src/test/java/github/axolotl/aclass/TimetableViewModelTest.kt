package github.axolotl.aclass

import androidx.lifecycle.ViewModelStore
import github.axolotl.aclass.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class TimetableViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var repository: TimetableRepository
    private val storage = MemoryStorage()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); if (::repository.isInitialized) repository.close(); Dispatchers.resetMain() }

    private fun createModel(clock: Clock = schoolClock()): TimetableViewModel {
        repository = TimetableRepository(storage, object : SchoolGateway {
            override suspend fun fetch(account: AccountSettings) = testSnapshot().courses
        }, dispatcher)
        return TimetableViewModel(repository, clock).also { store.put("model", it) }
    }

    private fun modelTest(block: suspend TestScope.(TimetableViewModel) -> Unit) = runTest(dispatcher) {
        val model = createModel()
        try { block(model) } finally { store.clear(); repository.close() }
    }

    @Test fun gestureSettlementsDoNotNavigateAndCannotOverwriteNewerRequests() = modelTest { model ->
        runCurrent()
        val initialNavigation = model.weekNavigationId.value
        model.settleWeek(2, initialNavigation)
        model.settleWeek(3, initialNavigation)
        assertEquals(3, model.selectedWeek.value)
        assertEquals(initialNavigation, model.weekNavigationId.value)

        model.selectWeek(8)
        val requestedNavigation = model.weekNavigationId.value
        model.settleWeek(4, initialNavigation)
        assertEquals(8, model.selectedWeek.value)
        model.settleWeek(6, requestedNavigation) // a gesture interrupts the request
        assertEquals(6, model.selectedWeek.value)
        assertEquals(requestedNavigation, model.weekNavigationId.value)

        model.selectWeek(6) // the same week is still a new request with its own acknowledgement
        assertTrue(model.weekNavigationId.value > requestedNavigation)
        model.settleWeek(7, requestedNavigation)
        assertEquals(6, model.selectedWeek.value)
    }

    @Test fun autoSaveDebouncesLatestAppearanceAndDoesNotBlockEditing() = modelTest { model ->
        runCurrent()
        val snapshot = repository.state.value.snapshot!!
        val appearance = CourseAppearance(0xFF18304AL, "🧑‍💻")
        val draft = model.appearanceDraft(snapshot.scopeKey, snapshot.courses.first(), AppSettings().appearance(snapshot, snapshot.courses.first()))
        draft.change(CourseAppearance(0xFFAABBCCL))
        advanceTimeBy(200)
        draft.change(appearance)
        advanceTimeBy(399)
        assertFalse(model.working.value)
        assertTrue(repository.state.value.settings.appearances.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertFalse(model.working.value)
        assertEquals(1, storage.saves)
        assertEquals(appearance, storage.settings.appearances.values.single())
        assertEquals(storage.settings, repository.state.value.settings)
    }

    @Test fun failureKeepsOldSettingsAndRetryCanSucceed() = modelTest { model ->
        runCurrent()
        val snapshot = repository.state.value.snapshot!!
        val appearance = CourseAppearance(0xFFAABBCCL, "")
        storage.failSave = true
        val draft = model.appearanceDraft(snapshot.scopeKey, snapshot.courses.first(), AppSettings().appearance(snapshot, snapshot.courses.first()))
        draft.attach()
        draft.change(appearance, immediate = true)
        runCurrent()
        assertEquals("磁盘写入失败", draft.state.value.error)
        assertTrue(draft.state.value.canRetry)
        assertTrue(repository.state.value.settings.appearances.isEmpty())
        assertTrue(storage.settings.appearances.isEmpty())
        assertFalse(model.working.value)
        storage.failSave = false
        draft.retry()
        runCurrent()
        assertNull(draft.state.value.error)
        assertEquals(appearance, repository.state.value.settings.appearances.values.single())
    }

    @Test fun saveUsesOpeningScopeEvenAfterAccountAndTermChange() = modelTest { model ->
        runCurrent()
        val opened = repository.state.value.snapshot!!
        assertEquals(RefreshResult.Success, repository.refresh(AccountSettings("other", "password", "202602")))
        val appearance = CourseAppearance(0xFF18304AL, "🌐")
        model.appearanceDraft(opened.scopeKey, opened.courses.first(), AppSettings().appearance(opened, opened.courses.first())).change(appearance, immediate = true)
        runCurrent()
        assertEquals(setOf("${opened.scopeKey}:${opened.courses.first().courseKey}"), storage.settings.appearances.keys)
        assertEquals("other:202602", repository.state.value.snapshot!!.scopeKey)
    }

    @Test fun opacitySaveFailureKeepsValueAndReportsErrorThenAllowsRetry() = modelTest { model ->
        runCurrent()
        val error = async { model.events.first() }
        storage.failSave = true
        model.setMainUiOpacity(0.45f)
        runCurrent()
        assertEquals("磁盘写入失败", error.await())
        assertEquals(0.82f, repository.state.value.settings.mainUiOpacity)
        assertEquals(0.82f, storage.settings.mainUiOpacity)
        storage.failSave = false
        model.setMainUiOpacity(0.45f)
        assertEquals(0.82f, repository.state.value.settings.mainUiOpacity)
        runCurrent()
        assertEquals(0.45f, repository.state.value.settings.mainUiOpacity)
        assertEquals(0.45f, storage.settings.mainUiOpacity)
    }

    @Test fun opacitySetterClampsBoundsAndRejectsNonFiniteValues() = modelTest { model ->
        runCurrent()
        model.setMainUiOpacity(-1f)
        runCurrent()
        assertEquals(0f, repository.state.value.settings.mainUiOpacity)
        model.setMainUiOpacity(2f)
        runCurrent()
        assertEquals(1f, repository.state.value.settings.mainUiOpacity)
        val error = async { model.events.first() }
        model.setMainUiOpacity(Float.NaN)
        runCurrent()
        assertEquals("不透明度应为有效数值", error.await())
        assertEquals(1f, repository.state.value.settings.mainUiOpacity)
    }

    @Test fun foregroundClockTicksAtMinuteBoundaryAndStopsInBackground() = runTest(dispatcher) {
        val clock = MutableClock(Instant.parse("2026-10-12T00:09:30Z"))
        val model = createModel(clock)
        runCurrent()
        model.onForeground()
        model.onForeground() // repeated foreground events must not create duplicate tickers
        runCurrent()
        clock.value = Instant.parse("2026-10-12T00:10:00Z")
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(clock.value, model.now.value)
        model.onBackground()
        clock.value = Instant.parse("2026-10-12T16:00:00Z")
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(Instant.parse("2026-10-12T00:10:00Z"), model.now.value)
        model.onForeground()
        assertEquals(clock.value, model.now.value)
        assertEquals(LocalDate.of(2026, 10, 13), model.today.value)
        store.clear()
        repository.close()
    }

    @Test fun highlightMinutesValidatePersistAndKeepOldValueOnFailure() = modelTest { model ->
        runCurrent()
        val draft = model.highlightDraft()
        draft.attach()
        draft.change("25", immediate = true)
        runCurrent()
        assertEquals(25, storage.settings.highlightMinutes)
        storage.failSave = true
        draft.change("30", immediate = true)
        runCurrent()
        assertEquals("磁盘写入失败", draft.state.value.error)
        assertEquals(25, repository.state.value.settings.highlightMinutes)
        storage.failSave = false
        draft.change("0", immediate = true)
        runCurrent()
        assertNull(draft.state.value.error)
        assertEquals(0, storage.settings.highlightMinutes)
        draft.change("61", immediate = true)
        runCurrent()
        assertEquals("请输入 0～60 的整数分钟", draft.state.value.error)
        assertEquals(0, repository.state.value.settings.highlightMinutes)
    }

    @Test fun backgroundFlushesAllValidDraftsAndDoesNotPublishInvalidSettings() = modelTest { model ->
        runCurrent()
        val snapshot = repository.state.value.snapshot!!
        val date = model.termStartDraft(snapshot.scopeKey, "2026-10-12")
        val theme = model.themeDraft()
        val periods = model.periodsDraft(10)
        model.working.value = true
        date.change("2026-10-19")
        theme.change("18304A")
        model.highlightDraft().change("25")
        periods.change(DEFAULT_PERIODS.toMutableList().apply { set(0, PeriodTime("08:05", "08:45")) })
        model.onBackground()
        runCurrent()
        assertEquals("2026-10-19", storage.settings.termStarts[snapshot.scopeKey])
        assertEquals(0xFF18304AL, storage.settings.customPrimaryColor)
        assertFalse(storage.settings.useDynamicColor)
        assertEquals(25, storage.settings.highlightMinutes)
        assertEquals("08:05", storage.settings.periods.first().start)
        val reopened = TimetableRepository(storage, object : SchoolGateway {
            override suspend fun fetch(account: AccountSettings) = error("离线重建不应请求网络")
        }, dispatcher)
        runCurrent()
        assertEquals(storage.settings, reopened.state.value.settings)
        reopened.close()
        date.change("2026-10-20")
        theme.change("invalid")
        periods.change(DEFAULT_PERIODS.toMutableList().apply { set(0, PeriodTime("09:00", "08:00")) })
        model.flushAutoSaves()
        advanceTimeBy(400)
        runCurrent()
        assertEquals("2026-10-19", storage.settings.termStarts[snapshot.scopeKey])
        assertEquals(0xFF18304AL, storage.settings.customPrimaryColor)
        assertEquals("08:05", storage.settings.periods.first().start)
        assertNotNull(date.state.value.error)
        assertNotNull(theme.state.value.error)
        assertNotNull(periods.state.value.error)
    }

    @Test fun restoredDateCancelsPendingEditAndDoesNotReappearOnReentry() = modelTest { model ->
        runCurrent()
        val snapshot = repository.state.value.snapshot!!
        val date = model.termStartDraft(snapshot.scopeKey, "2026-10-12")
        date.change("2026-10-19")
        model.resetTermStart()
        runCurrent()
        advanceTimeBy(400)
        runCurrent()
        assertFalse(storage.settings.termStarts.containsKey(snapshot.scopeKey))
        assertEquals(estimateTermStart(snapshot.term).toString(), date.state.value.value)
    }

    private class MutableClock(var value: Instant) : Clock() {
        override fun getZone(): ZoneId = SCHOOL_ZONE
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(value, zone)
        override fun instant(): Instant = value
    }

    private class MemoryStorage : TimetableStorage {
        var snapshot = testSnapshot()
        var settings = AppSettings()
        var failSave = false
        var saves = 0
        override fun loadSnapshot() = snapshot
        override fun saveSnapshot(snapshot: TimetableSnapshot) { this.snapshot = snapshot }
        override fun loadSettings() = settings
        override fun saveSettings(settings: AppSettings) {
            if (failSave) throw IOException("磁盘写入失败")
            saves++; this.settings = settings
        }
        override fun loadAccount(): AccountSettings? = null
        override fun saveAccount(account: AccountSettings?) = Unit
    }
}
