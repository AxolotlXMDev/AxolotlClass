package github.axolotl.aclass.data

import org.junit.Assert.*
import org.junit.Test

class StorageCodecTest {
    @Test fun snapshotRoundTripKeepsSuccessfulTimeAndRawDetails() {
        val original = testSnapshot("1-16单周")
        assertEquals(original, StorageCodec.decodeSnapshot(StorageCodec.encodeSnapshot(original)))
    }

    @Test fun emptySnapshotIsStillLoadedData() {
        val snapshot = testSnapshot().copy(courses = emptyList())
        assertEquals(snapshot, StorageCodec.decodeSnapshot(StorageCodec.encodeSnapshot(snapshot)))
    }

    @Test fun settingsPersistIndependentlyOfCourses() {
        val settings = AppSettings(termStarts = mapOf("student:202601" to "2026-08-31"),
            appearances = mapOf("key" to CourseAppearance(0xFFAEDCCCL, "🧑‍💻")),
            periods = DEFAULT_PERIODS + PeriodTime("20:35", "21:20"), backgroundPath = "/private/background.png", backgroundTransparency = 0.6f,
            useDynamicColor = false, customPrimaryColor = 0xFF427E89L, hiddenWeekendCourses = setOf("student:202601:code:test"))
        assertEquals(settings, StorageCodec.decodeSettings(StorageCodec.encodeSettings(settings)))
    }

    @Test fun badVersionAndCorruptCacheFailWithoutPretendingToBeEmpty() {
        listOf("", "{}", "{\"version\":99}", "broken").forEach {
            assertTrue(runCatching { StorageCodec.decodeSnapshot(it) }.isFailure)
        }
    }

    @Test fun accountRoundTripAndTimesValidate() {
        val account = AccountSettings("student", "密码&+", "202601")
        assertEquals(account, StorageCodec.decodeAccount(StorageCodec.encodeAccount(account)))
        assertTrue(DEFAULT_PERIODS.all(::validPeriodTime))
        assertTrue(validPeriodTime(PeriodTime()))
        assertFalse(validPeriodTime(PeriodTime("08:45", "08:00")))
        assertFalse(validPeriodTime(PeriodTime("25:00", "26:00")))
        assertFalse(validPeriodTime(PeriodTime("08:00", "")))
    }

    @Test fun oldNinePeriodSettingsGainTheTenthWithoutLosingCustomTimes() {
        val old = AppSettings(periods = DEFAULT_PERIODS.take(9).toMutableList().apply { set(0, PeriodTime("08:05", "08:50")) })
        val restored = StorageCodec.decodeSettings(StorageCodec.encodeSettings(old))
        assertEquals(10, restored.periods.size)
        assertEquals("08:05", restored.periods.first().start)
        assertEquals(PeriodTime("20:35", "21:20"), restored.periods.last())
        assertTrue(restored.useDynamicColor)
    }
}
