package cn.edu.sycu.schedule

import java.time.ZonedDateTime

internal fun reminderMinutes(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..60 }
internal fun countdownMinutes(now: ZonedDateTime, target: ZonedDateTime): Long =
    ((java.time.Duration.between(now, target).toMillis().coerceAtLeast(0) + 59_999) / 60_000)
data class AlertOptions(val ongoing: Boolean = false, val reminders: Boolean = false, val minutes: Int = 20, val name: Boolean = true, val time: Boolean = true, val room: Boolean = true, val ongoingCountdown: Boolean = true, val ongoingTime: Boolean = true, val ongoingRoom: Boolean = true, val ongoingTeacher: Boolean = true)
internal fun countdownLabel(minutes: Long): String = when {
    minutes < 60 -> "$minutes 分钟"
    minutes % 60 == 0L -> "${minutes / 60} 小时"
    else -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
}
internal fun ongoingLines(item: Occurrence, current: Boolean, now: ZonedDateTime, options: AlertOptions): Pair<String, String> {
    val timeFormat = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    val first = listOfNotNull(
        (if (current) "距下课 ${countdownLabel(countdownMinutes(now, item.end))}" else "距上课 ${countdownLabel(countdownMinutes(now, item.start))}").takeIf { options.ongoingCountdown },
        "${item.start.format(timeFormat)}–${item.end.format(timeFormat)}".takeIf { options.ongoingTime },
    ).joinToString(" · ")
    val second = listOfNotNull(
        roomLabel(item.course.room).takeIf { options.ongoingRoom && it.isNotBlank() },
        item.course.teacher.takeIf { options.ongoingTeacher && it.isNotBlank() },
    ).joinToString(" · ")
    return first to second
}
fun occurrenceKey(item: Occurrence) = "${item.course.id}:${item.start.toInstant().toEpochMilli()}"
data class AlertPlan(val today: List<Occurrence>, val due: List<Occurrence>, val next: ZonedDateTime)
fun alertPlan(t: Timetable?, courses: List<Course>, options: AlertOptions, now: ZonedDateTime, mutedNames: Set<String> = emptySet()): AlertPlan {
    val local = now.withZoneSameInstant(schoolZone)
    val day = local.toLocalDate()
    val all = if (t == null) emptyList() else todayLessons(t, courses, day)
    val due = if (!options.reminders) emptyList() else all.filter { it.course.name !in mutedNames && !local.isBefore(it.start.minusMinutes(options.minutes.toLong())) && local.isBefore(it.start) }
    val boundaries = buildList {
        add(day.plusDays(1).atStartOfDay(schoolZone))
        all.forEach {
            if (options.ongoing) { add(it.start); add(it.end) }
            if (options.reminders && it.course.name !in mutedNames) add(it.start.minusMinutes(options.minutes.toLong()))
        }
    }
    return AlertPlan(all, due, boundaries.filter { it.isAfter(local) }.minOrNull()!!)
}
fun reminderText(item: Occurrence, options: AlertOptions): String = listOfNotNull(
    item.course.name.takeIf { options.name },
    "${item.start.toLocalTime()}–${item.end.toLocalTime()}".takeIf { options.time },
    roomLabel(item.course.room).takeIf { options.room && it.isNotBlank() },
).joinToString(" · ").ifBlank { "即将上课，请查看课表" }
