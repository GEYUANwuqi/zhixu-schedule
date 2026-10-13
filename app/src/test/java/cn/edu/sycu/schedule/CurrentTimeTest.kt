package cn.edu.sycu.schedule

import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class CurrentTimeTest {
    private fun row(time: String, times: List<Pair<String, String>> = lessonTimes) =
        currentTimeRow(times, LocalTime.parse(time))

    @Test fun lessonProgressUsesConfiguredDuration() {
        assertEquals(0f, row("08:20")!!, .001f)
        assertEquals(.5f, row("08:42:30")!!, .001f)
        assertEquals(1f, row("09:05")!!, .001f)
        val custom = listOf("07:00" to "08:00", "08:15" to "08:45")
        assertEquals(.5f, row("07:30", custom)!!, .001f)
        assertEquals(1.5f, row("08:30", custom)!!, .001f)
    }

    @Test fun breaksStayAtSharedBoundaryIncludingLunch() {
        assertEquals(1f, row("09:10")!!, .001f)
        assertEquals(1f, row("09:15")!!, .001f)
        assertEquals(4f, row("12:45")!!, .001f)
    }

    @Test fun hidesOutsideConfiguredDay() {
        assertNull(row("08:19"))
        assertEquals(10f, row("19:40")!!, .001f)
        assertNull(row("19:41"))
        assertNull(row("12:00", emptyList()))
    }

    @Test fun missingAndInvalidPeriodsKeepTheirGridSlotsWithoutCrashing() {
        val custom = listOf("" to "", "08:00" to "09:00", "bad" to "bad", "10:00" to "11:00", "12:00" to "12:00")
        assertNull(row("07:59", custom))
        assertEquals(1.5f, row("08:30", custom)!!, .001f)
        assertEquals(2.5f, row("09:30", custom)!!, .001f)
        assertEquals(3.5f, row("10:30", custom)!!, .001f)
        assertNull(row("12:00", custom))
    }

    @Test fun hidesDuringScheduledClassesAndReturnsAtBreak() {
        fun marker(time: String) = currentTimeRow(lessonTimes, LocalTime.parse(time), setOf(1, 2))
        assertNull(marker("08:20"))
        assertNull(marker("08:42"))
        assertEquals(1f, marker("09:05")!!, .001f)
        assertEquals(1f, marker("09:10")!!, .001f)
        assertNull(marker("09:15"))
        assertEquals(2f, marker("10:00")!!, .001f)
        assertEquals(2.5f, marker("10:42:30")!!, .001f)
    }

    @Test fun customBackToBackClassesHideAtNextStart() {
        val times = listOf("07:00" to "08:00", "08:00" to "09:00")
        assertNull(currentTimeRow(times, LocalTime.parse("08:00"), setOf(2)))
        assertEquals(1f, currentTimeRow(times, LocalTime.parse("08:00"), setOf(1))!!, .001f)
    }
}
