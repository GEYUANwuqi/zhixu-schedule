package cn.edu.sycu.schedule

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GridTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sundayIsLastAndHighlighted() {
        val t = Timetable(start = "2026-08-24")
        val courses =
            listOf(
                Course(timetableId = t.id, name = "周日示例课", weekday = 7, weeks = "3"),
                Course(timetableId = t.id, name = "周一示例课", weekday = 1, weeks = "3"),
            )
        compose.setContent {
            MaterialTheme {
                ScheduleGrid(
                    t,
                    courses,
                    3,
                    Modifier.size(360.dp, 560.dp),
                    LocalDate.of(2026, 9, 13),
                ) {}
            }
        }
        val labels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val positions =
            labels.map { label ->
                compose
                    .onNodeWithText(label)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
                    .left
            }
        assertTrue(positions.zipWithNext().all { (a, b) -> a < b })
        assertTrue(
            compose.onNodeWithText("周日示例课").fetchSemanticsNode().boundsInRoot.left >
                compose.onNodeWithText("周一示例课").fetchSemanticsNode().boundsInRoot.left
        )
        compose.onNodeWithText("19:40").assertIsDisplayed()
        compose.onNodeWithContentDescription("星期7").assertIsSelected()
        compose.onNodeWithContentDescription("星期1").assertIsNotSelected()
    }

    @Test
    fun mondayStartUsesNormalWeekOrder() {
        val t = Timetable(start = "2026-08-24")
        compose.setContent {
            MaterialTheme {
                ScheduleGrid(
                    t,
                    emptyList(),
                    4,
                    Modifier.size(360.dp, 560.dp),
                    LocalDate.of(2026, 9, 14),
                ) {}
            }
        }
        assertTrue(
            compose.onNodeWithText("周一").fetchSemanticsNode().boundsInRoot.left <
                compose.onNodeWithText("周日").fetchSemanticsNode().boundsInRoot.left
        )
    }

    @Test
    fun hiddenFieldsAndSwipeDoNotOpenCourse() {
        val t = Timetable(start = "2026-08-24")
        var delta = 0
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                ScheduleGrid(
                    t,
                    listOf(
                        Course(
                            timetableId = t.id,
                            name = "课程",
                            room = "B101",
                            teacher = "小明",
                            weeks = "3",
                        )
                    ),
                    3,
                    Modifier.size(360.dp, 560.dp).testTag("grid"),
                    LocalDate.of(2026, 9, 13),
                    CardDisplay(teacher = false, weeks = false),
                    onWeekSwipe = { delta = it },
                ) {
                    clicks++
                }
            }
        }
        compose.onNodeWithText("B101(白宫)").assertExists()
        compose.onNodeWithText("小明", substring = true).assertDoesNotExist()
        compose.onNodeWithText("第3周").assertDoesNotExist()
        compose.onNodeWithTag("grid").performTouchInput { swipeLeft() }
        compose.runOnIdle {
            assertEquals(1, delta)
            assertEquals(0, clicks)
        }
        compose.onNodeWithTag("grid").performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(-1, delta) }
    }
}
