package cn.edu.sycu.schedule

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class WinterScheduleTest {
    private val table = Timetable(id = "table", start = "2026-08-24")
    private val winter = table.copy(winterSchedule = true)
    private val course = Course(id = "afternoon", timetableId = table.id, name = "数学", periods = "5,6", weeks = "4")
    private val date = LocalDate.parse("2026-09-14")
    private fun now(time: String) = ZonedDateTime.parse("${date}T$time:00+08:00").withZoneSameInstant(schoolZone)

    @Test fun winterTimesMatchSchoolImageAndLeaveMorningAndEveningUnchanged() {
        assertEquals(listOf("13:00" to "13:45", "13:55" to "14:40", "14:50" to "15:35", "15:45" to "16:30"), timesFor(winter).subList(4, 8))
        assertEquals(timesFor(table).take(4), timesFor(winter).take(4))
        assertEquals(timesFor(table).drop(8), timesFor(winter).drop(8))
    }

    @Test fun switchingPreservesOriginalCustomTimesAndAdditionalPeriods() {
        val custom = table.copy(times = """[{"period":5,"start":"13:40","end":"14:25"},{"period":11,"start":"20:00","end":"20:45"}]""")
        val enabled = custom.copy(winterSchedule = true)
        assertEquals("13:00" to "13:45", timesFor(enabled)[4])
        assertEquals("20:00" to "20:45", timesFor(enabled)[10])
        assertEquals(custom.times, enabled.times)
        assertEquals(timesFor(custom), timesFor(enabled.copy(winterSchedule = false)))
    }

    @Test fun occurrencesMakeupsAndCourseHighlightUseWinterTimes() {
        val item = todayLessons(winter, listOf(course), date).single()
        assertEquals(now("13:00"), item.start)
        assertEquals(now("14:40"), item.end)
        assertEquals(LessonPhase.CURRENT, coursePhase(winter, 4, 1, listOf(5, 6), now("13:10")))
        assertEquals(LessonPhase.PAST, coursePhase(winter, 4, 1, listOf(5, 6), now("14:40")))
        val makeup = Makeup(id = "extra", tableId = table.id, date = date.toString(), name = "补课", teacher = "", room = "", periods = "7,8").course(winter)
        assertEquals(now("14:50"), occurrences(winter, makeup).single().start)
        assertEquals(now("16:30"), occurrences(winter, makeup).single().end)
        assertEquals(5f, currentTimeRow(timesFor(winter), LocalTime.parse("13:50"))!!, .001f)
        assertNull(currentTimeRow(timesFor(winter), LocalTime.parse("13:10"), setOf(5, 6)))
    }

    @Test fun remindersBreaksCountdownsAndNextAlarmUseWinterTimes() {
        val options = AlertOptions(ongoing = true, reminders = true, endReminders = true)
        val before = alertPlan(winter, listOf(course), options, now("12:40"))
        assertEquals(now("13:00"), before.due.single().start)
        assertEquals(now("13:00"), before.next)
        val during = alertPlan(winter, listOf(course), options, now("13:10"))
        assertEquals("距课间 35 分钟 · 13:00–14:40", ongoingLines(during.status!!, now("13:10"), options).first)
        val rest = alertPlan(winter, listOf(course), options, now("13:45"))
        assertEquals(AlertPhase.BREAK, rest.status!!.phase)
        assertEquals(now("13:55"), rest.status.target)
        assertEquals(now("13:45"), rest.endDue.single().at)
        assertEquals(now("14:40"), alertPlan(winter, listOf(course), options, now("14:40")).endDue.single().at)
        assertEquals(AlertPhase.UPCOMING, alertPlan(table, listOf(course), options, now("13:10")).status!!.phase)
    }

    @Test fun holidaySelectionsFollowNormalAndMakeupClassesInBothDirections() {
        val makeup = Makeup(id = "extra", tableId = table.id, date = date.toString(), name = "补课", teacher = "", room = "", periods = "7,8").course(table)
        val courses = listOf(course, makeup)
        val original = courses.map { occurrenceKey(occurrences(table, it).single()) }.toSet() + "unrelated:1"
        val adjusted = retimeHolidayKeys(table, winter, courses, original)
        assertEquals(courses.map { occurrenceKey(occurrences(winter, it).single()) }.toSet() + "unrelated:1", adjusted)
        assertEquals(original, retimeHolidayKeys(winter, table, courses, adjusted))
        assertTrue(alertPlan(winter, courses, AlertOptions(endReminders = true), now("13:45"), holidayKeys = adjusted).today.isEmpty())
    }
}
