package cn.edu.sycu.schedule

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = Application::class)
class CourseAlertsTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private val table = Timetable(id = "table", start = "2026-08-24")
    private val course = Course(id = "math", timetableId = table.id, name = "数学", periods = "3,4", weeks = "4")
    private fun now(time: String) = ZonedDateTime.parse("2026-09-14T$time:00+08:00")

    @Before fun setup() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("schedule.db")
        listOf("display", "course-alerts", "alert-delivery", "holidays").forEach { context.deleteSharedPreferences(it) }
        BackupStore.restoring = false
        assertTrue(PrivacyConsent.accept(context))
        manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        val db = ScheduleDb.open(context)
        try { db.dao().save(table); db.dao().save(course) }
        finally { db.close() }
    }

    @Test fun newSwitchDefaultsOffAndKeepsExistingPreClassSettings() {
        context.getSharedPreferences("course-alerts", 0).edit().putBoolean("reminders", true).putInt("minutes", 37).commit()
        val prefs = AlertPreferences(context)
        val previous = prefs.read()
        assertTrue(previous.reminders)
        assertFalse(previous.endReminders)
        prefs.save(previous.copy(endReminders = true))
        assertEquals(previous.copy(endReminders = true), AlertPreferences(context).read())
    }

    @Test fun winterChangeUpdatesOngoingNotificationAndWidgetAndCanBeReverted() = runBlocking {
        val db = ScheduleDb.open(context)
        try {
            db.dao().save(course.copy(periods = "5,6"))
            AlertPreferences(context).save(AlertOptions(ongoing = true))
            fun text() = manager.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            CourseAlerts.refresh(context, now("13:10"))
            assertTrue(text().startsWith("距上课 20 分钟"))
            db.setWinterSchedule(context, table, true)
            CourseAlerts.refresh(context, now("13:10"))
            assertTrue(text().startsWith("距课间 35 分钟 · 13:00–14:40"))
            val offset = java.time.temporal.ChronoUnit.DAYS.between(today(), now("13:10").toLocalDate()).toInt()
            context.getSharedPreferences("widget-days", 0).edit().putInt("offset", offset).commit()
            val widget = TodayFactory(context)
            widget.onDataSetChanged()
            assertEquals(1, widget.getCount())
            val row = widget.getViewAt(0)!!.apply(context, android.widget.FrameLayout(context))
            val widgetTime = row.findViewById<android.widget.TextView>(R.id.widget_time).text.toString()
            assertTrue(widgetTime.contains("13:00") && widgetTime.contains("14:40"))
            db.setWinterSchedule(context, table.copy(winterSchedule = true), false)
            CourseAlerts.refresh(context, now("13:10"))
            assertTrue(text().startsWith("距上课 20 分钟"))
        } finally { db.close() }
    }

    @Test fun refreshPostsBreakAndFinalRemindersOnlyOnceAndCancelsWhenDisabled() = runBlocking {
        AlertPreferences(context).save(AlertOptions(endReminders = true))
        CourseAlerts.refresh(context, now("10:20"))
        assertTrue(manager.activeNotifications.isEmpty())
        CourseAlerts.refresh(context, now("11:05"))
        val first = manager.activeNotifications.single()
        assertEquals("下课提醒 · 课间休息", first.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        // Dismissing a delivered notification must not repost it at the next refresh.
        manager.cancel(first.tag, first.id)
        CourseAlerts.refresh(context, now("11:06"))
        assertTrue(manager.activeNotifications.isEmpty())
        CourseAlerts.refresh(context, now("12:00"))
        assertEquals("下课提醒 · 课程结束", manager.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(2, context.getSharedPreferences("alert-delivery", 0).getStringSet("sent", emptySet())!!.size)
        AlertPreferences(context).save(AlertOptions())
        CourseAlerts.refresh(context, now("12:01"))
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun ongoingNotificationChangesToBreakAndResumesClass() = runBlocking {
        AlertPreferences(context).save(AlertOptions(ongoing = true))
        fun title() = manager.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        fun text() = manager.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        CourseAlerts.refresh(context, now("10:20"))
        assertEquals("正在上课 · 数学", title())
        assertTrue(text().startsWith("距课间 45 分钟"))
        CourseAlerts.refresh(context, now("11:05"))
        assertEquals("课间时间", title())
        assertTrue(text().startsWith("距上课 10 分钟"))
        CourseAlerts.refresh(context, now("11:15"))
        assertEquals("正在上课 · 数学", title())
        assertTrue(text().startsWith("距下课 45 分钟"))
        CourseAlerts.refresh(context, now("12:00"))
        assertEquals("今日课程已结束", title())
    }

    @Test fun turningOffPreClassRemindersKeepsEndRemindersAndMutingCancelsBoth() = runBlocking {
        val prefs = AlertPreferences(context)
        prefs.save(AlertOptions(reminders = true, endReminders = true))
        CourseAlerts.refresh(context, now("10:10"))
        assertEquals("上课提醒", manager.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        prefs.save(prefs.read().copy(reminders = false))
        CourseAlerts.refresh(context, now("11:05"))
        assertEquals("下课提醒 · 课间休息", manager.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        prefs.saveMutedNames(table.id, setOf("数学"))
        CourseAlerts.refresh(context, now("11:06"))
        assertTrue(manager.activeNotifications.isEmpty())
        CourseAlerts.refresh(context, now("12:00"))
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
