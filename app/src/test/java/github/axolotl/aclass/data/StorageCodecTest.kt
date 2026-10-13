package github.axolotl.aclass.data

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

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
            useDynamicColor = false, customPrimaryColor = 0xFF427E89L, hiddenWeekendCourses = setOf("student:202601:code:test"),
            mainUiOpacity = 0.45f)
        assertEquals(settings, StorageCodec.decodeSettings(StorageCodec.encodeSettings(settings)))
    }

    @Test fun oldSettingsDefaultOpacityWithoutLosingOtherFields() {
        val original = AppSettings(backgroundTransparency = 0.6f, useDynamicColor = false,
            appearances = mapOf("course" to CourseAppearance(0xFFAABBCCL, "📚")))
        val legacy = JsonObject(TimetableParser.json.parseToJsonElement(StorageCodec.encodeSettings(original)).jsonObject - "mainUiOpacity")
        assertEquals(original, StorageCodec.decodeSettings(legacy.toString()))
        assertEquals(0.82f, StorageCodec.decodeSettings("{\"version\":1}").mainUiOpacity)
    }

    @Test fun opacityBoundsAndInvalidValuesDoNotDiscardSettings() {
        listOf("-0.2" to 0f, "1.2" to 1f, "0" to 0f, "1" to 1f,
            "null" to 0.82f, "\"NaN\"" to 0.82f, "\"Infinity\"" to 0.82f,
            "\"invalid\"" to 0.82f, "{}" to 0.82f).forEach { (value, expected) ->
            val restored = StorageCodec.decodeSettings("{\"version\":1,\"mainUiOpacity\":$value,\"backgroundTransparency\":0.6}")
            assertEquals(expected, restored.mainUiOpacity)
            assertEquals(0.6f, restored.backgroundTransparency)
        }
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
