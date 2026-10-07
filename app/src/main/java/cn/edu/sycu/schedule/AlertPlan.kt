package cn.edu.sycu.schedule

import java.time.LocalTime
import java.time.ZonedDateTime

internal fun reminderMinutes(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..60 }
internal fun countdownMinutes(now: ZonedDateTime, target: ZonedDateTime): Long =
    ((java.time.Duration.between(now, target).toMillis().coerceAtLeast(0) + 59_999) / 60_000)
data class AlertOptions(val ongoing: Boolean = false, val reminders: Boolean = false, val minutes: Int = 20, val name: Boolean = true, val time: Boolean = true, val room: Boolean = true, val ongoingCountdown: Boolean = true, val ongoingTime: Boolean = true, val ongoingRoom: Boolean = true, val ongoingTeacher: Boolean = true, val endReminders: Boolean = false)
internal fun countdownLabel(minutes: Long): String = when {
    minutes < 60 -> "$minutes 分钟"
    minutes % 60 == 0L -> "${minutes / 60} 小时"
    else -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
}
enum class AlertPhase { UPCOMING, CLASS, BREAK }
data class AlertStatus(val item: Occurrence, val phase: AlertPhase, val target: ZonedDateTime, val beforeBreak: Boolean = false) {
    val title: String get() = when (phase) {
        AlertPhase.UPCOMING -> "下一节 · ${item.course.name}"
        AlertPhase.CLASS -> "正在上课 · ${item.course.name}"
        AlertPhase.BREAK -> "课间时间"
    }
}
internal fun ongoingLines(status: AlertStatus, now: ZonedDateTime, options: AlertOptions): Pair<String, String> {
    val item = status.item
    val timeFormat = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    val label = when {
        status.phase != AlertPhase.CLASS -> "距上课"
        status.beforeBreak -> "距课间"
        else -> "距下课"
    }
    val first = listOfNotNull(
        "$label ${countdownLabel(countdownMinutes(now, status.target))}".takeIf { options.ongoingCountdown },
        "${item.start.format(timeFormat)}–${item.end.format(timeFormat)}".takeIf { options.ongoingTime },
    ).joinToString(" · ")
    val second = listOfNotNull(
        roomLabel(item.course.room).takeIf { options.ongoingRoom && it.isNotBlank() },
        item.course.teacher.takeIf { options.ongoingTeacher && it.isNotBlank() },
    ).joinToString(" · ")
    return first to second
}
fun occurrenceKey(item: Occurrence) = "${item.course.id}:${item.start.toInstant().toEpochMilli()}"
data class EndReminder(val item: Occurrence, val at: ZonedDateTime, val resumesAt: ZonedDateTime? = null) {
    val key: String get() = "end:${occurrenceKey(item)}:${at.toInstant().toEpochMilli()}"
    val title: String get() = if (resumesAt != null) "下课提醒 · 课间休息" else "下课提醒 · 课程结束"
}
private data class AlertLesson(val start: ZonedDateTime, val end: ZonedDateTime)
private data class AlertSession(val item: Occurrence, val lessons: List<AlertLesson>) {
    fun status(now: ZonedDateTime): AlertStatus? {
        lessons.forEachIndexed { index, lesson ->
            if (!now.isBefore(lesson.start) && now.isBefore(lesson.end))
                return AlertStatus(item, AlertPhase.CLASS, lesson.end, index < lessons.lastIndex)
            val next = lessons.getOrNull(index + 1)
            if (next != null && !now.isBefore(lesson.end) && now.isBefore(next.start))
                return AlertStatus(item, AlertPhase.BREAK, next.start)
        }
        return null
    }
    fun ends(): List<EndReminder> = lessons.mapIndexed { index, lesson ->
        EndReminder(item, lesson.end, lessons.getOrNull(index + 1)?.start)
    }
}
private fun alertSession(t: Timetable, item: Occurrence): AlertSession {
    val times = timesFor(t)
    val lessons = mutableListOf<AlertLesson>()
    item.course.periodList().forEach { period ->
        val time = times[period - 1]
        val start = item.date.atTime(LocalTime.parse(time.first)).atZone(schoolZone)
        val end = item.date.atTime(LocalTime.parse(time.second)).atZone(schoolZone)
        // Nonconsecutive periods already belong to separate occurrences.
        if (start.isBefore(item.start) || end.isAfter(item.end)) return@forEach
        val previous = lessons.lastOrNull()
        if (previous != null && !start.isAfter(previous.end))
            lessons[lessons.lastIndex] = previous.copy(end = maxOf(previous.end, end))
        else lessons.add(AlertLesson(start, end))
    }
    return AlertSession(item, lessons)
}
data class AlertPlan(val today: List<Occurrence>, val due: List<Occurrence>, val next: ZonedDateTime, val holiday: Boolean = false, val status: AlertStatus? = null, val ends: List<EndReminder> = emptyList(), val endDue: List<EndReminder> = emptyList())
fun alertPlan(t: Timetable?, courses: List<Course>, options: AlertOptions, now: ZonedDateTime, mutedNames: Set<String> = emptySet(), holidayDates: Set<java.time.LocalDate> = emptySet(), holidayKeys: Set<String> = emptySet()): AlertPlan {
    val local = now.withZoneSameInstant(schoolZone)
    val day = local.toLocalDate()
    val original = if (t == null) emptyList() else todayLessons(t, courses, day)
    val all = if (day in holidayDates) emptyList() else original.filterNot { occurrenceKey(it) in holidayKeys }
    val holiday = t != null && all.isEmpty()
    val sessions = all.map { alertSession(requireNotNull(t), it) }
    val states = sessions.mapNotNull { it.status(local) }
    val status = states.firstOrNull { it.phase == AlertPhase.CLASS }
        ?: states.firstOrNull { it.phase == AlertPhase.BREAK }
        ?: all.firstOrNull { it.start.isAfter(local) }?.let { AlertStatus(it, AlertPhase.UPCOMING, it.start) }
    val due = if (!options.reminders) emptyList() else all.filter { it.course.name !in mutedNames && !local.isBefore(it.start.minusMinutes(options.minutes.toLong())) && local.isBefore(it.start) }
    val ends = sessions.flatMap { it.ends() }
    // Allow a short delayed delivery, but never announce a break after class has resumed.
    val endDue = if (!options.endReminders) emptyList() else ends.filter {
        it.item.course.name !in mutedNames && !local.isBefore(it.at) &&
            local.isBefore(minOf(it.at.plusMinutes(5), it.resumesAt ?: it.at.plusMinutes(5)))
    }
    val boundaries = buildList {
        add(day.plusDays(1).atStartOfDay(schoolZone))
        sessions.forEach { session ->
            if (options.ongoing) session.lessons.forEach { add(it.start); add(it.end) }
            if (session.item.course.name !in mutedNames) {
                if (options.reminders) add(session.item.start.minusMinutes(options.minutes.toLong()))
                if (options.endReminders) session.lessons.forEach { add(it.end) }
            }
        }
    }
    return AlertPlan(all, due, boundaries.filter { it.isAfter(local) }.minOrNull()!!, holiday, status, ends, endDue)
}
private fun reminderFields(item: Occurrence, options: AlertOptions): String = listOfNotNull(
    item.course.name.takeIf { options.name },
    "${item.start.toLocalTime()}–${item.end.toLocalTime()}".takeIf { options.time },
    roomLabel(item.course.room).takeIf { options.room && it.isNotBlank() },
).joinToString(" · ")
fun reminderText(item: Occurrence, options: AlertOptions): String = reminderFields(item, options).ifBlank { "即将上课，请查看课表" }
fun endReminderText(reminder: EndReminder, options: AlertOptions): String = reminderFields(reminder.item, options)
    .ifBlank { if (reminder.resumesAt != null) "课间时间，请稍作休息" else "课程已结束，请查看课表" }
