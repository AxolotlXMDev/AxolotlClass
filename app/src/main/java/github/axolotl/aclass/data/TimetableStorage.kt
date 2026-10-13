package github.axolotl.aclass.data

import android.util.AtomicFile
import kotlinx.serialization.json.*
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

interface TimetableStorage {
    fun loadSnapshot(): TimetableSnapshot?
    fun saveSnapshot(snapshot: TimetableSnapshot)
    fun loadSettings(): AppSettings
    fun saveSettings(settings: AppSettings)
    fun loadAccount(): AccountSettings?
    fun saveAccount(account: AccountSettings?)
}

/** Versioned, explicit codecs keep persistence independent of reflection and R8. */
object StorageCodec {
    fun encodeSnapshot(snapshot: TimetableSnapshot): String = buildJsonObject {
        put("version", 1)
        put("studentId", snapshot.studentId)
        put("term", snapshot.term)
        put("updatedAt", snapshot.updatedAt)
        putJsonArray("courses") { snapshot.courses.forEach { add(it.raw) } }
    }.toString()

    fun decodeSnapshot(text: String): TimetableSnapshot {
        val root = TimetableParser.json.parseToJsonElement(text).jsonObject
        require(root["version"]?.jsonPrimitive?.int == 1) { "不支持的缓存版本" }
        val term = root.text("term")
        require(validTerm(term))
        val courses = root.getValue("courses").jsonArray.map { TimetableParser.fromRaw(it.jsonObject) }.distinctBy { it.id }
        return TimetableSnapshot(root.text("studentId"), term, root.getValue("updatedAt").jsonPrimitive.long, courses)
    }

    fun encodeSettings(settings: AppSettings): String = buildJsonObject {
        put("version", 1)
        putJsonObject("termStarts") { settings.termStarts.forEach { (key, value) -> put(key, value) } }
        putJsonObject("appearances") {
            settings.appearances.forEach { (key, value) -> putJsonObject(key) { put("color", value.color); put("emoji", value.emoji) } }
        }
        putJsonArray("periods") {
            settings.periods.forEach { add(buildJsonObject { put("start", it.start); put("end", it.end) }) }
        }
        settings.backgroundPath?.let { put("backgroundPath", it) }
        put("backgroundTransparency", settings.backgroundTransparency)
        put("mainUiOpacity", settings.mainUiOpacity)
        put("highlightMinutes", settings.highlightMinutes)
        put("useDynamicColor", settings.useDynamicColor)
        put("customPrimaryColor", settings.customPrimaryColor)
        putJsonArray("hiddenWeekendCourses") { settings.hiddenWeekendCourses.sorted().forEach { add(it) } }
    }.toString()

    fun decodeSettings(text: String): AppSettings {
        val root = TimetableParser.json.parseToJsonElement(text).jsonObject
        require(root["version"]?.jsonPrimitive?.int == 1)
        val dates = root["termStarts"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content }.filterValues {
            runCatching { LocalDate.parse(it).dayOfWeek == DayOfWeek.MONDAY }.getOrDefault(false)
        }
        val appearances = root["appearances"]?.jsonObject.orEmpty().mapValues {
            val value = it.value.jsonObject
            CourseAppearance(value.getValue("color").jsonPrimitive.long, value.text("emoji"))
        }
        val periods = root["periods"]?.jsonArray?.map { PeriodTime(it.jsonObject.text("start"), it.jsonObject.text("end")) }
            ?.takeIf { it.size in 9..99 && it.all(::validPeriodTime) }?.let {
                if (it.size == 9) it + DEFAULT_PERIODS[9] else it
            } ?: DEFAULT_PERIODS
        return AppSettings(
            termStarts = dates, appearances = appearances, periods = periods,
            backgroundPath = root.text("backgroundPath").ifBlank { null },
            backgroundTransparency = root["backgroundTransparency"]?.jsonPrimitive?.float?.coerceIn(0f, 1f) ?: 0.25f,
            useDynamicColor = root["useDynamicColor"]?.jsonPrimitive?.booleanOrNull ?: true,
            customPrimaryColor = root["customPrimaryColor"]?.jsonPrimitive?.longOrNull ?: 0xFF4E709BL,
            hiddenWeekendCourses = root["hiddenWeekendCourses"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
            mainUiOpacity = (root["mainUiOpacity"] as? JsonPrimitive)?.floatOrNull
                ?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: DEFAULT_MAIN_UI_OPACITY,
            highlightMinutes = (root["highlightMinutes"] as? JsonPrimitive)?.intOrNull
                ?.takeIf { it in 0..60 } ?: DEFAULT_HIGHLIGHT_MINUTES,
        )
    }

    fun encodeAccount(account: AccountSettings): String = buildJsonObject {
        put("version", 1); put("studentId", account.studentId); put("password", account.password); put("term", account.term)
    }.toString()

    fun decodeAccount(text: String): AccountSettings {
        val root = TimetableParser.json.parseToJsonElement(text).jsonObject
        require(root["version"]?.jsonPrimitive?.int == 1)
        val account = AccountSettings(root.text("studentId"), root.text("password"), root.text("term"))
        require(account.studentId.isNotBlank() && account.password.isNotEmpty() && validTerm(account.term))
        return account
    }
}

fun validPeriodTime(period: PeriodTime): Boolean {
    if (period.start.isBlank() && period.end.isBlank()) return true
    if (!Regex("\\d{2}:\\d{2}").matches(period.start) || !Regex("\\d{2}:\\d{2}").matches(period.end)) return false
    return runCatching { LocalTime.parse(period.start).isBefore(LocalTime.parse(period.end)) }.getOrDefault(false)
}

class FileTimetableStorage(directory: File) : TimetableStorage {
    private val snapshotFile = AtomicFile(File(directory, "timetable.json"))
    private val settingsFile = AtomicFile(File(directory, "settings.json"))
    // noBackupFilesDir is passed separately by the application for account storage.
    private var accountFile = AtomicFile(File(directory, "account.json"))

    constructor(directory: File, accountDirectory: File) : this(directory) {
        accountFile = AtomicFile(File(accountDirectory, "account.json"))
    }

    private fun read(file: AtomicFile): String? = runCatching { file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()
    private fun write(file: AtomicFile, text: String) {
        val stream = file.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    override fun loadSnapshot(): TimetableSnapshot? = read(snapshotFile)?.let { runCatching { StorageCodec.decodeSnapshot(it) }.getOrNull() }
    override fun saveSnapshot(snapshot: TimetableSnapshot) = write(snapshotFile, StorageCodec.encodeSnapshot(snapshot))
    override fun loadSettings(): AppSettings = read(settingsFile)?.let { runCatching { StorageCodec.decodeSettings(it) }.getOrNull() } ?: AppSettings()
    override fun saveSettings(settings: AppSettings) = write(settingsFile, StorageCodec.encodeSettings(settings))
    override fun loadAccount(): AccountSettings? = read(accountFile)?.let { runCatching { StorageCodec.decodeAccount(it) }.getOrNull() }
    override fun saveAccount(account: AccountSettings?) { if (account == null) accountFile.delete() else write(accountFile, StorageCodec.encodeAccount(account)) }
}
