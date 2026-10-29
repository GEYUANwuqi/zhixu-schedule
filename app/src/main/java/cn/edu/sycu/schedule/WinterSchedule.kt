package cn.edu.sycu.schedule

import android.content.Context
import androidx.room.withTransaction

internal val winterLessonTimes = listOf(
    "08:20" to "09:05",
    "09:15" to "10:00",
    "10:20" to "11:05",
    "11:15" to "12:00",
    "13:00" to "13:45",
    "13:55" to "14:40",
    "14:50" to "15:35",
    "15:45" to "16:30",
    "18:00" to "18:45",
    "18:55" to "19:40",
)

/** Holiday identities include the start instant, so carry selections to the new times. */
internal fun retimeHolidayKeys(before: Timetable, after: Timetable, courses: List<Course>, keys: Set<String>): Set<String> {
    if (keys.isEmpty()) return keys
    val replacements = courses.filter { it.timetableId == before.id && !it.deleted }.flatMap { course ->
        occurrences(before, course).zip(occurrences(after, course)).map { (old, new) -> occurrenceKey(old) to occurrenceKey(new) }
    }.toMap()
    return keys.map { replacements[it] ?: it }.toSet()
}

internal suspend fun ScheduleDb.setWinterSchedule(context: Context, table: Timetable, enabled: Boolean) {
    var previousKeys: Set<String>? = null
    try {
        withTransaction {
            val dao = dao()
            require(dao.findTable(table.id) == table) { "课表已变更，请重新切换作息" }
            val updated = table.copy(winterSchedule = enabled)
            val courses = dao.allCourses(table.id) + MakeupStore.courses(context, table)
            val keys = HolidayStore.keys(context, table.id)
            val retimed = retimeHolidayKeys(table, updated, courses, keys)
            dao.save(updated)
            if (keys != retimed) {
                previousKeys = keys
                HolidayStore.replaceKeys(context, table.id, retimed)
            }
        }
    } catch (failure: Exception) {
        previousKeys?.let { keys ->
            try { HolidayStore.replaceKeys(context, table.id, keys) }
            catch (rollbackFailure: Exception) { failure.addSuppressed(rollbackFailure) }
        }
        throw failure
    }
    if (previousKeys != null) HolidayStore.revision.intValue++
    TodayWidget.refresh(context)
    CourseAlerts.request(context)
}
