package github.axolotl.aclass

import androidx.lifecycle.ViewModelStore
import github.axolotl.aclass.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TimetableViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var repository: TimetableRepository
    private val storage = MemoryStorage()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); if (::repository.isInitialized) repository.close(); Dispatchers.resetMain() }

    private fun createModel(): TimetableViewModel {
        repository = TimetableRepository(storage, object : SchoolGateway {
            override suspend fun fetch(account: AccountSettings) = testSnapshot().courses
        }, dispatcher)
        return TimetableViewModel(repository).also { store.put("model", it) }
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
