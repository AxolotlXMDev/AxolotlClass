package github.axolotl.aclass.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant

private class MemoryStorage : TimetableStorage {
    var snapshot: TimetableSnapshot? = testSnapshot()
    var settings = AppSettings(appearances = mapOf("student:202601:code:计算机网络" to CourseAppearance(0xFFAABBCC, "🌐")))
    var account: AccountSettings? = AccountSettings("student", "password", "202601")
    var failSave = false
    override fun loadSnapshot() = snapshot
    override fun saveSnapshot(snapshot: TimetableSnapshot) { if (failSave) throw IOException("磁盘写入失败"); this.snapshot = snapshot }
    override fun loadSettings() = settings
    override fun saveSettings(settings: AppSettings) { this.settings = settings }
    override fun loadAccount() = account
    override fun saveAccount(account: AccountSettings?) { this.account = account }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TimetableRepositoryTest {
    private val clock = Clock.fixed(Instant.ofEpochMilli(9000), SCHOOL_ZONE)

    @Test fun failedAutomaticRefreshKeepsCacheStylesAndTime() = runTest {
        val storage = MemoryStorage()
        val old = storage.snapshot
        val repository = TimetableRepository(storage, object : SchoolGateway {
            override suspend fun fetch(account: AccountSettings): List<CourseRecord> = throw IOException("offline")
        }, StandardTestDispatcher(testScheduler), clock)
        try {
            repository.refreshAutomatically()
            advanceUntilIdle()
            assertEquals(old, repository.state.value.snapshot)
            assertEquals(storage.settings, repository.state.value.settings)
            assertFalse(repository.state.value.refreshing)
        } finally { repository.close() }
    }

    @Test fun concurrentRefreshesJoinOneRequestAndRetainAppearance() = runTest {
        val storage = MemoryStorage()
        var calls = 0
        val gateway = object : SchoolGateway {
            override suspend fun fetch(account: AccountSettings): List<CourseRecord> { calls++; delay(100); return testSnapshot("2").courses }
        }
        val repository = TimetableRepository(storage, gateway, StandardTestDispatcher(testScheduler), clock)
        try {
            val automatic = async { repository.refresh() }
            val manual = async { repository.refresh() }
            advanceUntilIdle()
            assertEquals(RefreshResult.Success, automatic.await())
            assertEquals(RefreshResult.Success, manual.await())
            assertEquals(1, calls)
            assertEquals(9000L, repository.state.value.snapshot!!.updatedAt)
            assertEquals("🌐", repository.state.value.settings.appearances.values.single().emoji)
        } finally { repository.close() }
    }

    @Test fun persistenceFailureDoesNotPublishNewDataOrAccount() = runTest {
        val storage = MemoryStorage().apply { failSave = true }
        val old = storage.snapshot
        val repository = TimetableRepository(storage, successGateway(), StandardTestDispatcher(testScheduler), clock)
        try {
            val result = repository.refresh(AccountSettings("new", "password", "202602"))
            assertTrue(result is RefreshResult.Failure)
            assertEquals(old, repository.state.value.snapshot)
            assertEquals("student", repository.state.value.account!!.studentId)
            assertEquals("student", storage.account!!.studentId)
        } finally { repository.close() }
    }

    @Test fun successfulEmptyTableClearsPreviousCourses() = runTest {
        val repository = TimetableRepository(MemoryStorage(), successGateway(emptyList()), StandardTestDispatcher(testScheduler), clock)
        try {
            assertEquals(RefreshResult.Success, repository.refresh())
            assertNotNull(repository.state.value.snapshot)
            assertTrue(repository.state.value.snapshot!!.courses.isEmpty())
        } finally { repository.close() }
    }

    @Test fun noCredentialsSkipsNetworkAndKeepsOfflineTable() = runTest {
        val storage = MemoryStorage().apply { account = null }
        var calls = 0
        val repository = TimetableRepository(storage, object : SchoolGateway {
            override suspend fun fetch(account: AccountSettings): List<CourseRecord> { calls++; return emptyList() }
        }, StandardTestDispatcher(testScheduler), clock)
        try {
            assertEquals(RefreshResult.NoAccount, repository.refresh())
            assertEquals(0, calls)
            assertEquals(storage.snapshot, repository.state.value.snapshot)
        } finally { repository.close() }
    }

    @Test fun clearCredentialsCancelsRefreshWithoutDiscardingSnapshot() = runTest {
        val storage = MemoryStorage()
        val repository = TimetableRepository(storage, object : SchoolGateway {
            override suspend fun fetch(account: AccountSettings): List<CourseRecord> { delay(10000); return emptyList() }
        }, StandardTestDispatcher(testScheduler), clock)
        try {
            repository.refreshAutomatically()
            runCurrent()
            repository.clearAccount()
            advanceUntilIdle()
            assertNull(storage.account)
            assertNull(repository.state.value.account)
            assertEquals(storage.snapshot, repository.state.value.snapshot)
            assertFalse(repository.state.value.refreshing)
        } finally { repository.close() }
    }

    @Test fun restartReadsSuccessfulSnapshotAndSettings() = runTest {
        val storage = MemoryStorage()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = TimetableRepository(storage, successGateway(), dispatcher, clock)
        first.refresh()
        first.updateSettings { it.copy(backgroundTransparency = 0.8f, mainUiOpacity = 0.45f) }
        first.close()
        val restarted = TimetableRepository(storage, successGateway(), dispatcher, clock)
        try {
            runCurrent()
            assertEquals(9000L, restarted.state.value.snapshot!!.updatedAt)
            assertEquals(0.8f, restarted.state.value.settings.backgroundTransparency)
            assertEquals(0.45f, restarted.state.value.settings.mainUiOpacity)
            assertFalse(restarted.state.value.loading)
        } finally { restarted.close() }
    }

    private fun successGateway(courses: List<CourseRecord> = testSnapshot("2").courses) = object : SchoolGateway {
        override suspend fun fetch(account: AccountSettings) = courses
    }
}
