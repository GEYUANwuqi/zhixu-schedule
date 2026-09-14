package cn.edu.sycu.schedule

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class JsonTransferTest {
    private val table = Timetable(name = "测试课表", year = 2026, start = "2026-08-24", times = """[{"period":1,"start":"08:20","end":"09:05"}]""")
    private val course = Course(timetableId = table.id, name = "数学", teacher = "教师", room = "B101", periods = "1", weeks = "1-3,5", note = "备注", sourceId = "remote")
    private fun encoded() = JsonTransfer.encode(table, listOf(course))

    @Test fun roundTripPreservesDetailsAndCreatesIndependentIds() {
        val parsed = JsonTransfer.decode(encoded())
        assertEquals(table.copy(id = parsed.table.id), parsed.table)
        assertEquals(course.copy(id = parsed.courses[0].id, timetableId = parsed.table.id, sourceId = null, edited = true), parsed.courses[0])
        assertNotEquals(table.id, parsed.table.id)
        assertNotEquals(course.id, parsed.courses[0].id)
        assertFalse(encoded().contains("sourceId"))
    }
    @Test fun excludesDeletedAndOtherTables() {
        val parsed = JsonTransfer.decode(JsonTransfer.encode(table, listOf(course, course.copy(deleted = true), course.copy(timetableId = "other"))))
        assertEquals(1, parsed.courses.size)
    }
    @Test fun emptyScheduleAndBomAreSupported() {
        val json = JsonTransfer.encode(table, emptyList())
        assertTrue(JsonTransfer.decode("\uFEFF$json").courses.isEmpty())
    }
    @Test fun rejectsUnsupportedVersionAndInvalidCourseBeforeImport() {
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("version", 2) },
            { it.put("version", "1") },
            { it.getJSONObject("timetable").put("start", "2026-02-30") },
            { it.getJSONArray("courses").getJSONObject(0).put("weekday", 8) },
            { it.getJSONArray("courses").getJSONObject(0).put("weeks", "1-65") },
            { it.getJSONObject("timetable").getJSONArray("times").getJSONObject(0).put("end", "07:00") }
        )) {
            val root = JSONObject(encoded()); change(root)
            assertThrows(IllegalArgumentException::class.java) { JsonTransfer.decode(root.toString()) }
        }
        assertThrows(IllegalArgumentException::class.java) { JsonTransfer.decode(encoded() + "garbage") }
    }
    @Test fun oversizedInputRejected() {
        assertThrows(IllegalArgumentException::class.java) { JsonTransfer.read(ByteArray(2 * 1024 * 1024 + 1).inputStream()) }
    }
}
