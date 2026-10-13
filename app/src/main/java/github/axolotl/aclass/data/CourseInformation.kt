package github.axolotl.aclass.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Only expose understood, useful primitive fields; raw codes remain in the cache. */
fun CourseRecord.information(key: String): String? = (raw[key] as? JsonPrimitive)
    ?.takeUnless { it is JsonNull }?.content?.trim()
    ?.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }

fun CourseRecord.additionalInformation(): List<Pair<String, String>> = listOf(
    "jxhjmc" to "教学环节", "sknrjj" to "授课内容简介", "jxbmc" to "教学班",
    "kcxz" to "课程性质", "kclb" to "课程类别", "kkbm" to "开课单位", "bz" to "备注",
).mapNotNull { (key, label) ->
    information(key)?.takeUnless { key in setOf("kcxz", "kclb", "kkbm") && it.none { c -> c in '\u4e00'..'\u9fff' } }
        ?.let { label to it }
}
