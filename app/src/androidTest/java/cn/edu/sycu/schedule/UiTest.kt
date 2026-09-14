package cn.edu.sycu.schedule

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun acceptPrivacyWhenNeeded() {
        if (compose.onAllNodesWithText("隐私协议").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText(PRIVACY_TEXT).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 100000f) }
            compose.waitUntil(timeoutMillis = 8000) {
                compose.onAllNodesWithText("我已完整阅读并同意").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("我已完整阅读并同意").assertIsEnabled().performClick()
        }
    }

    @Test
    fun createOfflineCourseAndOpenAnimatedDetails() {
        compose.onNodeWithText("创建第一张课表").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithText("＋ 课程").performClick()
        compose.onNodeWithText("课程名").performTextInput("示例数学")
        compose.onNodeWithText("教师").performTextInput("示例教师")
        compose.onNodeWithText("教室，例如 B233").performTextInput("B233")
        // Keep the demo course off today, so the widget preview is empty on any weekday.
        val otherDay = if (today().dayOfWeek.value == 1) "2" else "1"
        compose.onNodeWithText("星期 1–7").performTextClearance()
        compose.onNodeWithText("星期 1–7").performTextInput(otherDay)
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithText("周日").assertIsDisplayed()
        compose.onNodeWithText("19:40").assertIsDisplayed()
        compose.onNodeWithText("示例数学").performClick()
        compose.onAllNodesWithText("B233(白宫)").onLast().assertIsDisplayed()
        compose.onAllNodesWithText("示例教师").onLast().assertIsDisplayed()
        compose.onNodeWithText("编辑").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("导出与日历").performClick()
        compose.onNodeWithText("导出 / 分享 PNG").assertExists()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("卡片显示").performClick()
        compose.onNodeWithText("第几周").performClick()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("卡片显示").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("＋ 课程").assertIsDisplayed()
        compose.onNodeWithText("第1–16周").assertDoesNotExist()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("桌面小组件").performClick()
        compose.onNodeWithText("3×3").assertIsDisplayed()
        compose.onNodeWithText("4×3").performClick()
        compose.onNodeWithText("今天没有课程").assertIsDisplayed()
        compose.onNodeWithText("添加到桌面").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("个性化").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("个性化").performClick()
        compose.onNodeWithText("App 背景").assertIsDisplayed()
        compose.onNodeWithText("小组件背景").assertIsDisplayed()
        compose.onAllNodes(hasText("选择背景图") or hasText("更换背景图")).onFirst()
            .assertIsDisplayed()
        compose.onNodeWithText("App 背景和桌面小组件背景可以分别设置，图片只保存在本机，不会上传。")
            .assertIsDisplayed()
        compose.activityRule.scenario.onActivity {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(
                it, android.content.Intent(it, MainActivity::class.java).putExtra("open_today", true))
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("＋ 课程").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("＋ 课程").assertIsDisplayed()
    }
}
