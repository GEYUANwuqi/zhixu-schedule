package cn.edu.sycu.schedule

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PreviewInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val table = Timetable(id = "t", start = today().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toString())
    private val courses = listOf(
        Course(id = "a", timetableId = "t", name = "数学", periods = "1,2", teacher = "隐藏教师", room = "B101"),
        Course(id = "b", timetableId = "t", name = "英语", periods = "1,2"),
        Course(id = "c", timetableId = "t", name = "体育", periods = "5,6"),
    )
    @Test fun physicalWeekdayTapSelectsAllThenCancelsAndInheritsDisplay() {
        SchedulePreferences(RuntimeEnvironment.getApplication()).save(CardDisplay(teacher = false, room = false, weeks = false))
        compose.setContent { MaterialTheme { MakeupPreview(table, courses) {} } }
        compose.onNodeWithText("隐藏教师").assertDoesNotExist()
        compose.onNodeWithText("B101(白宫)").assertDoesNotExist()
        compose.onNodeWithText("周一").performTouchInput { click() }
        compose.onNodeWithText("已选 3 节", substring = true).assertExists()
        compose.onNodeWithText("周一").performTouchInput { click() }
        compose.onNodeWithText("请选择来源日期").assertExists()
        compose.onNodeWithText("体育").performClick()
        compose.onNodeWithText("已选 1 节", substring = true).assertExists()
        compose.onNodeWithText("周一").performTouchInput { click() }
        compose.onNodeWithText("已选 3 节", substring = true).assertExists()
    }
    @Test fun detailSwipesBothWaysDespiteScrollableContent() {
        compose.setContent { MaterialTheme { CourseDetailStack(lessonStacks(courses.take(2)).single().blocks, table, {}, {}, {}) } }
        compose.onNodeWithText("1/2 · 上下滑动切换课程").assertExists()
        // Also exercise the original problem area: the scrollable card body,
        // not just the fixed title handle.
        compose.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.onNodeWithText("2/2 · 上下滑动切换课程").assertExists()
        compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithText("1/2 · 上下滑动切换课程").assertExists()
        compose.onNodeWithTag("detail-swipe").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.onNodeWithText("2/2 · 上下滑动切换课程").assertExists()
        compose.onNodeWithTag("detail-swipe").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithText("1/2 · 上下滑动切换课程").assertExists()
    }
    @Test fun changingWeekUsesDisplayedDateAndCoursesInBothSteps() {
        val monday = java.time.LocalDate.parse(table.start)
        val thisTuesday = monday.plusDays(1).toString()
        val nextTuesday = monday.plusDays(8).toString()
        val rows = listOf(courses.first().copy(weekday = 2, weeks = "1", name = "本周课程"), courses.first().copy(id = "next", weekday = 2, weeks = "2", name = "下周课程"))
        compose.setContent { MaterialTheme { MakeupPreview(table, rows) {} } }
        compose.onNodeWithText("周二").performTouchInput { click() }
        compose.onNodeWithText("$thisTuesday · 已选 1 节").assertExists()
        compose.onNodeWithText("下一周").performClick()
        compose.onNodeWithText("周二").performTouchInput { click() }
        compose.onNodeWithText("$nextTuesday · 已选 1 节").assertExists()
        compose.onNodeWithText("确认来源，选择目标日期").performClick()
        compose.onNodeWithText("上一周").performClick()
        compose.onNodeWithText("周二").performTouchInput { click() }
        compose.onNodeWithText("$nextTuesday → $thisTuesday", substring = true).assertExists()
        compose.onNodeWithText("下一周").performClick()
        compose.onNodeWithText("周二").performTouchInput { click() }
        compose.onNodeWithText("$nextTuesday → $nextTuesday", substring = true).assertExists()
        compose.onNodeWithText("确认添加补课").performClick()
        compose.runOnIdle {
            val saved = MakeupStore.read(RuntimeEnvironment.getApplication()).single()
            org.junit.Assert.assertEquals(nextTuesday, saved.date)
            org.junit.Assert.assertEquals("下周课程", saved.name)
        }
    }
}
