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

    @Test fun savePublishesOnlyAfterPersistenceAndRejectsDuplicateSubmission() = modelTest { model ->
        runCurrent()
        val snapshot = repository.state.value.snapshot!!
        val appearance = CourseAppearance(0xFF18304AL, "🧑‍💻")
        var first: Result<Unit>? = null
        var duplicate: Result<Unit>? = null
        model.saveAppearance(snapshot.scopeKey, snapshot.courses.first(), appearance) { first = it }
        model.saveAppearance(snapshot.scopeKey, snapshot.courses.first(), CourseAppearance(0xFFAABBCCL)) { duplicate = it }
        assertTrue(model.working.value)
        assertNull(first)
        assertTrue(duplicate!!.isFailure)
        assertTrue(repository.state.value.settings.appearances.isEmpty())
        runCurrent()
        assertTrue(first!!.isSuccess)
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
        var result: Result<Unit>? = null
        model.saveAppearance(snapshot.scopeKey, snapshot.courses.first(), appearance) { result = it }
        runCurrent()
        assertEquals("磁盘写入失败", result!!.exceptionOrNull()!!.message)
        assertTrue(repository.state.value.settings.appearances.isEmpty())
        assertTrue(storage.settings.appearances.isEmpty())
        assertFalse(model.working.value)
        storage.failSave = false
        model.saveAppearance(snapshot.scopeKey, snapshot.courses.first(), appearance) { result = it }
        runCurrent()
        assertTrue(result!!.isSuccess)
        assertEquals(appearance, repository.state.value.settings.appearances.values.single())
    }

    @Test fun saveUsesOpeningScopeEvenAfterAccountAndTermChange() = modelTest { model ->
        runCurrent()
        val opened = repository.state.value.snapshot!!
        assertEquals(RefreshResult.Success, repository.refresh(AccountSettings("other", "password", "202602")))
        val appearance = CourseAppearance(0xFF18304AL, "🌐")
        var result: Result<Unit>? = null
        model.saveAppearance(opened.scopeKey, opened.courses.first(), appearance) { result = it }
        runCurrent()
        assertTrue(result!!.isSuccess)
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
        val saved = async { model.events.first() }
        model.setHighlightMinutes("25")
        runCurrent()
        assertEquals("高亮时长已保存", saved.await())
        assertEquals(25, storage.settings.highlightMinutes)
        storage.failSave = true
        val failed = async { model.events.first() }
        model.setHighlightMinutes("30")
        runCurrent()
        assertEquals("磁盘写入失败", failed.await())
        assertEquals(25, repository.state.value.settings.highlightMinutes)
        storage.failSave = false
        val retried = async { model.events.first() }
        model.setHighlightMinutes("0")
        runCurrent()
        assertEquals("高亮时长已保存", retried.await())
        assertEquals(0, storage.settings.highlightMinutes)
        val invalid = async { model.events.first() }
        model.setHighlightMinutes("61")
        runCurrent()
        assertEquals("请输入 0～60 的整数分钟", invalid.await())
        assertEquals(0, repository.state.value.settings.highlightMinutes)
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
