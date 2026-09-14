package cn.edu.sycu.schedule

import android.content.*
import android.graphics.*
import android.provider.CalendarContract
import androidx.core.content.FileProvider
import java.io.File

object CalendarExport {
    fun write(context: Context, t: Timetable, courses: List<Course>): Int {
        // Validate every occurrence before touching the provider.
        val events = courses.flatMap { occurrences(t, it) }
        val resolver = context.contentResolver
        val account = "知序课表"
        val accountType = CalendarContract.ACCOUNT_TYPE_LOCAL
        val uri =
            CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, accountType)
                .build()
        val calendarName = "sycu-${t.id}"
        val calendarId =
            resolver
                .query(
                    CalendarContract.Calendars.CONTENT_URI,
                    arrayOf("_id"),
                    "account_name=? AND account_type=? AND name=?",
                    arrayOf(account, accountType, calendarName),
                    null,
                )
                ?.use { if (it.moveToFirst()) it.getLong(0) else null }
                ?: run {
                    val v =
                        ContentValues().apply {
                            put(CalendarContract.Calendars.ACCOUNT_NAME, account)
                            put(CalendarContract.Calendars.ACCOUNT_TYPE, accountType)
                            put(CalendarContract.Calendars.NAME, calendarName)
                            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "知序 · ${t.name}")
                            put(CalendarContract.Calendars.CALENDAR_COLOR, Color.rgb(82, 117, 79))
                            put(
                                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                                CalendarContract.Calendars.CAL_ACCESS_OWNER,
                            )
                            put(CalendarContract.Calendars.OWNER_ACCOUNT, account)
                            put(CalendarContract.Calendars.VISIBLE, 1)
                            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
                            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, schoolZone.id)
                        }
                    ContentUris.parseId(resolver.insert(uri, v) ?: error("无法创建系统日历"))
                }
        // Replace only this app-owned timetable calendar, never the user's other calendars.
        val operations =
            arrayListOf(
                ContentProviderOperation.newDelete(CalendarContract.Events.CONTENT_URI)
                    .withSelection("calendar_id=?", arrayOf(calendarId.toString()))
                    .build()
            )
        events.forEach { event ->
            operations.add(
                ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                    .withValues(
                        ContentValues().apply {
                            put(CalendarContract.Events.CALENDAR_ID, calendarId)
                            put(CalendarContract.Events.CUSTOM_APP_PACKAGE, context.packageName)
                            put(CalendarContract.Events.TITLE, event.course.name)
                            put(
                                CalendarContract.Events.EVENT_LOCATION,
                                courseLocation(event.course),
                            )
                            put(
                                CalendarContract.Events.DESCRIPTION,
                                "${event.course.teacher}\n第${event.week}周\n${event.course.note}",
                            )
                            put(
                                CalendarContract.Events.DTSTART,
                                event.start.toInstant().toEpochMilli(),
                            )
                            put(CalendarContract.Events.DTEND, event.end.toInstant().toEpochMilli())
                            put(CalendarContract.Events.EVENT_TIMEZONE, schoolZone.id)
                        }
                    )
                    .build()
            )
        }
        // One transaction: provider failure cannot leave a half-replaced timetable.
        require(operations.size <= 1500) { "课程事件过多，请拆分课表后导入" }
        resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        return events.size
    }
}

object PngExport {
    private val colors =
        intArrayOf(
            0xffe4ebd5.toInt(),
            0xffe7def0.toInt(),
            0xfff5e1cb.toInt(),
            0xffd9e8ee.toInt(),
            0xfff0dce0.toInt(),
            0xffe8e7cb.toInt(),
        )

    fun write(context: Context, t: Timetable, courses: List<Course>): File {
        val width = 1800
        val column = 238f
        val margin = 60f
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
        data class Line(val text: String, val size: Float)
        data class Card(val course: Course, val lines: List<Line>) {
            val height: Float
                get() = lines.sumOf { (it.size + 7).toDouble() }.toFloat() + 28
        }
        fun wrap(value: String, size: Float): List<Line> {
            paint.textSize = size
            val output = mutableListOf<Line>()
            value.split('\n').forEach { part ->
                var rest = part
                if (rest.isEmpty()) output.add(Line("", size))
                while (rest.isNotEmpty()) {
                    val count = paint.breakText(rest, true, column - 36, null).coerceAtLeast(1)
                    output.add(Line(rest.take(count), size))
                    rest = rest.drop(count)
                }
            }
            return output
        }
        val times = timesFor(t)
        val sections =
            listOf(1, 0).map { parity ->
                (1..7).map { day ->
                    courses
                        .filter { it.weekday == day && it.weekList().any { w -> w % 2 == parity } }
                        .sortedBy { it.periodList().first() }
                        .map { c ->
                            val p = c.periodList()
                            val groups = mutableListOf<MutableList<Int>>()
                            p.forEach {
                                if (groups.isEmpty() || groups.last().last() + 1 != it)
                                    groups.add(mutableListOf(it))
                                else groups.last().add(it)
                            }
                            val timeLabel =
                                groups.joinToString(" / ") { group ->
                                    "${times.getOrNull(group.first()-1)?.first?:"待定"}–${times.getOrNull(group.last()-1)?.second?:"待定"}"
                                }
                            Card(
                                c,
                                wrap(c.name, 25f) +
                                    wrap(timeLabel, 19f) +
                                    wrap("${c.periods}节", 19f) +
                                    wrap(roomLabel(c.room), 20f) +
                                    wrap(c.teacher, 20f) +
                                    wrap(
                                        "第${c.weekList().filter { it%2==parity }.joinToString(",")}周",
                                        18f,
                                    ) +
                                    (if (c.note.isBlank()) emptyList() else wrap(c.note, 18f)),
                            )
                        }
                }
            }
        val heights =
            sections.map { days ->
                days.maxOf { cards -> cards.sumOf { (it.height + 12).toDouble() }.toFloat() } + 110
            }
        val height = (190 + heights.sum() + 80).toInt()
        require(height <= 16000) { "课表太大，无法导出单张图片" }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xfff8f7ef.toInt())
        fun text(value: String, x: Float, y: Float, size: Float) {
            paint.color = 0xff293b2b.toInt()
            paint.textSize = size
            canvas.drawText(value, x, y, paint)
        }
        text("知序 · ${t.name}", margin, 65f, 38f)
        text("开学日期 ${t.start}  ·  ${t.weekCount} 教学周  ·  时间：北京时间", margin, 110f, 24f)
        var top = 150f
        sections.forEachIndexed { index, days ->
            text(if (index == 0) "单周" else "双周", margin, top + 25, 30f)
            days.forEachIndexed { day, cards ->
                val x = margin + day * column
                text("周${"一二三四五六日"[day]}", x + 10, top + 70, 24f)
                var y = top + 90
                cards.forEach { card ->
                    paint.color = colors[Math.floorMod(card.course.name.hashCode(), colors.size)]
                    canvas.drawRoundRect(x, y, x + column - 10, y + card.height, 16f, 16f, paint)
                    var baseline = y + 14
                    card.lines.forEach { line ->
                        baseline += line.size
                        text(line.text, x + 12, baseline, line.size)
                        baseline += 7
                    }
                    y += card.height + 12
                }
            }
            top += heights[index]
        }
        text("${today()} · 起止时间以学校安排为准", margin, height - 30f, 22f)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "timetable.png")
        try {
            file.outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "图片写入失败" }
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("课表", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "分享课表 PNG",
            )
        )
    }
}
