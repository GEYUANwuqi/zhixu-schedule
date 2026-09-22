package cn.edu.sycu.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class AlertPlanTest {
    @Test fun individualHolidayOnlySuppressesSelectedOccurrence() {
        val english = course.copy(id = "english", name = "英语")
        val all = todayLessons(table, listOf(course, english), now("10:10").toLocalDate())
        val key = occurrenceKey(all.first { it.course.id == course.id })
        assertEquals(key, holidayKey(table, course, now("10:10").toLocalDate(), listOf(3, 4)))
        val partial = alertPlan(table, listOf(course, english), AlertOptions(reminders = true), now("10:10"), holidayKeys = setOf(key))
        assertFalse(partial.holiday)
        assertEquals(listOf("英语"), partial.today.map { it.course.name })
        assertEquals(listOf("英语"), partial.due.map { it.course.name })
        val complete = alertPlan(table, listOf(course, english), AlertOptions(reminders = true), now("10:10"), holidayKeys = all.map(::occurrenceKey).toSet())
        assertTrue(complete.holiday)
        assertTrue(complete.today.isEmpty())
        assertTrue(alertPlan(table, emptyList(), AlertOptions(), now("10:10"), holidayKeys = setOf(key)).holiday)
    }
    @Test fun holidaysSuppressLessonsAndRemindersUntilMidnightWithoutChangingCourses() {
        val date = now("10:10").toLocalDate()
        val options = AlertOptions(ongoing = true, reminders = true)
        val holiday = alertPlan(table, listOf(course), options, now("10:10"), holidayDates = setOf(date))
        assertTrue(holiday.holiday)
        assertTrue(holiday.today.isEmpty())
        assertTrue(holiday.due.isEmpty())
        assertEquals(now("00:00").plusDays(1).toInstant(), holiday.next.toInstant())
        val restored = alertPlan(table, listOf(course), options, now("10:10"))
        assertFalse(restored.holiday)
        assertEquals(1, restored.due.size)
        assertEquals(1, todayLessons(table, listOf(course), date).size)
        assertFalse(alertPlan(table, listOf(course), options, now("10:10"), holidayDates = setOf(date.plusDays(1))).holiday)
        assertFalse(alertPlan(null, emptyList(), options, now("10:10"), holidayDates = setOf(date)).holiday)
    }
    @Test fun ongoingUsesHoursAndTwoIndependentRows() {
        assertEquals("59 分钟", countdownLabel(59))
        assertEquals("1 小时", countdownLabel(60))
        assertEquals("1 小时 1 分钟", countdownLabel(61))
        assertEquals("2 小时", countdownLabel(120))
        val item = plan("10:10", courses = listOf(course.copy(teacher = "李老师"))).due.single()
        val lines = ongoingLines(item, false, now("09:00"), AlertOptions())
        assertEquals("距上课 1 小时 20 分钟 · 10:20–12:00", lines.first)
        assertEquals("B101(白宫) · 李老师", lines.second)
        assertEquals("距下课 1 小时 40 分钟 · 10:20–12:00", ongoingLines(item, true, now("10:20"), AlertOptions()).first)
        assertEquals("" to "", ongoingLines(item, false, now("09:00"), AlertOptions(ongoingCountdown = false, ongoingTime = false, ongoingRoom = false, ongoingTeacher = false)))
        assertEquals("10:20–12:00" to "李老师", ongoingLines(item, false, now("09:00"), AlertOptions(ongoingCountdown = false, ongoingRoom = false)))
    }
    @Test fun countdownRoundsUpToMinutesWithoutShowingSeconds() {
        val start = now("10:20")
        assertEquals(20L, countdownMinutes(start.minusMinutes(20), start))
        assertEquals(2L, countdownMinutes(start.minusSeconds(61), start))
        assertEquals(1L, countdownMinutes(start.minusSeconds(1), start))
        assertEquals(0L, countdownMinutes(start, start))
        assertEquals(0L, countdownMinutes(start.plusSeconds(1), start))
    }
    @Test fun customMinutesValidateAndScheduleAtBothBounds() {
        assertEquals(20, AlertOptions().minutes)
        listOf("", "0", "61", "-1", "1.5", "abc", "999999999999999").forEach { assertNull(reminderMinutes(it)) }
        for (minutes in listOf(1, 17, 60)) {
            assertEquals(minutes, reminderMinutes(minutes.toString()))
            val trigger = now("10:20").minusMinutes(minutes.toLong())
            val options = AlertOptions(reminders = true, minutes = minutes)
            val before = alertPlan(table, listOf(course), options, trigger.minusSeconds(1))
            assertTrue(before.due.isEmpty())
            assertEquals(trigger.toInstant(), before.next.toInstant())
            assertEquals(1, alertPlan(table, listOf(course), options, trigger).due.size)
        }
    }
    @Test fun mutedNamesExcludeAllMatchingLessonsButKeepOngoingSchedule() {
        val english = course.copy(id = "english", name = "英语")
        val mathAgain = course.copy(id = "math-again")
        val result = alertPlan(table, listOf(course, mathAgain, english), AlertOptions(reminders = true), now("10:10"), setOf("数学"))
        assertEquals(listOf("英语"), result.due.map { it.course.name })
        assertEquals(3, result.today.size)
        assertEquals(3, alertPlan(table, listOf(course, mathAgain, english), AlertOptions(reminders = true), now("10:10")).due.size)
    }
    @Test fun mutedCoursesDoNotScheduleAdvanceAlarmAndMatchingIsExact() {
        val muted = alertPlan(table, listOf(course), AlertOptions(reminders = true), now("10:00"), setOf("数学"))
        assertEquals(now("00:00").plusDays(1).toInstant(), muted.next.toInstant())
        assertEquals(1, alertPlan(table, listOf(course), AlertOptions(reminders = true), now("10:10"), setOf("数")).due.size)
        val ongoing = alertPlan(table, listOf(course), AlertOptions(reminders = true, ongoing = true), now("10:00"), setOf("数学"))
        assertEquals(now("10:20").toInstant(), ongoing.next.toInstant())
    }
    private val table = Timetable(start = "2026-08-24")
    private val course = Course(timetableId = table.id, name = "数学", room = "B101", weekday = 1, periods = "3,4", weeks = "4")
    private fun now(time: String) = ZonedDateTime.parse("2026-09-14T$time:00+08:00")
    private fun plan(time: String, minutes: Int = 10, courses: List<Course> = listOf(course)) = alertPlan(table, courses, AlertOptions(reminders = true, minutes = minutes), now(time))

    @Test fun advanceTimesAndLateDeliveryWindow() {
        assertTrue(plan("10:00").due.isEmpty())
        assertEquals(now("10:10").toInstant(), plan("10:00").next.toInstant())
        assertEquals(1, plan("10:00", 20).due.size)
        assertEquals(1, plan("10:10").due.size)
        assertEquals(1, plan("10:19").due.size)
        assertTrue(plan("10:20").due.isEmpty())
    }
    @Test fun deletedOtherTablesAndNonTeachingDatesAreExcluded() {
        assertTrue(plan("10:10", courses = listOf(course.copy(deleted = true), course.copy(timetableId = "other"))).today.isEmpty())
        assertTrue(alertPlan(table, listOf(course), AlertOptions(reminders = true), now("10:10").plusWeeks(1)).due.isEmpty())
        assertTrue(alertPlan(table.copy(weekCount = 3), listOf(course), AlertOptions(reminders = true), now("10:10")).today.isEmpty())
    }
    @Test fun concurrentCoursesHaveIndependentDeliveryKeys() {
        val items = plan("10:10", courses = listOf(course, course.copy(id = "second"))).due
        assertEquals(2, items.map(::occurrenceKey).toSet().size)
        assertTrue(plan("10:10", courses = listOf(course.copy(weekday = 3))).due.isEmpty())
        assertTrue(plan("10:10", courses = listOf(course.copy(periods = "5,6"))).due.isEmpty())
    }
    @Test fun ongoingBoundariesAndDisabledReminders() {
        val result = alertPlan(table, listOf(course), AlertOptions(ongoing = true), now("10:10"))
        assertTrue(result.due.isEmpty())
        assertEquals(now("10:20").toInstant(), result.next.toInstant())
        val empty = alertPlan(null, emptyList(), AlertOptions(ongoing = true), now("23:59"))
        assertEquals(now("00:00").plusDays(1).toInstant(), empty.next.toInstant())
    }
    @Test fun selectedFieldsDoNotLeakUnselectedValues() {
        val item = plan("10:10").due.single()
        assertEquals("数学", reminderText(item, AlertOptions(time = false, room = false)))
        assertEquals("即将上课，请查看课表", reminderText(item, AlertOptions(name = false, time = false, room = false)))
        assertEquals("10:20–12:00", reminderText(item, AlertOptions(name = false, room = false)))
    }
}
