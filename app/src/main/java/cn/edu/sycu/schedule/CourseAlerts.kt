package cn.edu.sycu.schedule

import android.app.*
import android.content.*
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime

class AlertPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("course-alerts", Context.MODE_PRIVATE)
    fun mutedNames(tableId: String): Set<String> = prefs.getStringSet("muted-names:$tableId", emptySet())!!.toSet()
    fun saveMutedNames(tableId: String, names: Set<String>) { prefs.edit().putStringSet("muted-names:$tableId", names.toSet()).apply() }
fun read() = AlertOptions(prefs.getBoolean("ongoing", false), prefs.getBoolean("reminders", false), prefs.getInt("minutes", 20).takeIf { it in 1..60 } ?: 20, prefs.getBoolean("name", true), prefs.getBoolean("time", true), prefs.getBoolean("room", true), prefs.getBoolean("ongoing_countdown", true), prefs.getBoolean("ongoing_time", true), prefs.getBoolean("ongoing_room", true), prefs.getBoolean("ongoing_teacher", true))
    fun save(value: AlertOptions) { require(value.minutes in 1..60); prefs.edit().putBoolean("ongoing", value.ongoing).putBoolean("reminders", value.reminders).putInt("minutes", value.minutes).putBoolean("name", value.name).putBoolean("time", value.time).putBoolean("room", value.room).putBoolean("ongoing_countdown", value.ongoingCountdown).putBoolean("ongoing_time", value.ongoingTime).putBoolean("ongoing_room", value.ongoingRoom).putBoolean("ongoing_teacher", value.ongoingTeacher).apply() }
}

