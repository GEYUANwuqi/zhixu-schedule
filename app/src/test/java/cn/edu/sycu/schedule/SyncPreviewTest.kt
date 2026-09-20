package cn.edu.sycu.schedule

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = Application::class)
class SyncPreviewTest {
    private val table = Timetable(id = "t", start = "2026-08-24")
    private val course = Course(id = "a", timetableId = "t", name = "数学", sourceId = "school", room = "G411")
    @Test fun previewUsesOriginalMatchingAndActualRuleOrder() {
        val rules = listOf(CourseRule("课程名", "数学", name = "高数", teacher = "教师", weekday = 3, periods = "5,6", weeks = "1-8", note = "补充"), CourseRule("课程名", "高数", room = "不应命中"), CourseRule("教室", "G411", room = "建工4楼"))
        val row = previewCourseRules(course, rules, table)
        assertEquals(listOf(0, 2), row.hits.map { it.index })
        assertEquals(applyCourseRules(course, rules, table), row.result)
        assertEquals(7, coursePreviewFields(course).zip(coursePreviewFields(row.result)).count { it.first != it.second })
        assertEquals("G411", row.original.room)
        assertTrue(row.changed)
        assertFalse(previewCourseRules(course, emptyList(), table).changed)
        val noChange = previewCourseRules(course, listOf(CourseRule("课程名", "数学", name = "数学")), table)
        assertFalse(noChange.changed)
        assertEquals(1, noChange.hits.size)
    }
    @Test fun previewAndCancelDoNotWriteAndConfirmationPreservesProtectedCourses() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ScheduleDb::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.dao()
            dao.save(table)
            val edited = course.copy(edited = true, room = "我的教室")
            val manual = course.copy(id = "manual", sourceId = null)
            dao.save(edited); dao.save(manual)
            val before = dao.allCourses(table.id)
            val proposal = SyncProposal(table, table, before, listOf(previewCourseRules(course, listOf(CourseRule("教室", "G411", room = "新教室")), table)))
            assertEquals(before, dao.allCourses(table.id)) // Preparing/dropping this value has no database effects.
            dao.confirmSync(proposal)
            assertEquals(before, dao.allCourses(table.id))
            val newCourse = course.copy(id = "new")
            val newProposal = proposal.copy(rows = listOf(previewCourseRules(newCourse, emptyList(), table)))
            dao.confirmSync(newProposal)
            assertTrue(dao.allCourses(table.id).contains(newCourse))
            try { dao.confirmSync(proposal); fail("stale preview accepted") } catch (_: IllegalArgumentException) { }
            assertTrue(dao.allCourses(table.id).contains(newCourse))
        } finally { db.close() }
    }
    @Test fun newTimetableIsCreatedOnlyOnConfirmAndInvalidRulesAreRejected() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ScheduleDb::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.dao()
            val proposal = SyncProposal(null, table, emptyList(), listOf(previewCourseRules(course, emptyList(), table)))
            assertNull(dao.findTable(table.id))
            assertThrows(IllegalArgumentException::class.java) { previewCourseRules(course, listOf(CourseRule("课程名", "数学", weeks = "64")), table) }
            assertNull(dao.findTable(table.id))
            dao.confirmSync(proposal)
            assertEquals(table, dao.findTable(table.id))
            assertEquals(listOf(course), dao.allCourses(table.id))
        } finally { db.close() }
    }
}
