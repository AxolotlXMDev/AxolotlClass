package github.axolotl.aclass

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Explicitly invoked benchmark fixture; normal device test runs do not change app data. */
class StartupFixtureTest {
    @Test fun prepareOrRestore() {
        val stage = InstrumentationRegistry.getArguments().getString("fixtureStage") ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "timetable.json")
        val marker = File(context.cacheDir, "startup-fixture-created")
        if (stage == "seed" && !file.exists()) {
            val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
            val year = if (today.monthValue >= 9) today.year else today.year - 1
            val term = "$year${if (today.monthValue in 2..8) "02" else "01"}"
            val names = listOf("计算机网络", "数据结构与算法", "大学英语")
            val periods = listOf("0102", "0304", "0506")
            val courses = (1..20).flatMap { week -> (1..5).flatMap { day -> names.indices.map { index ->
                """{"kcmc":"${names[index]}","kcdm":"test$index","teaxms":"演示教师","jxcdmc":"二教302","xq":"$day","jcdm":"${periods[index]}","zc":"$week"}"""
            } } }.joinToString(",")
            file.writeText("""{"version":1,"studentId":"startup-fixture","term":"$term","updatedAt":${System.currentTimeMillis()},"courses":[$courses]}""")
            marker.writeText("created")
        } else if (stage == "cleanup" && marker.exists()) {
            // Restore only our fixture; never remove a subsequently downloaded personal timetable.
            if (file.exists() && file.readText().contains("\"studentId\":\"startup-fixture\"")) file.delete()
            marker.delete()
        }
    }
}
