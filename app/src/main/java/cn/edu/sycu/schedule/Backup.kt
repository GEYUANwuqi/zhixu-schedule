package cn.edu.sycu.schedule

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.InputStream
import java.time.Instant

internal data class AppBackup(val name: String, val createdAt: String, val schedules: List<ImportedSchedule>, val preferences: JSONObject) {
    val courseCount get() = schedules.sumOf { it.courses.size }
    val makeupCount get() = MakeupCodec.decode(preferences.getJSONObject("display").optString("makeup_lessons", "[]")).size
    val rulesCount get() = RuleCodec.decode(preferences.getJSONObject("display").optString("course_rules", "[]")).size
}

internal suspend fun <T> replaceWithRollback(previous: T, incoming: T, persist: (T) -> Unit, replace: suspend (T) -> Unit, clear: () -> Unit) {
    persist(previous)
    try { replace(incoming) }
    catch (failure: Exception) {
        try { replace(previous); clear() }
        catch (rollbackFailure: Exception) { failure.addSuppressed(rollbackFailure) }
        throw failure
    }
    clear()
}

internal object BackupCodec {
    const val LIMIT = 16 * 1024 * 1024
    val displayBooleans = setOf("room", "teacher", "weeks", "periods", "note", "widget_large", "auto_check_updates")
    val displayInts = setOf("theme_color", "widget_opacity", "past_course_opacity")
    val displayStrings = setOf("active_table", "card_colors", "course_rules", "makeup_lessons")
    val alertBooleans = setOf("ongoing", "reminders", "name", "time", "room", "ongoing_countdown", "ongoing_time", "ongoing_room", "ongoing_teacher")
    fun allowed(group: String, key: String) = when (group) {
        "display" -> key in displayBooleans + displayInts + displayStrings
        "course-alerts" -> key in alertBooleans || key == "minutes" || key.startsWith("muted-names:")
        "widget-days" -> key == "offset"
        "holidays" -> key.isNotBlank() && key.length <= 264
        else -> false
    }
    val groups = listOf("display", "course-alerts", "widget-days", "holidays")
    fun read(input: InputStream): AppBackup {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(output.size() + n <= LIMIT) { "备份不能超过16 MB" }
            output.write(buffer, 0, n)
        }
        return decode(output.toString("UTF-8"))
    }
    fun encode(backup: AppBackup): String {
        val rows = JSONArray()
        backup.schedules.forEach { schedule ->
            val root = JSONObject(JsonTransfer.encode(schedule.table, schedule.courses.map { it.copy(deleted = false) }))
            root.getJSONObject("timetable").put("id", schedule.table.id)
            schedule.courses.forEachIndexed { i, c ->
                root.getJSONArray("courses").getJSONObject(i).put("id", c.id)
                    .put("sourceId", c.sourceId ?: JSONObject.NULL).put("edited", c.edited).put("deleted", c.deleted)
            }
            rows.put(root)
        }
        val text = JSONObject().put("format", "zhixu-app-backup").put("version", 1)
            .put("name", backup.name).put("createdAt", backup.createdAt).put("schedules", rows)
            .put("preferences", backup.preferences).toString(2)
        decode(text)
        return text
    }
    fun decode(text: String): AppBackup {
        try {
            require(text.toByteArray(Charsets.UTF_8).size <= LIMIT)
            val reader = JSONTokener(text.removePrefix("\uFEFF"))
            val root = reader.nextValue() as JSONObject
            require(reader.nextClean() == '\u0000')
            require(root.get("format") == "zhixu-app-backup" && root.get("version") == 1)
            val name = string(root, "name", 120).also { require(it.isNotBlank()) }
            val created = string(root, "createdAt", 64).also { require(Instant.parse(it).atZone(java.time.ZoneOffset.UTC).year in 1900..9999) }
            val rows = root.getJSONArray("schedules")
            require(rows.length() <= 100)
            val tableIds = mutableSetOf<String>()
            val courseIds = mutableSetOf<String>()
            val schedules = (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i)
                val imported = JsonTransfer.decode(row.toString())
                val id = string(row.getJSONObject("timetable"), "id", 256).also { require(it.isNotBlank() && tableIds.add(it)) }
                val courses = imported.courses.mapIndexed { index, c ->
                    val data = row.getJSONArray("courses").getJSONObject(index)
                    val courseId = string(data, "id", 256).also { require(it.isNotBlank() && courseIds.add(it)) }
                    val source = if (data.get("sourceId") == JSONObject.NULL) null else string(data, "sourceId", 512)
                    require(data.get("edited") is Boolean && data.get("deleted") is Boolean)
                    c.copy(id = courseId, timetableId = id, sourceId = source, edited = data.getBoolean("edited"), deleted = data.getBoolean("deleted"))
                }
                ImportedSchedule(imported.table.copy(id = id), courses)
            }
            require(courseIds.size <= 10000)
            val preferences = root.getJSONObject("preferences")
            val present = preferences.keys().asSequence().toSet()
            require(present == groups.toSet() || present == groups.toSet() - "holidays")
            // Older full backups predate rest states. Restoring one must clear local marks.
            if (!preferences.has("holidays")) preferences.put("holidays", JSONObject())
            groups.forEach { group ->
                val values = preferences.getJSONObject(group)
                values.keys().forEach { key ->
                    require(allowed(group, key))
                    val value = values.get(key)
                    when {
                        group == "holidays" -> {
                            require(value is JSONArray && value.length() <= 100000)
                            for (n in 0 until value.length()) {
                                val mark = value.get(n)
                                require(mark is String && mark.length <= 512)
                                if (key.startsWith("lessons:")) {
                                    require(key.removePrefix("lessons:").isNotBlank())
                                    require(mark.substringBeforeLast(':', "").isNotBlank())
                                    requireNotNull(mark.substringAfterLast(':').toLongOrNull())
                                } else java.time.LocalDate.parse(mark)
                            }
                        }
                        group == "display" && key in displayBooleans || group == "course-alerts" && key in alertBooleans -> require(value is Boolean)
                        group == "display" && key in displayInts || key == "minutes" || key == "offset" -> {
                            require(value is Int)
                            when (key) {
                                "minutes" -> require(value in 1..60)
                                "widget_opacity", "past_course_opacity" -> require(value in 0..100)
                            }
                        }
                        key.startsWith("muted-names:") -> {
                            require(group == "course-alerts" && key.length <= 300 && value is JSONArray && value.length() <= 1000)
                            for (n in 0 until value.length()) require(value.get(n) is String && value.getString(n).length <= 200)
                        }
                        else -> {
                            require(value is String)
                            when (key) {
                                "active_table" -> require(value in tableIds)
                                "makeup_lessons" -> MakeupCodec.decode(value)
                                "course_rules" -> RuleTransfer.decode(JSONObject().put("format", "zhixu-sync-rules").put("version", 1).put("rules", JSONArray(value)).toString())
                                "card_colors" -> {
                                    val colors = JSONObject(value)
                                    require(colors.length() <= 10000)
                                    colors.keys().forEach { require(it.length <= 200 && colors.get(it) is Int) }
                                }
                            }
                        }
                    }
                }
            }
            return AppBackup(name, created, schedules, preferences)
        } catch (e: Exception) {
            throw IllegalArgumentException("备份无效、损坏或版本不受支持，现有数据未修改", e)
        }
    }
    private fun string(value: JSONObject, key: String, limit: Int): String = (value.get(key) as String).also { require(it.length <= limit) }
}