object CourseAlerts {
    const val STATUS_CHANNEL = "today-schedule"
    const val REMINDER_CHANNEL = "course-reminders"
    private const val STATUS_ID = 4200
    private val lock = Mutex()
    fun request(context: Context) {
        if (PrivacyConsent.accepted(context)) context.sendBroadcast(Intent(context, CourseAlertReceiver::class.java).setAction("cn.edu.sycu.schedule.ALERT_REFRESH"))
    }
    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(STATUS_CHANNEL, "今日课程安排", NotificationManager.IMPORTANCE_LOW).apply { description = "持续显示正在上课、下一节课或今日空闲状态"; setShowBadge(false) })
        manager.createNotificationChannel(NotificationChannel(REMINDER_CHANNEL, "上课前提醒", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "按所选提前时间提醒当天课程" })
    }
    suspend fun refresh(context: Context) = lock.withLock {
        if (BackupStore.restoring) return@withLock
        if (!PrivacyConsent.accepted(context)) return@withLock
        channels(context)
        val options = AlertPreferences(context).read()
        val manager = context.getSystemService(NotificationManager::class.java)
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, 4201, Intent(context, CourseAlertReceiver::class.java).setAction("cn.edu.sycu.schedule.ALERT_TICK"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.cancel(pending)
        val minuteUpdate = PendingIntent.getBroadcast(context, 4202, Intent(context, CourseAlertReceiver::class.java).setAction("cn.edu.sycu.schedule.ALERT_MINUTE"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.cancel(minuteUpdate)
        val state = context.getSharedPreferences("alert-delivery", Context.MODE_PRIVATE)
        val previous = state.getStringSet("posted", emptySet())!!.toSet()
        fun cancelReminders() { previous.forEach { manager.cancel(it, 1) }; state.edit().putStringSet("posted", emptySet()).apply() }
        if (!options.ongoing) manager.cancel(STATUS_ID)
        if (!options.reminders) cancelReminders()
        if (!options.ongoing && !options.reminders) return@withLock
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) { manager.cancel(STATUS_ID); cancelReminders(); return@withLock }
        val db = ScheduleDb.open(context)
        val table: Timetable?
        val courses: List<Course>
        try {
            val tables = db.dao().timetables().first()
            table = tables.find { it.id == SchedulePreferences(context).activeId } ?: tables.firstOrNull()
            courses = table?.let { db.dao().allCourses(it.id) + MakeupStore.courses(context, it) } ?: emptyList()
        } finally { db.close() }
        val now = ZonedDateTime.now(schoolZone)
        val muted = table?.let { AlertPreferences(context).mutedNames(it.id) } ?: emptySet()
        val plan = alertPlan(table, courses, options, now, muted)
        val open = PendingIntent.getActivity(context, 4200, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open_today", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun builder(channel: String) = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_course_notification).setContentIntent(open).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (options.ongoing) {
            val current = plan.today.firstOrNull { !now.isBefore(it.start) && now.isBefore(it.end) }
            val next = plan.today.firstOrNull { it.start.isAfter(now) }
            val item = current ?: next
            val title = when { table == null -> "今日课表 · 尚无课表"; current != null -> "正在上课 · ${current.course.name}"; next != null -> "下一节 · ${next.course.name}"; plan.today.isEmpty() -> "今日没有课程"; else -> "今日课程已结束" }
            val lines = item?.let { ongoingLines(it, current != null, now, options) } ?: ("点击查看课表" to "")
            val content = android.widget.RemoteViews(context.packageName, R.layout.course_notification).apply {
                setTextViewText(R.id.notification_title, title)
                setTextViewText(R.id.notification_time, lines.first)
                setTextViewText(R.id.notification_location, lines.second)
                setViewVisibility(R.id.notification_time, if (lines.first.isBlank()) android.view.View.GONE else android.view.View.VISIBLE)
                setViewVisibility(R.id.notification_location, if (lines.second.isBlank()) android.view.View.GONE else android.view.View.VISIBLE)
            }
            val notification = builder(STATUS_CHANNEL).setContentTitle(title).setContentText(listOf(lines.first, lines.second).filter { it.isNotBlank() }.joinToString("\n"))
                .setStyle(NotificationCompat.DecoratedCustomViewStyle()).setCustomContentView(content).setCustomBigContentView(content)
                .setOngoing(true).setOnlyAlertOnce(true)
            notification.setUsesChronometer(false).setShowWhen(false)
            try { manager.notify(STATUS_ID, notification.build()) } catch (_: SecurityException) { }
            if (item != null && options.ongoingCountdown && manager.getNotificationChannel(STATUS_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE) {
                val at = now.withSecond(0).withNano(0).plusMinutes(1).toInstant().toEpochMilli()
                // Display refreshes do not wake the device or consume idle reminder alarms.
                try {
                    if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExact(AlarmManager.RTC, at, minuteUpdate)
                    else alarms.set(AlarmManager.RTC, at, minuteUpdate)
                } catch (_: SecurityException) { alarms.set(AlarmManager.RTC, at, minuteUpdate) }
            }
        }
        val valid = plan.today.map(::occurrenceKey).toSet()
        val allowed = plan.today.filter { it.course.name !in muted }.map(::occurrenceKey).toSet()
        val posted = (if (options.reminders) previous.intersect(allowed) else emptySet()).toMutableSet()
        previous.filter { it !in posted }.forEach { manager.cancel(it, 1) }
        val sent = state.getStringSet("sent", emptySet())!!.intersect(valid).toMutableSet()
        if (options.reminders && manager.getNotificationChannel(REMINDER_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE) {
            plan.due.forEach { item ->
                val key = occurrenceKey(item)
                if (key !in sent) {
                    val text = reminderText(item, options)
                    try {
                        manager.notify(key, 1, builder(REMINDER_CHANNEL).setContentTitle("上课提醒").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).build())
                        sent.add(key); posted.add(key)
                    } catch (_: SecurityException) { }
                }
            }
        }
        state.edit().putStringSet("sent", sent).putStringSet("posted", posted).apply()
        val at = plan.next.toInstant().toEpochMilli()
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (_: SecurityException) { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
    }
}

class CourseAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!PrivacyConsent.accepted(context)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { CourseAlerts.refresh(context.applicationContext) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                // A transient database/platform failure must not crash a background receiver.
                val retry = PendingIntent.getBroadcast(context, 4201, Intent(context, CourseAlertReceiver::class.java).setAction("cn.edu.sycu.schedule.ALERT_TICK"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60_000, retry)
            }
            finally { pending.finish() }
        }
    }
}
