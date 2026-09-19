package cn.edu.sycu.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class CourseInteractionTest {
    private val t = Timetable(start = "2026-08-24")
    @Test fun moveChangesEveryWeekAndPreservesIdentityAndDuration() {
        val c = Course(timetableId = t.id, name = "数学", weeks = "1-5", periods = "3,4", sourceId = "source")
        val result = moveLesson(t, c, 4, listOf(3,4), 3, 7)
        assertEquals(c.weeks, result.weeks)
        assertFalse(result.edited)
        assertEquals(listOf(7,8), result.periodList())
        assertEquals(c.id, result.id)
        assertEquals(3, result.weekday)
        assertEquals(c.sourceId, result.sourceId)
        assertThrows(IllegalArgumentException::class.java) { moveLesson(t, c, 4, listOf(3,4), 3, 10) }
    }
    @Test fun separateBlocksShiftTogetherWithoutChangingGaps() {
        val c = Course(timetableId = t.id, weeks = "4", periods = "1,2,5,6", sourceId = "source")
        val result = moveLesson(t, c, 4, listOf(5,6), 2, 7)
        assertFalse(result.deleted)
        assertEquals(listOf(3,4,7,8), result.periodList())
        assertEquals(c.weeks, result.weeks)
        assertEquals(c, moveLesson(t, c, 4, listOf(5,6), 1, 5))
    }
    @Test fun phaseIncludesBreakButEndsAtFinalBell() {
        fun phase(time: String) = coursePhase(t, 4, 1, listOf(3,4), ZonedDateTime.parse("2026-09-14T${time}:00+08:00"))
        assertEquals(LessonPhase.FUTURE, phase("10:19"))
        assertEquals(LessonPhase.CURRENT, phase("10:20"))
        assertEquals(LessonPhase.CURRENT, phase("11:10"))
        assertEquals(LessonPhase.PAST, phase("12:00"))
    }
}
