package cn.edu.sycu.schedule

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class BackupTest {
    @Test fun restStatesRoundTripAndOlderBackupsDefaultToEmpty() {
        val original = sample()
        val marks = JSONObject().put("lessons:table", JSONArray(listOf("course:1790000000000", "makeup:id:2026-09-22:1790000000001")))
            .put("table", JSONArray(listOf("2026-09-22")))
        original.preferences.put("holidays", marks)
        val restored = BackupCodec.decode(BackupCodec.encode(original))
        assertEquals(marks.toString(), restored.preferences.getJSONObject("holidays").toString())
        assertEquals(original.schedules, restored.schedules)
        val older = JSONObject(BackupCodec.encode(original))
        older.getJSONObject("preferences").remove("holidays")
        assertEquals(0, BackupCodec.decode(older.toString()).preferences.getJSONObject("holidays").length())
        invalid { it.getJSONObject("preferences").put("holidays", JSONObject().put("lessons:table", JSONArray(listOf("course:invalid")))) }
        invalid { it.getJSONObject("preferences").put("holidays", JSONObject().put("table", JSONArray(listOf("not-a-date")))) }
    }
    private fun sample(): AppBackup {
        val t = Timetable(id = "table", start = "2026-08-24")
        val other = Timetable(id = "other", name = "另一个课表")
        val course = Course(id = "course", timetableId = t.id, name = "数学", sourceId = "remote", edited = true, deleted = true)
        val rules = listOf(CourseRule("课程名", "数学", room = "A320"), CourseRule("教室", "G411", room = "建工4楼"))
        val prefs = JSONObject().put("display", JSONObject().put("active_table", t.id)
            .put("theme_color", -123).put("card_colors", "{\"数学\":-45}").put("room", false)
            .put("widget_opacity", 65).put("past_course_opacity", 60).put("widget_large", true)
            .put("course_rules", RuleCodec.encode(rules)))
            .put("course-alerts", JSONObject().put("minutes", 37).put("reminders", true).put("muted-names:table", JSONArray(listOf("数学"))))
            .put("widget-days", JSONObject().put("offset", -2))
        return AppBackup("我的完整备份", "2026-09-18T01:02:03Z", listOf(ImportedSchedule(t, listOf(course)), ImportedSchedule(other, emptyList())), prefs)
    }
    @Test fun roundTripPreservesIdentityDeletionRulesAndAllSettings() {
        val original = sample()
        val result = BackupCodec.read(ByteArrayInputStream(BackupCodec.encode(original).toByteArray()))
        assertEquals(original.schedules, result.schedules)
        assertEquals(original.name, result.name)
        assertEquals(original.createdAt, result.createdAt)
        assertEquals(2, result.rulesCount)
        assertEquals(RuleCodec.decode(original.preferences.getJSONObject("display").getString("course_rules")), RuleCodec.decode(result.preferences.getJSONObject("display").getString("course_rules")))
        assertEquals(37, result.preferences.getJSONObject("course-alerts").getInt("minutes"))
        assertEquals(-2, result.preferences.getJSONObject("widget-days").getInt("offset"))
        assertEquals(65, result.preferences.getJSONObject("display").getInt("widget_opacity"))
        assertEquals(-45, JSONObject(result.preferences.getJSONObject("display").getString("card_colors")).getInt("数学"))
    }
    private fun invalid(edit: (JSONObject) -> Unit) {
        val root = JSONObject(BackupCodec.encode(sample()))
        edit(root)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(root.toString()) }
    }
    @Test fun unsupportedCorruptAndInvalidMetadataAreRejected() {
        invalid { it.put("version", 2) }
        invalid { it.put("format", "zhixu-schedule") }
        invalid { it.put("name", " ") }
        invalid { it.put("createdAt", "not-a-date") }
        invalid { it.put("createdAt", "+100000-01-01T00:00:00Z") }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(BackupCodec.encode(sample()) + "garbage") }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.read(ByteArrayInputStream(ByteArray(BackupCodec.LIMIT + 1))) }
    }
    @Test fun duplicateIdentityAndBadCoursesAreRejected() {
        invalid { val a = it.getJSONArray("schedules"); a.put(a.getJSONObject(0)) }
        invalid { val a = it.getJSONArray("schedules").getJSONObject(0).getJSONArray("courses"); a.put(a.getJSONObject(0)) }
        invalid { it.getJSONArray("schedules").getJSONObject(0).getJSONArray("courses").getJSONObject(0).put("deleted", "true") }
        invalid { it.getJSONArray("schedules").getJSONObject(0).getJSONArray("courses").getJSONObject(0).put("weeks", "999") }
    }
    @Test fun credentialsBackgroundAndInvalidPreferencesCannotBeImported() {
        assertFalse(BackupCodec.allowed("display", "background"))
        assertFalse(BackupCodec.allowed("credentials", "session"))
        assertFalse(BackupCodec.allowed("privacy", "version"))
        invalid { it.getJSONObject("preferences").put("credentials", JSONObject()) }
        invalid { it.getJSONObject("preferences").getJSONObject("display").put("background", "x") }
        invalid { it.getJSONObject("preferences").getJSONObject("display").put("active_table", "missing") }
        invalid { it.getJSONObject("preferences").getJSONObject("display").put("widget_opacity", 101) }
        invalid { it.getJSONObject("preferences").getJSONObject("course-alerts").put("minutes", 61) }
    }
    @Test fun emptyBackupIsValid() {
        val original = sample().copy(schedules = emptyList())
        original.preferences.getJSONObject("display").remove("active_table")
        assertEquals(0, BackupCodec.decode(BackupCodec.encode(original)).courseCount)
    }
    @Test fun partialWriteRollsBackAndRetainsJournalWhenRollbackFails() = runBlocking {
        var state = "old"
        var journal: String? = null
        try {
            replaceWithRollback("old", "new", { journal = it }, {
                state = it
                if (it == "new") error("settings write failed")
            }, { journal = null })
            fail("must fail")
        } catch (_: IllegalStateException) { }
        assertEquals("old", state)
        assertNull(journal)
        try {
            replaceWithRollback("old", "new", { journal = it }, { error("disk full") }, { journal = null })
            fail("must fail")
        } catch (e: IllegalStateException) { assertEquals(1, e.suppressed.size) }
        assertEquals("old", journal)
    }
    @Test fun cannotWriteBeforeJournalAndSuccessClearsJournal() = runBlocking {
        var writes = 0
        try { replaceWithRollback("old", "new", { error("journal failed") }, { writes++ }, {}) }
        catch (_: IllegalStateException) { }
        assertEquals(0, writes)
        val actions = mutableListOf<String>()
        replaceWithRollback("old", "new", { actions.add("journal:$it") }, { actions.add("replace:$it") }, { actions.add("clear") })
        assertEquals(listOf("journal:old", "replace:new", "clear"), actions)
    }
}
