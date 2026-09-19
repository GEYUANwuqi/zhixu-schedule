package cn.edu.sycu.schedule

import org.junit.Assert.*
import org.junit.Test

class RuleTransferTest {
    @Test fun roundTripKeepsExactValuesOrderAndDefaults() {
        val rules = listOf(CourseRule("教室", " G411", room = "建工4楼"), CourseRule("课程名", "乒乓球", weekday = 3, periods = "7-8", note = "带球拍"))
        assertEquals(rules, RuleTransfer.decode("\uFEFF" + RuleTransfer.encode(rules)))
        assertEquals(emptyList<CourseRule>(), RuleTransfer.decode(RuleTransfer.encode(emptyList())))
    }
    @Test fun rejectsWrongTypeVersionAndSize() {
        val text = RuleTransfer.encode(listOf(CourseRule("教室", "G411", room = "A")))
        assertThrows(IllegalArgumentException::class.java) { RuleTransfer.decode(text.replace("\"weekday\": 0", "\"weekday\": 8")) }
        assertThrows(IllegalArgumentException::class.java) { RuleTransfer.decode(text.replace("\"version\": 1", "\"version\": 2")) }
        assertThrows(IllegalArgumentException::class.java) { RuleTransfer.read(ByteArray(RuleTransfer.LIMIT + 1).inputStream()) }
    }
}
