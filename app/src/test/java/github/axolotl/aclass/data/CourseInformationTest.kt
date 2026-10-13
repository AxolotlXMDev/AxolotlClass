package github.axolotl.aclass.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CourseInformationTest {
    @Test fun preservesSchoolHoursAndOnlyExposesUnderstoodMetadata() {
        val record = TimetableParser.fromRaw(buildJsonObject {
            put("kcmc", "测试课程"); put("xq", 1); put("jcdm", "01020304"); put("zc", "1-10")
            put("zxs", 1); put("xs", 4); put("xf", "2.5"); put("jxhjmc", "实践教学")
            put("sknrjj", "章节简介"); put("jxbmc", "教学班A"); put("jxcddm", "技术代码")
            put("kcxz", "01"); put("bz", JsonNull)
        })
        assertEquals("1", record.information("zxs"))
        assertEquals("4", record.information("xs"))
        assertEquals("2.5", record.information("xf"))
        assertEquals(listOf("教学环节" to "实践教学", "授课内容简介" to "章节简介", "教学班" to "教学班A"), record.additionalInformation())
        assertNull(record.information("bz"))
        assertNull(record.information("khfs"))
        val restored = StorageCodec.decodeSnapshot(StorageCodec.encodeSnapshot(TimetableSnapshot("test", "202601", 0, listOf(record))))
        assertEquals(record.raw, restored.courses.single().raw)
    }

    @Test fun nullBlankAndStructuredValuesAreOmitted() {
        val record = testSnapshot().courses.first().copy(raw = buildJsonObject {
            put("zxs", JsonNull); put("xs", " "); put("xf", "null"); putJsonObject("bz") { put("value", "嵌套") }
        })
        listOf("zxs", "xs", "xf", "bz", "missing").forEach { assertNull(record.information(it)) }
        assertTrue(record.additionalInformation().isEmpty())
    }

    @Test fun extraPresetsDoNotChangeExistingDefaultPalette() {
        assertEquals(24, COURSE_COLOR_PRESETS.size)
        assertEquals(12, COURSE_PALETTE.size)
        val snapshot = testSnapshot()
        val course = snapshot.courses.single()
        assertEquals(CourseAppearance(COURSE_PALETTE[Math.floorMod(course.courseKey.hashCode(), 12)]), AppSettings().appearance(snapshot, course))
    }
}
