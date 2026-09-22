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
    internal fun regularCourses(t: Timetable, courses: List<Course>) = courses.filter { !it.deleted && !it.isMakeup && it.timetableId == t.id }
    fun write(context: Context, t: Timetable, courses: List<Course>, pdf: Boolean = false): File {
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
        val sections = listOf(1, 0).map { parity ->
            (1..7).map { day ->
                lessonBlocks(regularCourses(t, courses).filter { it.weekday == day && it.weekList().any { w -> w % 2 == parity } })
                    .sortedBy { it.periods.first() }.map { block ->
                        val c = block.course.copy(periods = block.periods.joinToString(","))
                        val timeLabel = "${times.getOrNull(block.periods.first()-1)?.first ?: "待定"}–${times.getOrNull(block.periods.last()-1)?.second ?: "待定"}"
                        Card(c, wrap(c.name, 25f) + wrap(timeLabel, 19f) + wrap("${c.periods}节", 19f) +
                            wrap(roomLabel(c.room), 20f) + wrap(c.teacher, 20f) +
                            wrap("第${c.weekList().filter { it % 2 == parity }.joinToString(",")}周", 18f) +
                            (if (c.note.isBlank()) emptyList() else wrap(c.note, 18f)))
                    }
            }
        }
        val periodCount = maxOf(10, sections.flatten().flatten().maxOfOrNull { it.course.periodList().last() } ?: 10)
        // Each period has the same shared height in every weekday column. Overlapping
        // regular courses stay readable inside their shared period region.
        val rowHeights = sections.map { days ->
            FloatArray(periodCount) { 70f }.also { rows ->
                days.forEach { cards ->
                    lessonStacks(cards.map { it.course }).forEach { stack ->
                        val members = stack.blocks.map { b -> cards.first { it.course == b.course } }
                        val required = members.sumOf { (it.height + 12).toDouble() }.toFloat()
                        val perRow = required / (stack.end - stack.start + 1)
                        for (p in stack.start..stack.end) rows[p - 1] = maxOf(rows[p - 1], perRow)
                    }
                }
            }
        }
        val heights = rowHeights.map { it.sum() + 110 }
        val height = (190 + heights.sum() + 80).toInt()
        require(height <= 16000) { "课表太大，无法导出单张图片" }
        val document = if (pdf) android.graphics.pdf.PdfDocument() else null
        val bitmap = if (pdf) null else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
        val page = document?.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(width / 2, (height + 1) / 2, 1).create())
        val canvas = page?.canvas?.apply { scale(.5f, .5f) } ?: Canvas(requireNotNull(bitmap))
        val appearance = SchedulePreferences(context).appearance()
        canvas.drawColor(appearance.background)
        fun text(value: String, x: Float, y: Float, size: Float, color: Int = appearance.ink) {
            paint.color = color
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
                val rows = rowHeights[index]
                lessonStacks(cards.map { it.course }).forEach { stack ->
                    val members = stack.blocks.map { b -> cards.first { it.course == b.course } }
                    var y = top + 90 + rows.take(stack.start - 1).sum()
                    val available = rows.slice((stack.start - 1)..(stack.end - 1)).sum()
                    val extra = (available - members.sumOf { (it.height + 12).toDouble() }.toFloat()) / members.size
                    members.forEach { card ->
                        val cardHeight = card.height + extra
                        val cardColor = appearance.card(card.course.name)
                        paint.color = cardColor
                        canvas.drawRoundRect(x, y, x + column - 10, y + cardHeight, 16f, 16f, paint)
                        var baseline = y + 14
                        card.lines.forEach { line ->
                            baseline += line.size
                            text(line.text, x + 12, baseline, line.size, readableColor(cardColor))
                            baseline += 7
                        }
                        y += cardHeight + 12
                    }
                }
            }
            top += heights[index]
        }
        text("${today()} · 起止时间以学校安排为准", margin, height - 30f, 22f)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "timetable-${java.util.UUID.randomUUID()}.${if (pdf) "pdf" else "png"}")
        try {
            file.outputStream().use {
                if (document != null) { document.finishPage(requireNotNull(page)); document.writeTo(it) }
                else check(requireNotNull(bitmap).compress(Bitmap.CompressFormat.PNG, 100, it)) { "图片写入失败" }
            }
        } catch (e: Exception) { file.delete(); throw e }
        return file
        } finally { bitmap?.recycle(); document?.close() }
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = when (file.extension) { "pdf" -> "application/pdf"; "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; else -> "image/png" }
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("课表", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "分享课表 ${file.extension.uppercase()}",
            )
        )
    }
}
