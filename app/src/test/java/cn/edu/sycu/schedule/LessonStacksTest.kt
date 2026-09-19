package cn.edu.sycu.schedule

import org.junit.Assert.*
import org.junit.Test

class LessonStacksTest {
    private fun course(id: String, periods: String, day: Int = 1) = Course(id = id, timetableId = "t", name = id, periods = periods, weekday = day)
    @Test fun exportExcludesMakeupDeletedAndOtherTables() {
        val table = Timetable(id = "t")
        val normal = course("normal", "5,6")
        assertEquals(listOf(normal), PngExport.regularCourses(table, listOf(normal, normal.copy(deleted = true), normal.copy(timetableId = "other"), course("makeup:one:2026-09-21", "5,6"))))
    }
    @Test fun longerCourseWinsEvenWhenAddedLater() {
        val stack = lessonStacks(listOf(course("语文", "1"), course("乒乓球", "1,2"))).single()
        assertEquals("乒乓球", stack.front.course.name)
        assertEquals(2, stack.blocks.size)
    }
    @Test fun equalDurationPreservesInsertionAndHiddenMakeupMarksStack() {
        val original = course("高数", "5,6")
        val makeup = course("makeup:one:2026-09-21", "5,6")
        val stack = lessonStacks(listOf(original, makeup)).single()
        assertEquals(original, stack.front.course)
        assertTrue(stack.hasMakeup)
        assertEquals(listOf(original, makeup), stack.blocks.map { it.course })
    }
    @Test fun transitiveOverlapIsOneStackButDifferentDaysAndGapsAreSeparate() {
        val stacks = lessonStacks(listOf(course("a", "1,2"), course("b", "2,3"), course("c", "3,4"), course("d", "5,7"), course("e", "1,2", 2)))
        assertEquals(4, stacks.size)
        assertEquals(3, stacks.first().blocks.size)
        assertEquals(1, stacks.first().start)
        assertEquals(4, stacks.first().end)
        assertEquals("1,2", stacks.first().front.course.periods)
    }
}
