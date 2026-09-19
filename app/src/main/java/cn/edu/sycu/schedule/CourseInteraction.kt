package cn.edu.sycu.schedule

import java.time.ZonedDateTime
import java.time.LocalTime

enum class LessonPhase { PAST, CURRENT, FUTURE }
fun lessonPhase(start: ZonedDateTime, end: ZonedDateTime, now: ZonedDateTime): LessonPhase =
    when { !now.isBefore(end) -> LessonPhase.PAST; !now.isBefore(start) -> LessonPhase.CURRENT; else -> LessonPhase.FUTURE }
fun coursePhase(t: Timetable, week: Int, day: Int, group: List<Int>, now: ZonedDateTime): LessonPhase {
    if (week == 0) return LessonPhase.FUTURE
    return runCatching {
        val date = firstWeekMonday(t).plusDays(((week - 1) * 7 + day - 1).toLong())
        val times = timesFor(t)
        lessonPhase(date.atTime(LocalTime.parse(times[group.first() - 1].first)).atZone(schoolZone), date.atTime(LocalTime.parse(times[group.last() - 1].second)).atZone(schoolZone), now)
    }.getOrDefault(LessonPhase.FUTURE)
}
fun phaseColor(color: Int, phase: LessonPhase): Int = when (phase) {
    LessonPhase.FUTURE -> color
    LessonPhase.CURRENT -> tintColor(color, .23f)
    LessonPhase.PAST -> color
}
fun phaseAlpha(phase: LessonPhase, pastOpacity: Int = 60): Float = if (phase == LessonPhase.PAST) pastOpacity.coerceIn(0, 100) / 100f else 1f

/** Move the original record across every teaching week, preserving its identity and duration. */
fun moveLesson(t: Timetable, c: Course, week: Int, group: List<Int>, day: Int, start: Int): Course {
    require(week in 1..t.weekCount && week in c.weekList() && day in 1..7)
    require(group.isNotEmpty() && group.zipWithNext().all { it.second == it.first + 1 } && c.periodList().containsAll(group))
    val target = c.periodList().map { it + start - group.first() }
    val times = timesFor(t)
    require(target.all { it in 1..times.size && times[it - 1].first.isNotBlank() && times[it - 1].second.isNotBlank() }) { "目标节次没有配置时间" }
    if (day == c.weekday && target == c.periodList()) return c
    return c.copy(weekday = day, periods = target.joinToString(","), edited = false)
}
