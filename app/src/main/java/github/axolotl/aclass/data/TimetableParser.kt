package github.axolotl.aclass.data

import kotlinx.serialization.json.*

object TimetableParser {
    val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<CourseRecord> {
        require(text.isNotBlank()) { "教务系统返回了空响应" }
        val root = try { json.parseToJsonElement(text) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("课表响应不是有效 JSON，可能需要重新登录")
        }
        val rows = extractRows(root)
        return rows.mapIndexed { index, element ->
            val raw = element as? JsonObject ?: error("第${index + 1}条课表记录格式异常")
            fromRaw(raw)
        }.distinctBy { it.id }.sortedWith(compareBy({ it.day }, { it.periods.first() }, { it.name }))
    }

    private fun extractRows(root: JsonElement): JsonArray = when (root) {
        is JsonArray -> when {
            root.isEmpty() -> root
            root.first() is JsonArray -> root.first().jsonArray
            root.first() is JsonObject && listOf("rows", "data", "items").any { root.first().jsonObject[it] is JsonArray } ->
                extractRows(root.first())
            root.all { it is JsonObject } -> root
            else -> error("无法识别课表数组格式")
        }
        is JsonObject -> listOf("rows", "data", "items").firstNotNullOfOrNull { root[it] as? JsonArray }
            ?.let(::extractRows) ?: error("教务系统未返回课程列表")
        else -> error("无法识别课表响应")
    }

    fun fromRaw(raw: JsonObject): CourseRecord {
        val name = raw.text("kcmc", "courseName", "course_name")
        val teacher = raw.text("teaxms", "teacher", "courseTeacher", "course_teacher")
        val location = raw.text("jxcdmc", "location", "courseLocation", "course_location")
        require(name.isNotBlank()) { "课程名称缺失" }
        val day = parseDay(raw.text("xq", "courseWeekDay", "course_week_day"))
        val periods = parsePeriods(raw.text("jcdm", "courseClass", "course_class"))
        val weekValue = raw.first("zc", "courseWeek", "course_week") ?: error("课程周次缺失")
        val weeks = if (weekValue is JsonArray) weekValue.flatMap { parseWeeks(it.jsonPrimitive.content) }.toSet()
            else parseWeeks(weekValue.jsonPrimitive.content)
        val code = raw.text("kcdm", "kcbh")
        val key = if (code.isNotBlank()) "code:$code" else "name:${stableHash("$name|$teacher")}"
        val id = stableHash("$key|$teacher|$location|$day|${periods.joinToString(",")}|${weeks.sorted().joinToString(",")}")
        return CourseRecord(id, key, name, teacher, location, day, periods, weeks, raw)
    }

    fun parseDay(text: String): Int {
        text.trim().toIntOrNull()?.let { require(it in 1..7) { "星期超出范围" }; return it }
        val name = text.trim().removePrefix("星期").removePrefix("周")
        val day = listOf("一", "二", "三", "四", "五", "六", "日").indexOf(if (name == "天") "日" else name) + 1
        require(day in 1..7) { "无法识别星期：$text" }
        return day
    }

    fun parsePeriods(text: String): List<Int> {
        val value = text.trim().removePrefix("第").removeSuffix("节")
        val periods = if (value.matches(Regex("\\d+"))) {
            val digits = if (value.length % 2 == 1) "0$value" else value
            digits.chunked(2).map(String::toInt)
        } else {
            val normalized = value.replace(Regex("[，、;；]"), ",").replace(Regex("[~～至—–]"), "-")
            normalized.split(',').flatMap { part ->
                val match = Regex("\\s*(\\d+)\\s*(?:-\\s*(\\d+))?\\s*").matchEntire(part)
                    ?: error("无法识别节次：$text")
                val first = match.groupValues[1].toInt()
                val last = match.groupValues[2].toIntOrNull() ?: first
                require(first in 1..99 && last in first..99) { "节次范围不正确" }
                (first..last).toList()
            }
        }
        require(periods.isNotEmpty() && periods.all { it in 1..99 }) { "节次超出范围" }
        return periods.distinct().sorted()
    }

    fun parseWeeks(text: String): Set<Int> {
        val normalized = text.replace(Regex("[，、;；]"), ",").replace(Regex("[~～至—–]"), "-")
        val result = normalized.split(',').flatMap { part ->
            val odd = part.contains("单")
            val even = part.contains("双")
            require(!(odd && even)) { "单双周格式不明确" }
            val cleaned = part.replace(Regex("[第周单双()（）\\s]"), "")
            val match = Regex("(\\d+)(?:-(\\d+))?").matchEntire(cleaned) ?: error("无法识别周次：$text")
            val start = match.groupValues[1].toInt()
            val end = match.groupValues[2].toIntOrNull() ?: start
            require(start in 1..99 && end in start..99) { "周次范围不正确" }
            (start..end).filter { (!odd || it % 2 == 1) && (!even || it % 2 == 0) }
        }.toSet()
        require(result.isNotEmpty()) { "课程没有有效周次" }
        return result
    }
}

fun JsonObject.first(vararg keys: String): JsonElement? = keys.firstNotNullOfOrNull { get(it)?.takeUnless { value -> value is JsonNull } }
fun JsonObject.text(vararg keys: String): String = first(*keys)?.let { (it as? JsonPrimitive)?.content ?: it.toString() }.orEmpty()
