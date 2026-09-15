package cn.edu.sycu.schedule

import org.junit.Assert.*
import org.junit.Test

class RulesTest {
    private val table = Timetable(weekCount = 20)
    private val course = Course(timetableId = table.id, name = "乒乓球", teacher = "老师", room = "G411", note = "原备注", sourceId = "source")
    @Test fun exactMatchChangesOnlySpecifiedFields() {
        val rule = CourseRule("教室", "G411", room = "建工4楼")
        assertEquals(course.copy(room = "建工4楼"), applyCourseRules(course, listOf(rule), table))
        assertEquals(course, rule.copy(matchValue = "G41").applyTo(course))
        assertEquals(course, rule.copy(matchValue = " G411").applyTo(course))
        assertEquals(course, rule.copy(matchValue = "g411").applyTo(course))
    }
    @Test fun rulesMatchOriginalAndLaterFilledFieldsWin() {
        val rules = listOf(CourseRule("课程名", "乒乓球", name = "体育", weekday = 4), CourseRule("课程名", "体育", room = "错误"), CourseRule("课程名", "乒乓球", note = "带球拍"))
        assertEquals(course.copy(name = "体育", weekday = 4, note = "带球拍"), applyCourseRules(course, rules, table))
    }
    @Test fun encodingPreservesRulesAndOrder() {
        val rules = listOf(CourseRule("教师", "老师", room = "A320"), CourseRule("备注", "原备注", note = "新备注"))
        assertEquals(rules, RuleCodec.decode(RuleCodec.encode(rules)))
    }
    @Test fun invalidRuleOrResultRejected() {
        assertThrows(IllegalArgumentException::class.java) { CourseRule("课程名", "乒乓球").validate() }
        assertThrows(IllegalArgumentException::class.java) { CourseRule("课程名", "乒乓球", weekday = 9).validate() }
        assertThrows(IllegalArgumentException::class.java) { applyCourseRules(course, listOf(CourseRule("课程名", "乒乓球", weeks = "21")), table) }
        assertThrows(IllegalArgumentException::class.java) { applyCourseRules(course, listOf(CourseRule("课程名", "乒乓球", periods = "30")), table) }
        assertFalse(CourseRule("未知", "", room = "A").matches(course))
    }
    @Test fun themeTintKeepsOpaqueColorAndWhiteEndpoint() {
        assertEquals(0xff123456.toInt(), tintColor(0xff123456.toInt(), 0f))
        assertEquals(0xffffffff.toInt(), tintColor(0xff123456.toInt(), 1f))
    }
}
