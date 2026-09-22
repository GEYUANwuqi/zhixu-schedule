package cn.edu.sycu.schedule

import android.content.Context
import androidx.room.*
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

@Entity
data class Timetable(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String = "秋冬学期",
    val year: Int = today().year,
    val semester: Int = 1,
    val start: String = "${today().year}-08-24",
    val weekCount: Int = 20,
    val times: String = "",
)

@Entity(indices = [Index("timetableId")])
data class Course(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val timetableId: String,
    val name: String = "",
    val teacher: String = "",
    val room: String = "",
    val weekday: Int = 1,
    val periods: String = "1,2",
    val weeks: String = "1-16",
    val note: String = "",
    val sourceId: String? = null,
    val edited: Boolean = false,
    val deleted: Boolean = false,
) {
    fun periodList() = numbers(periods, 30)

    fun weekList() = numbers(weeks, 64)
}

fun numbers(s: String, max: Int): List<Int> {
    require(s.isNotBlank()) { "周次或节次不能为空" }
    val result =
        s.replace('，', ',')
            .split(',')
            .flatMap { part ->
                val p = part.trim().split('-')
                require(p.size in 1..2) { "请输入数字、逗号或范围，例如 1-4,6" }
                val a = p[0].trim().toIntOrNull() ?: error("请输入有效数字")
                val b = p.last().trim().toIntOrNull() ?: error("请输入有效数字")
                require(a in 1..max && b in a..max) { "数字范围应为 1–$max" }
                (a..b).toList()
            }
            .distinct()
            .sorted()
    return result
}

fun roomLabel(room: String): String {
    val map =
        mapOf(
            "J" to "经管楼",
            "G" to "建工楼",
            "D" to "机电楼",
            "N" to "南楼",
            "X" to "西山会所",
            "L" to "工训楼",
            "B" to "白宫",
            "C" to "传媒楼",
            "H" to "宏志楼",
        )
    val prefix =
        Regex("^([A-Za-z])\\d+[A-Za-z]?$")
            .matchEntire(room.trim())
            ?.groupValues
            ?.get(1)
            ?.uppercase()
    return map[prefix]?.let { "${room.trim()}($it)" }
        ?: room.replace("（", "(").replace("）", ")").replace("西山会所2楼", "西山会所")
}

val schoolZone: ZoneId = ZoneId.of("Asia/Shanghai")

fun today(): LocalDate = LocalDate.now(schoolZone)

fun firstWeekMonday(t: Timetable): LocalDate =
    LocalDate.parse(t.start)
        .with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

fun weekOn(t: Timetable, date: LocalDate = today()): Int =
    Math.floorDiv(ChronoUnit.DAYS.between(firstWeekMonday(t), date).toInt(), 7) + 1

val lessonTimes =
    listOf(
        "08:20" to "09:05",
        "09:15" to "10:00",
        "10:20" to "11:05",
        "11:15" to "12:00",
        "13:30" to "14:15",
        "14:25" to "15:10",
        "15:20" to "16:05",
        "16:15" to "17:00",
        "18:00" to "18:45",
        "18:55" to "19:40",
    )

fun timesFor(t: Timetable): List<Pair<String, String>> {
    if (t.times.isBlank()) return lessonTimes
    val array = org.json.JSONArray(t.times)
    val map =
        (0 until array.length()).associate { i ->
            val r = array.getJSONObject(i)
            r.getInt("period") to (r.getString("start") to r.getString("end"))
        }
    return (1..(map.keys.maxOrNull() ?: 10)).map { map[it] ?: ("" to "") }
}

data class Occurrence(
    val course: Course,
    val week: Int,
    val date: LocalDate,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
)

fun occurrences(t: Timetable, c: Course): List<Occurrence> {
    if (c.isMakeup) return makeupOccurrences(t, c)
    val lessonTimes = timesFor(t)
    val p = c.periodList()
    require(p.all { it <= lessonTimes.size && lessonTimes[it - 1].first.isNotBlank() }) {
        "课程包含尚未配置时间的节次"
    }
    return c.weekList()
        .filter { it <= t.weekCount }
        .flatMap { week ->
            val date = firstWeekMonday(t).plusDays(((week - 1) * 7 + c.weekday - 1).toLong())
            val groups = mutableListOf<MutableList<Int>>()
            p.forEach {
                if (groups.isEmpty() || groups.last().last() + 1 != it)
                    groups.add(mutableListOf(it))
                else groups.last().add(it)
            }
            groups.map {
                Occurrence(
                    c,
                    week,
                    date,
                    date
                        .atTime(LocalTime.parse(lessonTimes[it.first() - 1].first))
                        .atZone(schoolZone),
                    date
                        .atTime(LocalTime.parse(lessonTimes[it.last() - 1].second))
                        .atZone(schoolZone),
                )
            }
        }
}

