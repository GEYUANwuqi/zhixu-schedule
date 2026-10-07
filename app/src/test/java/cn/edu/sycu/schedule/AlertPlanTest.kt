package cn.edu.sycu.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class AlertPlanTest {
    @Test fun ongoingSwitchesAtEveryBreakBoundary() {
        fun status(time: String) = alertPlan(table, listOf(course), AlertOptions(ongoing = true), now(time))
        val first = status("10:20")
        assertEquals("正在上课 · 数学", first.status!!.title)
        assertEquals(now("11:05"), first.status.target)
        assertEquals(now("11:05"), first.next)
        assertEquals("距课间 45 分钟 · 10:20–12:00", ongoingLines(first.status, now("10:20"), AlertOptions()).first)
        val rest = status("11:05")
        assertEquals(AlertPhase.BREAK, rest.status!!.phase)
        assertEquals("课间时间", rest.status.title)
        assertEquals("距上课 10 分钟 · 10:20–12:00", ongoingLines(rest.status, now("11:05"), AlertOptions()).first)
        assertEquals(now("11:15"), rest.next)
        val resumed = status("11:15")
        assertEquals(AlertPhase.CLASS, resumed.status!!.phase)
        assertEquals("距下课 45 分钟 · 10:20–12:00", ongoingLines(resumed.status, now("11:15"), AlertOptions()).first)
        assertEquals(now("12:00"), resumed.next)
        assertNull(status("12:00").status)
    }
    @Test fun endRemindersAreIndependentAndCoverBreaksAndFinalEnd() {
        val options = AlertOptions(endReminders = true)
        val before = alertPlan(table, listOf(course), options, now("10:20"))
        assertTrue(before.due.isEmpty())
        assertTrue(before.endDue.isEmpty())
        assertEquals(now("11:05"), before.next)
        val rest = alertPlan(table, listOf(course), options, now("11:05"))
        assertEquals("下课提醒 · 课间休息", rest.endDue.single().title)
        assertEquals(now("11:15"), rest.endDue.single().resumesAt)
        assertEquals(now("12:00"), rest.next)
        val end = alertPlan(table, listOf(course), options, now("12:00"))
        assertEquals("下课提醒 · 课程结束", end.endDue.single().title)
        assertNull(end.endDue.single().resumesAt)
        assertEquals(2, end.ends.map { it.key }.toSet().size)
        assertFalse(AlertOptions().endReminders)
        assertTrue(alertPlan(table, listOf(course), AlertOptions(reminders = true), now("11:05")).endDue.isEmpty())
        assertEquals(1, alertPlan(table, listOf(course), options.copy(reminders = true), now("10:10")).due.size)
    }
    @Test fun delayedEndRemindersExpireAndNeverAnnounceBreakAfterResuming() {
        val options = AlertOptions(endReminders = true)
        assertEquals(1, alertPlan(table, listOf(course), options, now("11:09")).endDue.size)
        assertTrue(alertPlan(table, listOf(course), options, now("11:10")).endDue.isEmpty())
        assertTrue(alertPlan(table, listOf(course), options, now("11:15")).endDue.isEmpty())
        assertEquals(1, alertPlan(table, listOf(course), options, now("12:04")).endDue.size)
        assertTrue(alertPlan(table, listOf(course), options, now("12:05")).endDue.isEmpty())
        val shortBreak = table.copy(times = """[{"period":3,"start":"10:20","end":"11:05"},{"period":4,"start":"11:07","end":"12:00"}]""")
        assertEquals(1, alertPlan(shortBreak, listOf(course), options, now("11:06")).endDue.size)
        assertTrue(alertPlan(shortBreak, listOf(course), options, now("11:07")).endDue.isEmpty())
    }
    @Test fun singlePeriodAndConsecutiveTimesWithoutGapCountDownToFinalEnd() {
        val single = course.copy(periods = "3")
        val singlePlan = alertPlan(table, listOf(single), AlertOptions(endReminders = true), now("10:20"))
        assertEquals("距下课 45 分钟 · 10:20–11:05", ongoingLines(singlePlan.status!!, now("10:20"), AlertOptions()).first)
        assertEquals(1, singlePlan.ends.size)
        val continuous = table.copy(times = """[{"period":3,"start":"10:20","end":"11:05"},{"period":4,"start":"11:05","end":"12:00"}]""")
        val continuousPlan = alertPlan(continuous, listOf(course), AlertOptions(ongoing = true, endReminders = true), now("10:20"))
        assertEquals("距下课 1 小时 40 分钟 · 10:20–12:00", ongoingLines(continuousPlan.status!!, now("10:20"), AlertOptions()).first)
        assertEquals(now("12:00"), continuousPlan.next)
        assertEquals(1, continuousPlan.ends.size)
        assertEquals(AlertPhase.CLASS, alertPlan(continuous, listOf(course), AlertOptions(), now("11:05")).status!!.phase)
    }
    @Test fun multipleBreaksUseCustomTimesAndDisjointPeriodsAreSeparateLessons() {
        val custom = table.copy(times = """[{"period":1,"start":"08:00","end":"08:30"},{"period":2,"start":"08:35","end":"09:05"},{"period":3,"start":"09:15","end":"09:45"}]""")
        val longCourse = course.copy(periods = "1-3")
        val options = AlertOptions(ongoing = true, endReminders = true)
        val plan = alertPlan(custom, listOf(longCourse), options, now("08:40"))
        assertEquals("距课间 25 分钟 · 08:00–09:45", ongoingLines(plan.status!!, now("08:40"), options).first)
        assertEquals(3, plan.ends.size)
        assertEquals("课间时间", alertPlan(custom, listOf(longCourse), options, now("09:05")).status!!.title)
        val disjoint = alertPlan(custom, listOf(longCourse.copy(periods = "1,3")), options, now("08:30"))
        assertEquals(AlertPhase.UPCOMING, disjoint.status!!.phase)
        assertTrue(disjoint.ends.all { it.resumesAt == null })
        assertEquals(2, disjoint.ends.map { it.key }.toSet().size)
    }
    @Test fun endRemindersRespectMutedDeletedAndHolidayCourses() {
        val options = AlertOptions(endReminders = true)
        val date = now("11:05").toLocalDate()
        val muted = alertPlan(table, listOf(course), options, now("10:20"), setOf("数学"))
        assertEquals(now("00:00").plusDays(1), muted.next)
        assertTrue(alertPlan(table, listOf(course), options, now("11:05"), setOf("数学")).endDue.isEmpty())
        assertTrue(alertPlan(table, listOf(course.copy(deleted = true)), options, now("11:05")).ends.isEmpty())
        assertTrue(alertPlan(table, listOf(course), options, now("11:05"), holidayDates = setOf(date)).ends.isEmpty())
        val key = occurrenceKey(todayLessons(table, listOf(course), date).single())
        assertTrue(alertPlan(table, listOf(course), options, now("11:05"), holidayKeys = setOf(key)).endDue.isEmpty())
    }
    @Test fun overlappingActualClassTakesPriorityOverBreak() {
        val other = course.copy(id = "other", name = "英语", periods = "3", teacher = "王老师")
        val custom = table.copy(times = """[{"period":1,"start":"10:20","end":"11:05"},{"period":2,"start":"11:15","end":"12:00"},{"period":3,"start":"11:00","end":"11:30"}]""")
        val result = alertPlan(custom, listOf(course.copy(periods = "1,2"), other), AlertOptions(), now("11:10"))
        assertEquals("正在上课 · 英语", result.status!!.title)
    }
    @Test fun makeupBreaksAndReminderFieldsFollowTheSameRules() {
        val makeup = Makeup(id = "extra", tableId = table.id, date = "2026-09-14", name = "补数学", teacher = "李老师", room = "B101", periods = "3,4", note = "").course(table)
        val result = alertPlan(table, listOf(makeup), AlertOptions(endReminders = true), now("11:05"))
        assertEquals("课间时间", result.status!!.title)
        val rest = result.endDue.single()
        assertEquals("补数学", endReminderText(rest, AlertOptions(time = false, room = false)))
        val hidden = AlertOptions(name = false, time = false, room = false)
        assertEquals("课间时间，请稍作休息", endReminderText(rest, hidden))
        assertEquals("课程已结束，请查看课表", endReminderText(result.ends.last(), hidden))
        assertEquals("10:20–12:00", endReminderText(rest, AlertOptions(name = false, room = false)))
    }
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
        val upcoming = AlertStatus(item, AlertPhase.UPCOMING, item.start)
        val lines = ongoingLines(upcoming, now("09:00"), AlertOptions())
        assertEquals("距上课 1 小时 20 分钟 · 10:20–12:00", lines.first)
        assertEquals("B101(白宫) · 李老师", lines.second)
        val current = alertPlan(table, listOf(course), AlertOptions(), now("10:20")).status!!
        assertEquals("距课间 45 分钟 · 10:20–12:00", ongoingLines(current, now("10:20"), AlertOptions()).first)
        assertEquals("" to "", ongoingLines(upcoming, now("09:00"), AlertOptions(ongoingCountdown = false, ongoingTime = false, ongoingRoom = false, ongoingTeacher = false)))
        assertEquals("10:20–12:00" to "李老师", ongoingLines(upcoming, now("09:00"), AlertOptions(ongoingCountdown = false, ongoingRoom = false)))
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
    private fun now(time: String) = ZonedDateTime.parse("2026-09-14T$time:00+08:00").withZoneSameInstant(schoolZone)
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
