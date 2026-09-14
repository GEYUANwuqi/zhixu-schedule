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
        super.onReceive(context, intent)
        if (intent.action in WIDGET_ACTIONS) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    updateComponent(context, component, layout, inlineDate)
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

        private val WIDGET_ACTIONS =
            listOf(
                Intent.ACTION_DATE_CHANGED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                REFRESH_ACTION,
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
            val db = ScheduleDb.open(context)
            try {
                val tables = db.dao().timetables().first()
                val selected = SchedulePreferences(context).activeId
                val table = tables.find { it.id == selected } ?: tables.firstOrNull()
                ids.forEach { id ->
                    val views = RemoteViews(context.packageName, layout)
                    if (inlineDate) {
                        views.setTextViewText(R.id.widget_title, "今日课表 · ${today()}")
                    } else {
                        views.setTextViewText(R.id.widget_title, "今日课表")
                        views.setTextViewText(R.id.widget_date, today().toString())
                    }
                    views.setTextViewText(R.id.widget_subtitle, table?.name ?: "知序")
                    views.setTextViewText(
                        R.id.widget_empty,
                        if (table == null) "尚无课表，点击添加或同步" else "今天没有课程",
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

    override fun onCreate() {}

    override fun onDataSetChanged() {
        rows =
            runBlocking(Dispatchers.IO) {
                val db = ScheduleDb.open(context)
                try {
                    val tables = db.dao().timetables().first()
                    val id = SchedulePreferences(context).activeId
                    val t = tables.find { it.id == id } ?: tables.firstOrNull()
                    if (t == null) emptyList() else todayLessons(t, db.dao().allCourses(t.id))
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
            RemoteViews(context.packageName, R.layout.today_widget_row).apply {
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