fun parseCourses(data: JSONObject, t: Timetable): List<Course> {
    val rows = data.getJSONArray("courses")
    return (0 until rows.length()).map { index ->
        val r = rows.getJSONObject(index)
        fun array(k: String): String {
            val a = r.getJSONArray(k)
            return (0 until a.length()).joinToString(",") { i -> a.getInt(i).toString() }
        }
        Course(
                id = t.id + ":" + r.getString("id"),
                timetableId = t.id,
                name = r.getString("name"),
                teacher = r.getString("teacher"),
                room = r.getString("room"),
                weekday = r.getInt("weekday"),
                periods = array("periods"),
                weeks = array("weeks"),
                sourceId = r.getString("id"),
            )
            .also { course ->
                require(course.weekday in 1..7)
                course.periodList()
                require(course.weekList().all { it <= t.weekCount }) { "学校课程超出当前教学周数，请先调整课表设置" }
            }
    }
}

@Dao
abstract class ScheduleDao {
    @Query("SELECT * FROM Timetable ORDER BY year DESC,name")
    abstract fun timetables(): Flow<List<Timetable>>

    @Query("SELECT * FROM Course WHERE deleted=0 ORDER BY rowid")
    abstract fun courses(): Flow<List<Course>>

    @Query("SELECT * FROM Course WHERE timetableId=:id ORDER BY rowid")
    abstract suspend fun allCourses(id: String): List<Course>

    @Query("SELECT * FROM Timetable WHERE id=:id")
    abstract suspend fun findTable(id: String): Timetable?

    @Transaction
    open suspend fun confirmSync(proposal: SyncProposal) {
        require(findTable(proposal.table.id) == proposal.previousTable && allCourses(proposal.table.id) == proposal.previous) {
            "课表已变更，请取消并重新获取同步预览"
        }
        merge(proposal.table, proposal.rows.map { it.result })
    }

    @Query("DELETE FROM Course") abstract suspend fun clearAllCourses()
    @Query("DELETE FROM Timetable") abstract suspend fun clearAllTables()

    @Upsert abstract suspend fun save(t: Timetable)

    @Upsert abstract suspend fun save(c: Course)

    @Query("DELETE FROM Course WHERE id=:id") abstract suspend fun removeCourse(id: String)

    @Query("DELETE FROM Course WHERE timetableId=:id") abstract suspend fun clearCourses(id: String)

    @Query("DELETE FROM Timetable WHERE id=:id") abstract suspend fun removeTable(id: String)

    @Transaction
    open suspend fun moveOccurrence(t: Timetable, c: Course, week: Int, group: List<Int>, day: Int, start: Int) {
        val current = allCourses(t.id).firstOrNull { it.id == c.id && !it.deleted }
        require(current == c) { "课程已变更，请重新拖动" }
        save(moveLesson(t, c, week, group, day, start))
    }

    @Transaction
    open suspend fun importSchedule(data: ImportedSchedule) {
        save(data.table)
        data.courses.forEach { save(it) }
    }

    @Transaction
    open suspend fun deleteTable(id: String) {
        clearCourses(id)
        removeTable(id)
    }

    @Transaction
    open suspend fun merge(t: Timetable, incoming: List<Course>) {
        save(t)
        val previous = allCourses(t.id)
        previous
            .filter { it.sourceId != null && !it.edited && !it.deleted }
            .forEach { removeCourse(it.id) }
        val protected = previous.filter { it.edited || it.deleted }.map { it.id }.toSet()
        incoming.filter { it.id !in protected }.forEach { save(it) }
    }
}

@Database(entities = [Timetable::class, Course::class], version = 1, exportSchema = false)
abstract class ScheduleDb : RoomDatabase() {
    abstract fun dao(): ScheduleDao

    companion object {
        fun open(c: Context) =
            Room.databaseBuilder(c, ScheduleDb::class.java, "schedule.db").build()
    }
}

fun weekLabel(weeks: List<Int>): String {
    val sorted = weeks.distinct().sorted()
    if (sorted.isEmpty()) return ""
    if (sorted.size > 1 && sorted.zipWithNext().all { (a, b) -> b - a == 2 })
        return "第${sorted.first()}–${sorted.last()}周·${if(sorted.first()%2==1)"单"else"双"}"
    val ranges = mutableListOf<MutableList<Int>>()
    sorted.forEach {
        if (ranges.isEmpty() || ranges.last().last() + 1 != it) ranges.add(mutableListOf(it))
        else ranges.last().add(it)
    }
    return "第" +
        ranges.joinToString("、") {
            if (it.size == 1) it.first().toString() else "${it.first()}–${it.last()}"
        } +
        "周"
}
