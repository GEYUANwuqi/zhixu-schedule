package cn.edu.sycu.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

class MakeupTest {
    @Test fun wholeDayToggleIncludesHiddenBlocksAndReplacesOtherDay() {
        val t = Timetable(id = "t", start = "2026-08-24")
        val a = Course(id = "a", timetableId = t.id, periods = "1,2,5,6", weeks = "1")
        val b = a.copy(id = "b", periods = "1,2")
        val other = a.copy(id = "other", weekday = 2)
        val shown = makeupPreviewCourses(t, listOf(a, b, other), 1)
        val all = toggleMakeupDay(shown, 1, emptySet())
        assertEquals(3, all.size)
        assertEquals(emptySet<String>(), toggleMakeupDay(shown, 1, all))
        assertEquals(all, toggleMakeupDay(shown, 1, setOf(all.first())))
        val next = toggleMakeupDay(shown, 2, all)
        assertEquals(2, next.size)
        assertTrue(next.intersect(all).isEmpty())
        assertTrue(toggleMakeupDay(shown, 7, next).isEmpty())
    }
    @Test fun chooseWholeDayOrSeparateBlockWithoutCopyingOtherWeeks() {
        val t = Timetable(id = "table", start = "2026-08-24")
        val c = Course(id = "source", timetableId = t.id, name = "数学", weekday = 1, periods = "1,2,5,6", weeks = "5")
        val english = c.copy(id = "english", name = "英语", periods = "3,4")
        val preview = makeupPreviewCourses(t, listOf(c, english, c.copy(id = "deleted", deleted = true), c.copy(id = "otherweek", weeks = "4")), 5)
        assertEquals(3, preview.size)
        assertEquals(setOf("1,2", "3,4", "5,6"), preview.map { it.periods }.toSet())
        val wholeDay = makeupsFromSelection(t, preview, LocalDate.parse("2026-09-20"))
        assertEquals(3, wholeDay.size)
        assertTrue(wholeDay.all { occurrences(t, it.course(t)).single().date == LocalDate.parse("2026-09-20") })
        assertEquals(LocalDate.parse("2026-09-21"), occurrences(t, c).first().date)
        val one = makeupsFromSelection(t, listOf(preview.first()), LocalDate.parse("2026-09-20"), "7,8").single()
        assertEquals("7,8", one.periods)
        assertEquals("1,2,5,6", c.periods)
        assertThrows(IllegalArgumentException::class.java) { makeupsFromSelection(t, emptyList(), LocalDate.parse("2026-09-20")) }
    }
    private val table = Timetable(id = "table", start = "2026-08-24", weekCount = 20)
    private val makeup = Makeup(id = "one", tableId = table.id, date = "2026-09-19", name = "数学", teacher = "教师", room = "B101", periods = "3,4")
    @Test fun overlayOccursOnceAndLeavesOriginalCourseUntouched() {
        val original = Course(timetableId = table.id, name = "数学", weekday = 1, periods = "3,4", weeks = "1-20")
        val c = makeup.course(table)
        assertTrue(c.isMakeup)
        val rows = occurrences(table, c)
        assertEquals(1, rows.size)
        assertEquals(LocalDate.parse("2026-09-19"), rows.single().date)
        assertEquals("10:20", rows.single().start.toLocalTime().toString())
        assertEquals(4, rows.single().week)
        assertEquals(20, occurrences(table, original).size)
        assertEquals(1, todayLessons(table, listOf(original, c), LocalDate.parse(makeup.date)).size)
        assertTrue(todayLessons(table, listOf(c), LocalDate.parse(makeup.date).plusWeeks(1)).isEmpty())
    }
    @Test fun holidaysAndChangesToTermStartDoNotMoveTheChosenDate() {
        val holiday = makeup.copy(date = "2027-02-01")
        assertEquals(LocalDate.parse(holiday.date), occurrences(table, holiday.course(table)).single().date)
        val changed = table.copy(start = "2026-09-01")
        assertEquals(LocalDate.parse(makeup.date), occurrences(changed, makeup.course(changed)).single().date)
        val options = AlertOptions(reminders = true)
        assertEquals(1, alertPlan(table, listOf(holiday.course(table)), options, ZonedDateTime.parse("2027-02-01T10:00:00+08:00")).due.size)
    }
    @Test fun serializationValidationAndIndependentIdentities() {
        assertEquals(listOf(makeup), MakeupCodec.decode(MakeupCodec.encode(listOf(makeup))))
        assertThrows(IllegalArgumentException::class.java) { MakeupCodec.encode(listOf(makeup, makeup)) }
        assertThrows(Exception::class.java) { MakeupCodec.encode(listOf(makeup.copy(date = "bad"))) }
        assertThrows(Exception::class.java) { MakeupCodec.encode(listOf(makeup.copy(periods = "0"))) }
        assertThrows(Exception::class.java) { MakeupCodec.encode(listOf(makeup.copy(id = "bad:id"))) }
        assertNotEquals(makeup.course(table).id, makeup.copy(id = "two").course(table).id)
    }
}
