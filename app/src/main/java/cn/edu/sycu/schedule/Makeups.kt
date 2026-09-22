package cn.edu.sycu.schedule

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

val Course.isMakeup get() = id.startsWith("makeup:")
data class Makeup(val id: String = UUID.randomUUID().toString(), val tableId: String, val date: String, val name: String, val teacher: String, val room: String, val periods: String, val note: String = "") {
    fun course(t: Timetable) = Course(id = "makeup:$id:$date", timetableId = tableId, name = name, teacher = teacher, room = room,
        weekday = LocalDate.parse(date).dayOfWeek.value, periods = periods, weeks = weekOn(t, LocalDate.parse(date)).coerceIn(1, t.weekCount).toString(), note = note)
}
object MakeupCodec {
    fun encode(items: List<Makeup>): String = JSONArray().apply { items.forEach {
        put(JSONObject().put("id", it.id).put("tableId", it.tableId).put("date", it.date).put("name", it.name).put("teacher", it.teacher).put("room", it.room).put("periods", it.periods).put("note", it.note))
    } }.toString().also { decode(it) }
    fun decode(text: String): List<Makeup> {
        require(text.toByteArray().size <= 2 * 1024 * 1024) { "补课数据过大" }
        val reader = org.json.JSONTokener(text)
        val rows = reader.nextValue() as JSONArray
        require(reader.nextClean() == '\u0000' && rows.length() <= 1000)
        val ids = mutableSetOf<String>()
        return (0 until rows.length()).map { index ->
            val r = rows.getJSONObject(index)
            fun s(key: String, max: Int = 200) = (r.get(key) as String).also { require(it.length <= max) }
            Makeup(s("id", 64), s("tableId", 256), s("date", 10), s("name"), s("teacher"), s("room", 300), s("periods"), s("note", 4000)).also {
                require(it.id.matches(Regex("[A-Za-z0-9-]+")) && ids.add(it.id) && it.tableId.isNotBlank() && it.name.isNotBlank())
                require(LocalDate.parse(it.date).year in 1900..9999)
                numbers(it.periods, 30)
            }
        }
    }
}
object MakeupStore {
    val revision = mutableIntStateOf(0)
    fun read(context: Context) = MakeupCodec.decode(context.getSharedPreferences("display", 0).getString("makeup_lessons", "[]")!!)
    fun save(context: Context, value: Makeup) = write(context, read(context).filterNot { it.id == value.id } + value)
    fun addAll(context: Context, values: List<Makeup>) = write(context, read(context) + values)
    fun remove(context: Context, id: String) = write(context, read(context).filterNot { it.id == id })
    private fun write(context: Context, items: List<Makeup>) {
        check(context.getSharedPreferences("display", 0).edit().putString("makeup_lessons", MakeupCodec.encode(items)).commit()) { "无法保存补课，请检查设备空间" }
        revision.intValue++
        TodayWidget.refresh(context); CourseAlerts.request(context)
    }
    fun courses(context: Context, t: Timetable) = read(context).filter { it.tableId == t.id }.map { it.course(t) }
}
internal fun makeupPreviewCourses(t: Timetable, courses: List<Course>, week: Int): List<Course> = courses
    .filter { it.timetableId == t.id && !it.deleted && !it.isMakeup && week in it.weekList() }
    .flatMap { c ->
        val groups = mutableListOf<MutableList<Int>>()
        c.periodList().forEach { p -> if (groups.isEmpty() || groups.last().last() + 1 != p) groups.add(mutableListOf(p)) else groups.last().add(p) }
        groups.map { group -> c.copy(id = "preview:${c.id}:${group.first()}", periods = group.joinToString(","), weeks = week.toString()) }
    }
internal fun makeupsFromSelection(t: Timetable, courses: List<Course>, date: LocalDate, singlePeriods: String? = null): List<Makeup> {
    require(courses.isNotEmpty()) { "请先选择课程" }
    return courses.map { c ->
        require(c.timetableId == t.id && !c.deleted)
        Makeup(tableId = t.id, date = date.toString(), name = c.name, teacher = c.teacher, room = c.room,
            periods = if (courses.size == 1 && singlePeriods != null) singlePeriods else c.periods, note = c.note).also { makeupOccurrences(t, it.course(t)) }
    }.also { MakeupCodec.encode(it) }
}
internal fun toggleMakeupDay(courses: List<Course>, day: Int, selected: Set<String>): Set<String> {
    val all = courses.filter { it.weekday == day }.map { it.id }.toSet()
    return if (all.isNotEmpty() && selected == all) emptySet() else all
}
fun makeupOccurrences(t: Timetable, c: Course): List<Occurrence> {
    val date = LocalDate.parse(c.id.substringAfterLast(':'))
    val temporary = t.copy(start = date.minusDays((date.dayOfWeek.value - 1).toLong()).toString(), weekCount = 1)
    return occurrences(temporary, c.copy(id = c.id.removePrefix("makeup:"), weeks = "1")).map { it.copy(course = c, week = weekOn(t, date)) }
}
fun lessonShape(makeup: Boolean) = RoundedCornerShape(topStart = if (makeup) 0.dp else 5.dp, topEnd = 5.dp, bottomEnd = 5.dp, bottomStart = 5.dp)
fun Modifier.makeupCorner(makeup: Boolean, holiday: Boolean = false): Modifier = if (!makeup && !holiday) this else drawWithContent {
    drawContent()
    val edge = 11.dp.toPx().coerceAtMost(size.width / 3)
    drawPath(Path().apply { moveTo(0f, 0f); lineTo(edge, 0f); lineTo(0f, edge); close() }, Color(if (holiday) 0xFF39A66B else 0xFFE34B4B))
}
