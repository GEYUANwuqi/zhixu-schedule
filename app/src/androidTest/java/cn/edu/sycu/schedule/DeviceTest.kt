package cn.edu.sycu.schedule

import android.graphics.BitmapFactory
import android.util.Size
import kotlin.math.roundToInt
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceTest {
    @Test
    fun pdfExportOpensWithFullLayout() {
        val t = Timetable(id = "pdf-test", start = "2026-08-24")
        val c = Course(timetableId = t.id, name = "示例数学", room = "B101", periods = "5,6")
        val file = PngExport.write(context, t, listOf(c), pdf = true)
        try {
            android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                assertEquals(1, renderer.pageCount)
                renderer.openPage(0).use { page -> assertEquals(900, page.width); assertTrue(page.height > 700) }
            }
        } finally { file.delete() }
    }
    @get:Rule
    val calendarPermission =
        androidx.test.rule.GrantPermissionRule.grant(
            android.Manifest.permission.READ_CALENDAR,
            android.Manifest.permission.WRITE_CALENDAR,
        )
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun browserJniBridgeStartsWithoutExposingProtocol() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = android.webkit.WebView(context)
            try {
                val result =
                    org.json.JSONObject(
                        NativeCore.browser(
                            view,
                            null,
                            org.json
                                .JSONObject()
                                .put("begin", true)
                                .toString(),
                        )
                    )
                assertTrue(result.getBoolean("ok"))
                assertTrue(result.getJSONObject("data").getBoolean("ready"))
            } finally {
                BrowserHost.clear(view)
            }
        }
    }

    @Test
    fun nativeBoundaryAndDeviceKeyEncryption() {
        SecretVault.initialize(context)
        val content = "synthetic credential fixture".toByteArray()
        val expected = content.clone()
        val wrapped = SecretVault.wrap(content)
        assertFalse(wrapped.contentEquals(content))
        assertArrayEquals(expected, SecretVault.unwrap(wrapped))
        assertTrue(content.all { it == 0.toByte() })
        val altered = wrapped.clone()
        altered[altered.lastIndex] = (altered.last().toInt() xor 1).toByte()
        assertTrue(runCatching { SecretVault.unwrap(altered) }.isFailure)
        assertTrue(NativeCore.request(BuildConfig.INITIALIZE).has("hasLogin"))
    }

    @Test
    fun syncPreservesManualEditsAndTombstones() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ScheduleDb::class.java).build()
        try {
            val dao = db.dao()
            val t = Timetable(start = "2026-08-24")
            dao.save(t)
            val manual = Course(timetableId = t.id, name = "手动课")
            val edited =
                Course(
                    id = "edited",
                    timetableId = t.id,
                    name = "本地更名",
                    sourceId = "s1",
                    edited = true,
                )
            val deleted =
                Course(
                    id = "deleted",
                    timetableId = t.id,
                    name = "已删除",
                    sourceId = "s2",
                    deleted = true,
                )
            dao.save(manual)
            dao.save(edited)
            dao.save(deleted)
            dao.merge(
                t,
                listOf(edited.copy(name = "学校名称", edited = false), deleted.copy(deleted = false)),
            )
            val rows = dao.allCourses(t.id)
            assertEquals(3, rows.size)
            assertEquals("本地更名", rows.single { it.id == "edited" }.name)
            assertTrue(rows.single { it.id == "deleted" }.deleted)
            dao.deleteTable(t.id)
            assertTrue(dao.allCourses(t.id).isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun calendarImportIsIdempotent() {
        val t = Timetable(name = "自动验证临时课表", start = "2026-08-24")
        val c = Course(timetableId = t.id, name = "示例课", weeks = "4,6", periods = "5-6")
        val resolver = context.contentResolver
        try {
            assertEquals(2, CalendarExport.write(context, t, listOf(c)))
            assertEquals(2, CalendarExport.write(context, t, listOf(c)))
            val id =
                resolver
                    .query(
                        android.provider.CalendarContract.Calendars.CONTENT_URI,
                        arrayOf("_id"),
                        "name=?",
                        arrayOf("sycu-${t.id}"),
                        null,
                    )!!
                    .use {
                        check(it.moveToFirst())
                        it.getLong(0)
                    }
            resolver.insert(
                android.provider.CalendarContract.Events.CONTENT_URI,
                android.content.ContentValues().apply {
                    put(android.provider.CalendarContract.Events.CALENDAR_ID, id)
                    put(android.provider.CalendarContract.Events.TITLE, "用户手工添加的测试事件")
                    put(android.provider.CalendarContract.Events.DTSTART, 1_800_000_000_000L)
                    put(android.provider.CalendarContract.Events.DTEND, 1_800_000_060_000L)
                    put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, schoolZone.id)
                },
            )
            assertEquals(2, CalendarExport.write(context, t, listOf(c)))
            val count =
                resolver
                    .query(
                        android.provider.CalendarContract.Events.CONTENT_URI,
                        arrayOf("_id"),
                        "calendar_id=? AND deleted=0",
                        arrayOf(id.toString()),
                        null,
                    )!!
                    .use { it.count }
            assertEquals(2, count)
        } finally {
            val uri =
                android.provider.CalendarContract.Calendars.CONTENT_URI.buildUpon()
                    .appendQueryParameter("caller_is_syncadapter", "true")
                    .appendQueryParameter("account_name", "知序课表")
                    .appendQueryParameter(
                        "account_type",
                        android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL,
                    )
                    .build()
            resolver.delete(uri, "name=?", arrayOf("sycu-${t.id}"))
        }
    }

    @Test
    fun pngRendersOddAndEvenWithoutNetwork() {
        val t = Timetable(name = "演示学期", start = "2026-08-24")
        val courses =
            listOf(
                Course(
                    timetableId = t.id,
                    name = "示例数学",
                    teacher = "示例教师",
                    room = "B233",
                    weekday = 1,
                    periods = "5-6",
                    weeks = "1,3,5",
                ),
                Course(
                    timetableId = t.id,
                    name = "示例英语",
                    teacher = "示例教师",
                    room = "J110",
                    weekday = 3,
                    periods = "3-4",
                    weeks = "2,4,6",
                ),
            )
        val f = PngExport.write(context, t, courses)
        val bitmap = BitmapFactory.decodeFile(f.path)
        assertEquals(1800, bitmap.width)
        assertTrue(bitmap.height > 500)
        bitmap.recycle()
    }
    @Test
    fun backgroundImagesAreSeparatePerSurface() {
        val source =
            android.graphics.Bitmap.createBitmap(800, 600, android.graphics.Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.RED)
        val sourceFile = java.io.File(context.cacheDir, "background-source.png")
        try {
            assertFalse(Backgrounds.exists(context, BackgroundKind.App))
            assertFalse(Backgrounds.exists(context, BackgroundKind.Widget))
            sourceFile.outputStream().use {
                assertTrue(source.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            val uri = android.net.Uri.fromFile(sourceFile)

            Backgrounds.save(context, BackgroundKind.App, uri)
            assertTrue(Backgrounds.exists(context, BackgroundKind.App))
            assertFalse(Backgrounds.exists(context, BackgroundKind.Widget))
            assertNotNull(Backgrounds.bitmap(context, BackgroundKind.App))
            Backgrounds.widgetBitmap(context, 540, 540)!!.also {
                assertEquals(255, it.getPixel(270, 270).ushr(24))
                it.recycle()
            }

            Backgrounds.save(context, BackgroundKind.Widget, uri)
            val widget = Backgrounds.widgetBitmap(context, 540, 540)!!
            assertEquals(540, widget.width)
            assertEquals(540, widget.height)
            // Rounded corners stay clear and the photo is veiled toward white for readable text.
            assertEquals(0, widget.getPixel(0, 0).ushr(24))
            val centre = widget.getPixel(270, 270)
            assertTrue(android.graphics.Color.green(centre) > 120)
            assertTrue(android.graphics.Color.blue(centre) > 120)
            widget.recycle()

            Backgrounds.clear(context, BackgroundKind.App)
            assertFalse(Backgrounds.exists(context, BackgroundKind.App))
            assertTrue(Backgrounds.exists(context, BackgroundKind.Widget))
            Backgrounds.clear(context, BackgroundKind.Widget)
            val preferences = SchedulePreferences(context)
            try {
                for (opacity in listOf(0, 50, 100)) {
                    preferences.widgetOpacity = opacity
                    Backgrounds.widgetBitmap(context, 540, 540)!!.also {
                        assertEquals(opacity * 255 / 100, it.getPixel(270, 270).ushr(24))
                        it.recycle()
                    }
                }
            } finally {
                preferences.widgetOpacity = 100
            }
        } finally {
            source.recycle()
            if (sourceFile.exists()) sourceFile.delete()
        }
    }

    @Test
    fun widgetBackgroundUsesReportedSizeAndCropBudget() {
        val density = context.resources.displayMetrics.density
        // The launcher's own measurement wins over the table estimate in either orientation.
        // Launchers mix the bounds: the minimum width and the maximum height are the portrait pair.
        val reported =
            android.os.Bundle().apply {
                putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 214)
                putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 208)
                putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 336)
                putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 306)
            }
        val size = Backgrounds.widgetSizePx(context, 3, 3, reported)
        assertEquals((214 * density).roundToInt(), size.width)
        assertEquals((306 * density).roundToInt(), size.height)

        // Without launcher data the Android sizing table applies: 3×3 is taller than it is wide,
        // and 4×3 keeps that height while adding one 73dp column.
        val small = Backgrounds.widgetSizePx(context, 3, 3, null)
        val large = Backgrounds.widgetSizePx(context, 4, 3, null)
        assertEquals((WidgetGeometry.widthDp(3) * density).roundToInt(), small.width)
        assertEquals((WidgetGeometry.heightDp(3) * density).roundToInt(), small.height)
        assertTrue(small.height > small.width)
        assertEquals(small.height, large.height)
        assertEquals((73 * density).roundToInt(), large.width - small.width)

        val budget = Backgrounds.widgetBitmapSize(3000, 2400)
        assertTrue(budget.width.toLong() * budget.height <= Backgrounds.MAX_PIXELS)
        assertTrue(budget.width < 3000 && budget.height < 2400)
        assertEquals(3000f / 2400f, budget.width.toFloat() / budget.height, 0.02f)
        assertEquals(
            android.util.Size(900, 300),
            Backgrounds.widgetBitmapSize(900, 300),
        )
    }

    @Test
    fun widgetReadsSelectedTableAndRefreshesDeletedCourses() = runBlocking {
        val db = ScheduleDb.open(context)
        val prefs = SchedulePreferences(context)
        val previous = prefs.activeId
        val t = Timetable(name = "小组件测试", start = today().with(java.time.DayOfWeek.MONDAY).toString())
        val c = Course(timetableId = t.id, name = "今日示例课", room = "B101", teacher = "小明", weekday = today().dayOfWeek.value, weeks = "1", periods = "5-6")
        val factory = TodayFactory(context)
        try {
            db.dao().save(t); db.dao().save(c); prefs.activeId = t.id
            factory.onDataSetChanged()
            assertEquals(1, factory.count)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val view = factory.getViewAt(0)!!.apply(context, android.widget.FrameLayout(context))
                assertEquals("今日示例课", view.findViewById<android.widget.TextView>(R.id.widget_course).text.toString())
                assertEquals("13:30–15:10", view.findViewById<android.widget.TextView>(R.id.widget_time).text.toString())
                assertEquals("B101(白宫)·小明", view.findViewById<android.widget.TextView>(R.id.widget_location).text.toString())
            }
            db.dao().save(c.copy(deleted = true))
            factory.onDataSetChanged()
            assertEquals(0, factory.count)
        } finally { factory.onDestroy(); prefs.activeId = previous; db.dao().deleteTable(t.id); db.close() }
    }
}
