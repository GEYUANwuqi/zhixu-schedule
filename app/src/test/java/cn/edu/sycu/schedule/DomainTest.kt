package cn.edu.sycu.schedule

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    @Test
    fun compactWeekLabelsKeepMeaning() {
        assertEquals("第4周", weekLabel(listOf(4)))
        assertEquals("第1–16周", weekLabel((1..16).toList()))
        assertEquals("第1–5周·单", weekLabel(listOf(1, 3, 5)))
        assertEquals("第1–3、6周", weekLabel(listOf(1, 2, 3, 6)))
    }

    @Test
    fun fourthMondayIsSeptember14() {
        val t = Timetable(start = "2026-08-24")
        val c = Course(timetableId = t.id, name = "示例课程", weekday = 1, periods = "5-6", weeks = "4")
        val o = occurrences(t, c).single()
        assertEquals(LocalDate.of(2026, 9, 14), o.date)
        assertEquals("13:30", o.start.toLocalTime().toString())
        assertEquals("15:10", o.end.toLocalTime().toString())
    }

    @Test
    fun nonContiguousPeriodsNeverFillGaps() {
        val t = Timetable(start = "2026-08-24")
        val c = Course(timetableId = t.id, name = "示例", periods = "1-2,5-6", weeks = "4")
        assertEquals(2, occurrences(t, c).size)
    }

    @Test
    fun beforeSemesterIsNotFirstWeek() {
        assertEquals(0, weekOn(Timetable(start = "2026-08-24"), LocalDate.of(2026, 8, 23)))
    }

    @Test
    fun rooms() {
        assertEquals("B233(白宫)", roomLabel("B233"))
        assertEquals("实验中心", roomLabel("实验中心"))
        assertEquals("X101(西山会所)", roomLabel("X101"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidRangesFail() {
        numbers("5-2", 30)
    }

    @Test
    fun oddWeeksArePreserved() {
        assertEquals(listOf(1, 3, 5, 7), numbers("1,3,5,7", 64))
    }

    @Test
    fun customStartKeepsMondayColumnsAndWeeksConsistent() {
        val t = Timetable(start = "2026-08-26")
        assertEquals(LocalDate.of(2026, 8, 24), firstWeekMonday(t))
        assertEquals(2, weekOn(t, LocalDate.of(2026, 8, 31)))
    }

    @Test
    fun optionalCardFieldsAndHalfWidthRoom() {
        val c = Course(timetableId = "test", room = "B101", teacher = "小明")
        assertEquals("B101(白宫)·小明", courseLocation(c))
        assertEquals("B101(白宫)", courseLocation(c, CardDisplay(teacher = false)))
        assertEquals("小明", courseLocation(c, CardDisplay(room = false)))
        assertEquals("", courseLocation(c, CardDisplay(room = false, teacher = false)))
        assertEquals("X101(西山会所)", roomLabel("X101（西山会所2楼）"))
    }

    @Test
    fun widgetOnlyUsesTodayAndCurrentTable() {
        val t = Timetable(id = "test", start = "2026-08-24")
        val c = Course(timetableId = t.id, name = "今天", weekday = 7, weeks = "3", periods = "5-6")
        val rows =
            listOf(
                c,
                c.copy(deleted = true),
                c.copy(timetableId = "other"),
                c.copy(weekday = 1),
                c.copy(weeks = "4"),
            )
        assertEquals(
            listOf("今天"),
            todayLessons(t, rows, LocalDate.of(2026, 9, 13)).map { it.course.name },
        )
        assertTrue(todayLessons(t, rows, LocalDate.of(2026, 8, 23)).isEmpty())
    }

    @Test
    fun centerCropMatchesContentScaleCrop() {
        // Too wide for the target shape: the sides are dropped, top and bottom are kept.
        val wide = centerCrop(1000f, 500f, 0.5f)
        assertEquals(375f, wide.left)
        assertEquals(625f, wide.right)
        assertEquals(0f, wide.top)
        assertEquals(500f, wide.bottom)

        // Too tall: the top and bottom are dropped instead.
        val tall = centerCrop(500f, 1000f, 1f)
        assertEquals(0f, tall.left)
        assertEquals(500f, tall.right)
        assertEquals(250f, tall.top)
        assertEquals(750f, tall.bottom)

        val exact = centerCrop(300f, 600f, 0.5f)
        assertEquals(300f, exact.width)
        assertEquals(600f, exact.height)
    }

    @Test
    fun widgetCellsAreWiderThanTheyAreTall() {
        // Android's widget sizing table for a Pixel 4 in portrait: (73n − 16) × (118m − 16) dp.
        assertEquals(203f, WidgetGeometry.widthDp(3))
        assertEquals(276f, WidgetGeometry.widthDp(4))
        assertEquals(338f, WidgetGeometry.heightDp(3))
        assertTrue(WidgetGeometry.heightDp(3) > WidgetGeometry.widthDp(3))

        // The 3×3 preview box is the centred 3/4 slice of the 4×3 box, so both keep the same height.
        val large = WidgetGeometry.widthDp(4) / WidgetGeometry.heightDp(3)
        val small = WidgetGeometry.widthDp(3) / WidgetGeometry.heightDp(3)
        assertEquals(WidgetGeometry.widthDp(3) / WidgetGeometry.widthDp(4), small / large, 0.0001f)
    }
}
