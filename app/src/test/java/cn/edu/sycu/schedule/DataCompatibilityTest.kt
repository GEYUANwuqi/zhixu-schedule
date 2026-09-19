package cn.edu.sycu.schedule

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.LocalDate

/** Frozen synthetic release data, read by the current real Room/preference code. No JNI or login. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DataCompatibilityTest {
    private lateinit var context: Context
    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream("/compat/$name")).bufferedReader().use { it.readText() }
    @Before fun reset() {
        assertTrue(android.os.Build.VERSION.SDK_INT in setOf(26, 35))
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("schedule.db")
        listOf("display", "widget-days", "course-alerts").forEach { context.deleteSharedPreferences(it) }
    }
    private fun seed(sql: String = fixture("schema-v1.sql")) {
        val path = context.getDatabasePath("schedule.db")
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db -> sql.split(';').filter { it.isNotBlank() }.forEach { db.execSQL(it) } }
    }
    private fun prefs(release: String) {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs").apply { mkdirs() }
        File(dir, "display.xml").writeText(fixture("$release-display.xml"))
        if (release == "v0.1.1") File(dir, "widget-days.xml").writeText("<map><int name=\"offset\" value=\"2\" /></map>")
    }
    @Test fun released010DataSurvives() = verifyRelease("v0.1.0")
    @Test fun released011DataSurvives() = verifyRelease("v0.1.1")
    private fun verifyRelease(release: String) = runBlocking {
        seed(); prefs(release)
        val images = listOf(BackgroundKind.App, BackgroundKind.Widget).associateWith { kind ->
            val file = Backgrounds.file(context, kind)
            val bytes = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jS1sAAAAASUVORK5CYII=")
            file.writeBytes(bytes)
            bytes
        }
        val before = File(context.applicationInfo.dataDir, "shared_prefs/display.xml").readBytes()
        repeat(2) {
            val db = ScheduleDb.open(context)
            try {
                val tables = db.dao().timetables().first()
                assertEquals(setOf("autumn", "spring"), tables.map { it.id }.toSet())
                val autumn = tables.single { it.id == "autumn" }
                assertEquals("2026-08-24", autumn.start)
                assertEquals(20, autumn.weekCount)
                val courses = db.dao().allCourses(autumn.id)
                assertEquals(2, courses.size)
                val manual = courses.single { it.id == "manual" }
                assertTrue(manual.edited); assertFalse(manual.deleted)
                assertEquals("source-a", manual.sourceId)
                assertEquals("手动调整", manual.note)
                assertTrue(courses.single { it.id == "removed" }.deleted)
                assertEquals("source-b", courses.single { it.id == "removed" }.sourceId)
                val row = todayLessons(autumn, courses, LocalDate.parse("2026-09-14")).single()
                assertEquals("数学", row.course.name)
                assertEquals("10:20", row.start.toLocalTime().toString())
                assertEquals("12:00", row.end.toLocalTime().toString())
                assertEquals(listOf(4, 6), manual.weekList())
                assertTrue(todayLessons(autumn, courses, LocalDate.parse("2026-09-15")).isEmpty())
                assertEquals("本地课程", db.dao().allCourses("spring").single().name)
                val p = SchedulePreferences(context)
                assertEquals("autumn", p.activeId)
                assertEquals(CardDisplay(false, true, false, true, true), p.read())
                assertEquals(-1234567, p.themeColor); assertTrue(p.widgetLarge)
                assertEquals(if (release == "v0.1.1") 2 else 0, context.getSharedPreferences("widget-days", 0).getInt("offset", 0))
                assertEquals(60, p.pastCourseOpacity)
                if (release == "v0.1.1") {
                    assertTrue(p.autoCheckUpdates)
                    assertEquals(73, p.widgetOpacity)
                    assertEquals(mapOf("数学" to -7654321), p.cardColors)
                    assertEquals(listOf("数学", "G411"), p.rules.map { it.matchValue })
                    assertEquals("A320", applyCourseRules(manual, p.rules, autumn).room)
                } else {
                    assertFalse(p.autoCheckUpdates); assertEquals(100, p.widgetOpacity)
                    assertTrue(p.cardColors.isEmpty()); assertTrue(p.rules.isEmpty())
                }
                images.forEach { (kind, bytes) -> assertArrayEquals(bytes, Backgrounds.file(context, kind).readBytes()); assertTrue(Backgrounds.exists(context, kind)) }
                assertArrayEquals(before, File(context.applicationInfo.dataDir, "shared_prefs/display.xml").readBytes())
            } finally { db.close() }
        }
    }
    private fun assertFailedOpenPreservesData(sql: String) = runBlocking {
        seed(sql)
        val db = ScheduleDb.open(context)
        try {
            try { db.dao().timetables().first(); fail("Incompatible database must not be silently recreated") }
            catch (_: IllegalStateException) { }
        } finally { db.close() }
        SQLiteDatabase.openDatabase(context.getDatabasePath("schedule.db").path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            raw.rawQuery("SELECT name,edited,deleted FROM Course WHERE id='manual'", null).use {
                assertTrue(it.moveToFirst()); assertEquals("数学", it.getString(0)); assertEquals(1, it.getInt(1)); assertEquals(0, it.getInt(2))
            }
            raw.rawQuery("SELECT count(*) FROM Course", null).use { assertTrue(it.moveToFirst()); assertEquals(3, it.getInt(0)) }
        }
    }
    @Test fun unsupportedVersionDoesNotErase() = assertFailedOpenPreservesData(fixture("schema-v1.sql").replace("user_version=1", "user_version=999"))
    @Test fun changedSchemaDoesNotErase() = assertFailedOpenPreservesData(fixture("schema-v1.sql").replace("`name` TEXT", "`name` INTEGER"))
    @Test fun previousJsonFormatsStillDecode() {
        val data = JsonTransfer.decode(fixture("timetable-v1.json"))
        assertEquals("数学", data.courses.single().name)
        assertEquals(listOf(4, 6), data.courses.single().weekList())
        assertEquals("A320", RuleTransfer.decode(fixture("rules-v1.json")).single().room)
    }
    @Test fun makeupOverlayNeverWritesOriginalCourseRows() = runBlocking {
        seed(); prefs("v0.1.1")
        val db = ScheduleDb.open(context)
        try {
            val before = db.dao().allCourses("autumn")
            val entry = Makeup(id = "overlay", tableId = "autumn", date = "2026-09-19", name = "数学", teacher = "", room = "B101", periods = "3,4")
            MakeupStore.save(context, entry)
            assertEquals(before, db.dao().allCourses("autumn"))
            assertEquals(listOf(entry), MakeupStore.read(context))
            val offset = java.time.temporal.ChronoUnit.DAYS.between(today(), LocalDate.parse(entry.date)).toInt()
            context.getSharedPreferences("widget-days", 0).edit().putInt("offset", offset).commit()
            val factory = TodayFactory(context)
            factory.onDataSetChanged()
            assertEquals(1, factory.getCount())
            val widgetRow = factory.getViewAt(0)!!.apply(context, android.widget.FrameLayout(context))
            assertEquals(android.view.View.VISIBLE, widgetRow.findViewById<android.view.View>(R.id.widget_makeup_corner).visibility)
            val corners = (widgetRow.background as android.graphics.drawable.GradientDrawable).cornerRadii!!
            assertEquals(0f, corners[0], 0f)
            assertTrue(corners.drop(2).all { it > 0f })
            val backup = BackupCodec.decode(BackupStore.export(context, "补课备份"))
            assertEquals(listOf(entry), MakeupCodec.decode(backup.preferences.getJSONObject("display").getString("makeup_lessons")))
            MakeupStore.remove(context, entry.id)
            factory.onDataSetChanged()
            assertEquals(0, factory.getCount())
            assertTrue(MakeupStore.read(context).isEmpty())
            assertEquals(before, db.dao().allCourses("autumn"))
        } finally { db.close() }
    }
}
