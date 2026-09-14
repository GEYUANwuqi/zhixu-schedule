package cn.edu.sycu.schedule

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.time.LocalDate
import java.time.LocalTime

data class ImportedSchedule(val table: Timetable, val courses: List<Course>)

/** Portable course data only: imported courses are independent of remote synchronization. */
object JsonTransfer {
    private const val LIMIT = 2 * 1024 * 1024

    fun read(input: InputStream): ImportedSchedule {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= LIMIT) { "JSON 文件不能超过 2 MB" }
            output.write(buffer, 0, count)
        }
        return decode(output.toString("UTF-8"))
    }

    fun encode(t: Timetable, courses: List<Course>): String {
        val rows = JSONArray()
        courses.filter { !it.deleted && it.timetableId == t.id }.forEach { c ->
            rows.put(JSONObject().put("name", c.name).put("teacher", c.teacher)
                .put("room", c.room).put("weekday", c.weekday).put("periods", c.periods)
                .put("weeks", c.weeks).put("note", c.note))
        }
        val table = JSONObject().put("name", t.name).put("year", t.year)
            .put("semester", t.semester).put("start", t.start).put("weekCount", t.weekCount)
            .put("times", if (t.times.isBlank()) JSONArray() else JSONArray(t.times))
        val text = JSONObject().put("format", "zhixu-schedule").put("version", 1)
            .put("timetable", table).put("courses", rows).toString(2)
        require(text.toByteArray(Charsets.UTF_8).size <= LIMIT) { "课表过大，无法导出 JSON" }
        decode(text) // Never produce a file this version cannot import.
        return text
    }

    fun decode(text: String): ImportedSchedule {
        try {
            require(text.toByteArray(Charsets.UTF_8).size <= LIMIT)
            val reader = org.json.JSONTokener(text.removePrefix("\uFEFF"))
            val root = reader.nextValue() as JSONObject
            require(reader.nextClean() == '\u0000')
            require(root.getString("format") == "zhixu-schedule" && integer(root, "version") == 1)
            val r = root.getJSONObject("timetable")
            val times = r.getJSONArray("times")
            require(times.length() <= 30)
            val seen = mutableSetOf<Int>()
            for (i in 0 until times.length()) {
                val time = times.getJSONObject(i)
                require(integer(time, "period") in 1..30 && seen.add(integer(time, "period")))
                require(LocalTime.parse(string(time, "start", 8)) < LocalTime.parse(string(time, "end", 8)))
            }
            val t = Timetable(name = string(r, "name", 120), year = integer(r, "year"),
                semester = integer(r, "semester"), start = string(r, "start", 10),
                weekCount = integer(r, "weekCount"), times = if (times.length() == 0) "" else times.toString())
            require(t.name.isNotBlank() && t.year in 1900..9999 && t.semester in 1..2 && t.weekCount in 1..64)
            require(LocalDate.parse(t.start).year in 1900..9999)
            val rows = root.getJSONArray("courses")
            require(rows.length() <= 1000)
            val courses = (0 until rows.length()).map { i ->
                val c = rows.getJSONObject(i)
                Course(timetableId = t.id, name = string(c, "name", 200), teacher = string(c, "teacher", 200),
                    room = string(c, "room", 300), weekday = integer(c, "weekday"),
                    periods = string(c, "periods", 200), weeks = string(c, "weeks", 400),
                    note = string(c, "note", 4000), edited = true).also {
                    require(it.name.isNotBlank() && it.weekday in 1..7)
                    it.periodList()
                    require(it.weekList().all { week -> week <= t.weekCount })
                }
            }
            return ImportedSchedule(t, courses)
        } catch (e: Exception) {
            throw IllegalArgumentException("JSON 格式或课程数据无效，请选择知序导出的版本 1 文件", e)
        }
    }

    private fun string(o: JSONObject, key: String, max: Int): String {
        val value = o.get(key)
        require(value is String && value.length <= max)
        return value
    }
    private fun integer(o: JSONObject, key: String): Int {
        val value = o.get(key)
        require(value is Int || value is Long)
        val n = (value as Number).toLong()
        require(n in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        return n.toInt()
    }
}
