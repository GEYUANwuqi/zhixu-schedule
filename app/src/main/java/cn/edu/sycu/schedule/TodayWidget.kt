package cn.edu.sycu.schedule

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.*
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

fun todayLessons(t: Timetable, courses: List<Course>, date: LocalDate = today()): List<Occurrence> =
    courses
        .filter { !it.deleted && it.timetableId == t.id }
        .flatMap { runCatching { occurrences(t, it) }.getOrDefault(emptyList()) }
        .filter { it.date == date }
        .sortedBy { it.start }

open class TodayWidget : AppWidgetProvider() {
    /** Provider this receiver refreshes; the 4×3 widget overrides it. */
    protected open val component: Class<*> get() = TodayWidget::class.java

    protected open val layout: Int get() = R.layout.today_widget

    protected open val inlineDate: Boolean get() = false

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        requestRefresh(context)

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        options: Bundle,
    ) = requestRefresh(context)

    override fun onReceive(context: Context, intent: Intent) {
        if (BackupStore.restoring) return
        super.onReceive(context, intent)
        if (intent.action == ACTION_TODAY) {
            val prefs = context.getSharedPreferences("widget-days", Context.MODE_PRIVATE)
            if (prefs.getInt("offset", 0) == 0) return
            prefs.edit().putInt("offset", 0).apply()
        }
        if (intent.action == ACTION_PREV || intent.action == ACTION_NEXT) {
            val delta = if (intent.action == ACTION_PREV) -1 else 1
            val prefs = context.getSharedPreferences("widget-days", Context.MODE_PRIVATE)
            prefs.edit().putInt("offset", prefs.getInt("offset", 0) + delta).apply()
        }
        if (intent.action in WIDGET_ACTIONS) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (intent.action == ACTION_TODAY) {
                        updateComponent(context, TodayWidget::class.java, R.layout.today_widget, false)
                        updateComponent(context, TodayWidgetLarge::class.java, R.layout.today_widget_large, true)
                    } else updateComponent(context, component, layout, inlineDate)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    // The local broadcast gives the receiver a bounded goAsync lifetime.
    private fun requestRefresh(context: Context) {
        context.sendBroadcast(Intent(context, component).setAction(REFRESH_ACTION))
    }

    companion object {
        const val REFRESH_ACTION = "cn.edu.sycu.schedule.REFRESH_WIDGET"
        const val ACTION_PREV = "cn.edu.sycu.schedule.WIDGET_PREV"
        const val ACTION_NEXT = "cn.edu.sycu.schedule.WIDGET_NEXT"
        const val ACTION_TODAY = "cn.edu.sycu.schedule.WIDGET_TODAY"

        private val WIDGET_ACTIONS =
            listOf(
                Intent.ACTION_DATE_CHANGED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                REFRESH_ACTION,
                ACTION_PREV,
                ACTION_NEXT,
                ACTION_TODAY,
            )

        /** Both sizes share the label「知序 · 今日课表」, told apart by size and preview. */
        val providers: List<Class<out TodayWidget>> =
            listOf(TodayWidget::class.java, TodayWidgetLarge::class.java)

        fun pin(context: Context, large: Boolean = false): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            val provider = if (large) TodayWidgetLarge::class.java else TodayWidget::class.java
            return manager.isRequestPinAppWidgetSupported &&
                manager.requestPinAppWidget(ComponentName(context, provider), null, null)
        }

        fun refresh(context: Context) {
            providers.forEach { provider ->
                context.sendBroadcast(Intent(context, provider).setAction(REFRESH_ACTION))
            }
        }

        suspend fun updateComponent(
            context: Context,
            provider: Class<*>,
            layout: Int,
            inlineDate: Boolean,
        ) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, provider))
            if (ids.isEmpty()) return
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val tick = PendingIntent.getBroadcast(context, 901, Intent(context, provider).setAction(REFRESH_ACTION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // Inexact refresh requires no exact-alarm permission; Android may defer it in power saving modes.
            alarm.setAndAllowWhileIdle(android.app.AlarmManager.RTC, (System.currentTimeMillis() / 60000 + 1) * 60000, tick)
            val db = ScheduleDb.open(context)
            try {
                val tables = db.dao().timetables().first()
                val selected = SchedulePreferences(context).activeId
                val table = tables.find { it.id == selected } ?: tables.firstOrNull()
                ids.forEach { id ->
                    val views = RemoteViews(context.packageName, layout)
                    val appearance = SchedulePreferences(context).appearance()
                    listOf(R.id.widget_title, R.id.widget_date, R.id.widget_subtitle, R.id.widget_empty).forEach {
                        views.setTextColor(it, appearance.ink)
                    }
                    views.setInt(R.id.widget_prev, "setColorFilter", appearance.seed)
                    views.setInt(R.id.widget_next, "setColorFilter", appearance.seed)
                    val offset = context.getSharedPreferences("widget-days", Context.MODE_PRIVATE).getInt("offset", 0)
                    val date = today().plusDays(offset.toLong())
                    views.setViewVisibility(R.id.widget_today, if (offset == 0) android.view.View.GONE else android.view.View.VISIBLE)
                    views.setTextColor(R.id.widget_today, appearance.seed)
                    views.setOnClickPendingIntent(R.id.widget_today, PendingIntent.getBroadcast(context, id, Intent(context, provider).setAction(ACTION_TODAY), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    val title = when (offset) {
                        -1 -> "昨天课表"
                        -2 -> "前天课表"
                        1 -> "明天课表"
                        2 -> "后天课表"
                        in Int.MIN_VALUE..-3 -> "以往课表"
                        in 3..Int.MAX_VALUE -> "未来课表"
                        else -> "今日课表"
                    }
                    if (inlineDate) {
                        views.setTextViewText(R.id.widget_title, "$title · $date")
                    } else {
                        views.setTextViewText(R.id.widget_title, title)
                        views.setTextViewText(R.id.widget_date, date.toString())
                    }
                    views.setTextViewText(R.id.widget_subtitle, table?.name ?: "知序")
                    views.setTextViewText(
                        R.id.widget_empty,
                        if (table == null) "尚无课表，点击添加或同步" else "今天是假日哦~好好休息一下吧~",
                    )
                    val open =
                        PendingIntent.getActivity(
                            context,
                            0,
                            Intent(context, MainActivity::class.java)
                                .putExtra("open_today", true)
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                )
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                ),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                    // The launcher reports the real widget area; the table size is the fallback.
                    val size =
                        Backgrounds.widgetSizePx(
                            context,
                            if (inlineDate) 4 else 3,
                            3,
                            runCatching { manager.getAppWidgetOptions(id) }.getOrNull(),
                        )
                    val background = Backgrounds.widgetBitmap(context, size.width, size.height)
                    if (background == null) {
                        views.setImageViewResource(
                            R.id.widget_background,
                            R.drawable.widget_background,
                        )
                    } else {
                        views.setImageViewBitmap(R.id.widget_background, background)
                    }
                    views.setOnClickPendingIntent(R.id.widget_root, open)
                    views.setOnClickPendingIntent(R.id.widget_prev, PendingIntent.getBroadcast(context, id * 2, Intent(context, provider).setAction(ACTION_PREV), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    views.setOnClickPendingIntent(R.id.widget_next, PendingIntent.getBroadcast(context, id * 2 + 1, Intent(context, provider).setAction(ACTION_NEXT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    views.setPendingIntentTemplate(
                        R.id.widget_list,
                        PendingIntent.getActivity(
                            context,
                            1,
                            Intent(context, MainActivity::class.java)
                                .putExtra("open_today", true)
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                ),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                        ),
                    )
                    val service =
                        Intent(context, TodayWidgetService::class.java)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                            .putExtra("widget_offset", offset)
                    service.data = android.net.Uri.parse("sycu-widget://today/$id")
                    views.setRemoteAdapter(R.id.widget_list, service)
                    views.setEmptyView(R.id.widget_list, R.id.widget_empty)
                    manager.updateAppWidget(id, views)
                    manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
                }
            } finally {
                db.close()
            }
        }
    }
}

class TodayWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        TodayFactory(applicationContext)
}

class TodayFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private var rows = emptyList<Occurrence>()
    private var date: LocalDate = today()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        rows =
            runBlocking(Dispatchers.IO) {
                val db = ScheduleDb.open(context)
                try {
                    val tables = db.dao().timetables().first()
                    val id = SchedulePreferences(context).activeId
                    val t = tables.find { it.id == id } ?: tables.firstOrNull()
                    date = today().plusDays(context.getSharedPreferences("widget-days", Context.MODE_PRIVATE).getInt("offset", 0).toLong())
                    if (t == null || date in HolidayStore.dates(context, t.id)) emptyList() else {
                        val holidayKeys = HolidayStore.keys(context, t.id)
                        todayLessons(t, db.dao().allCourses(t.id) + MakeupStore.courses(context, t), date).filterNot { occurrenceKey(it) in holidayKeys }
                    }
                } finally {
                    db.close()
                }
            }
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount() = rows.size

    override fun getViewAt(position: Int): RemoteViews? =
        rows.getOrNull(position)?.let { item ->
            val phase = lessonPhase(item.start, item.end, java.time.ZonedDateTime.now(schoolZone))
            RemoteViews(context.packageName, R.layout.today_widget_row).apply {
                setInt(R.id.widget_row, "setBackgroundResource", if (item.course.isMakeup) R.drawable.widget_makeup_background else R.drawable.widget_course_background)
                setViewVisibility(R.id.widget_makeup_corner, if (item.course.isMakeup) android.view.View.VISIBLE else android.view.View.GONE)
                val appearance = SchedulePreferences(context).appearance()
                val background = phaseColor(appearance.card(item.course.name), phase)
                val fade = phaseAlpha(phase, appearance.pastCourseOpacity)
                setInt(R.id.widget_makeup_corner, "setImageAlpha", (255 * fade).toInt())
                if (item.course.isMakeup) {
                    // Draw the asymmetric corners directly, including on older outline-clipping implementations.
                    setInt(R.id.widget_row_color, "setBackgroundColor", android.graphics.Color.TRANSPARENT)
                    setImageViewResource(R.id.widget_row_color, R.drawable.widget_makeup_fill)
                    setInt(R.id.widget_row_color, "setColorFilter", background or 0xff000000.toInt())
                    setInt(R.id.widget_row_color, "setImageAlpha", (appearance.opacity * 255 / 100 * fade).toInt())
                } else {
                    setImageViewResource(R.id.widget_row_color, 0)
                    setInt(R.id.widget_row_color, "setImageAlpha", 255)
                    setInt(R.id.widget_row_color, "setBackgroundColor", (background and 0x00ffffff) or ((appearance.opacity * 255 / 100 * fade).toInt() shl 24))
                }
                val textColor = (readableColor(background) and 0x00ffffff) or ((255 * fade).toInt() shl 24)
                listOf(R.id.widget_time, R.id.widget_course, R.id.widget_location).forEach { setTextColor(it, textColor) }
                setTextViewText(
                    R.id.widget_time,
                    "${item.start.toLocalTime()}–${item.end.toLocalTime()}",
                )
                setTextViewText(R.id.widget_course, item.course.name)
                setTextViewText(R.id.widget_location, courseLocation(item.course))
                setOnClickFillInIntent(R.id.widget_row, Intent())
            }
        }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount() = 1

    override fun getItemId(position: Int) = position.toLong()

    override fun hasStableIds() = false
}

class TodayWidgetLarge : TodayWidget() {
    override val component: Class<*> get() = TodayWidgetLarge::class.java

    override val layout: Int get() = R.layout.today_widget_large

    override val inlineDate: Boolean get() = true
}
