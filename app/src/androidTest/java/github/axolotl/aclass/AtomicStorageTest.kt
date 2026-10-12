package github.axolotl.aclass

import androidx.test.platform.app.InstrumentationRegistry
import github.axolotl.aclass.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AtomicStorageTest {
    @Test fun diskPersistenceAndCorruptCacheRecovery() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "storage-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val storage = FileTimetableStorage(directory)
            assertNull(storage.loadSnapshot())
            val snapshot = TimetableSnapshot("test", "202601", 4567, emptyList())
            storage.saveSnapshot(snapshot)
            storage.saveSettings(AppSettings(backgroundTransparency = 0.7f))
            storage.saveAccount(AccountSettings("test", "password", "202601"))
            val reopened = FileTimetableStorage(directory)
            assertEquals(snapshot, reopened.loadSnapshot())
            assertEquals(0.7f, reopened.loadSettings().backgroundTransparency)
            assertEquals("test", reopened.loadAccount()!!.studentId)
            File(directory, "timetable.json").writeText("bad cache")
            assertNull(reopened.loadSnapshot())
            assertEquals(0.7f, reopened.loadSettings().backgroundTransparency)
            reopened.saveAccount(null)
            assertNull(reopened.loadAccount())
        } finally { directory.deleteRecursively() }
    }
}
