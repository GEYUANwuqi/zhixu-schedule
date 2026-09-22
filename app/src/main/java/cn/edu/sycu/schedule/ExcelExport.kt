package cn.edu.sycu.schedule

import android.content.Context
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Editable SpreadsheetML workbook; values are always text, never formulas. */
object ExcelExport {
    private const val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private fun xml(value: String) = value.filter { it == '\n' || it == '\r' || it == '\t' || it >= ' ' && it != '\uFFFE' && it != '\uFFFF' }
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun cell(column: Int, row: Int, value: String, style: Int = 0) =
        "<c r=\"${('A'.code + column).toChar()}$row\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>"
    fun write(context: Context, table: Timetable, courses: List<Course>): File {
        val file = File(File(context.cacheDir, "exports").apply { mkdirs() }, "timetable-${java.util.UUID.randomUUID()}.xlsx")
        try { file.outputStream().use { encode(it, table, courses) } }
        catch (e: Exception) { file.delete(); throw e }
        return file
    }
    internal fun encode(output: OutputStream, table: Timetable, courses: List<Course>) {
        val regular = PngExport.regularCourses(table, courses)
        val times = timesFor(table)
        val periods = maxOf(10, regular.maxOfOrNull { it.periodList().last() } ?: 10)
        val names = listOf("单周", "双周", "课程明细")
        fun sheet(parity: Int): String {
            val values = mutableMapOf<Pair<Int, Int>, String>()
            val merges = mutableListOf("A1:H1", "A2:H2")
            val heights = FloatArray(periods) { 32f }
            for (day in 1..7) {
                val stacks = lessonStacks(regular.filter { it.weekday == day && it.weekList().any { w -> w % 2 == parity } })
                for (stack in stacks) {
                    val content = stack.blocks.joinToString("\n────────\n") { block ->
                        val c = block.course
                        listOf(c.name, "${block.periods.joinToString(",")}节 ${times.getOrNull(block.periods.first()-1)?.first ?: "待定"}–${times.getOrNull(block.periods.last()-1)?.second ?: "待定"}", roomLabel(c.room), c.teacher,
                            "第${c.weekList().filter { it % 2 == parity }.joinToString(",")}周", c.note).filter { it.isNotBlank() }.joinToString("\n")
                    }
                    require(content.length <= 32767) { "单元格内容过长" }
                    values[day to stack.start] = content
                    val column = ('A'.code + day).toChar()
                    if (stack.end > stack.start) merges += "$column${stack.start + 3}:$column${stack.end + 3}"
                    val lines = content.lines().sumOf { maxOf(1, (it.length + 11) / 12) }
                    val required = (lines * 15f + 12f) / (stack.end - stack.start + 1)
                    require(required <= 409f) { "课程内容过长，无法完整导出 Excel，请缩短备注后重试" }
                    for (p in stack.start..stack.end) heights[p - 1] = maxOf(heights[p - 1], required)
                }
            }
            return buildString {
                append("<worksheet xmlns=\"$ns\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"3\" topLeftCell=\"A4\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><cols><col min=\"1\" max=\"1\" width=\"18\" customWidth=\"1\"/><col min=\"2\" max=\"8\" width=\"26\" customWidth=\"1\"/></cols><sheetData>")
                append("<row r=\"1\" ht=\"28\" customHeight=\"1\">${cell(0, 1, "知序 · ${table.name} · ${if (parity == 1) "单周" else "双周"}", 1)}</row>")
                append("<row r=\"2\" ht=\"26\" customHeight=\"1\">${cell(0, 2, "开学日期 ${table.start} · ${table.weekCount}教学周 · 北京时间")}</row>")
                append("<row r=\"3\" ht=\"24\" customHeight=\"1\">${cell(0, 3, "节次 / 时间", 1)}")
                for (day in 1..7) append(cell(day, 3, "周${"一二三四五六日"[day - 1]}", 1))
                append("</row>")
                for (p in 1..periods) {
                    val row = p + 3
                    append("<row r=\"$row\" ht=\"${heights[p - 1]}\" customHeight=\"1\">")
                    append(cell(0, row, "$p\n${times.getOrNull(p-1)?.let { "${it.first}–${it.second}" } ?: "待定"}"))
                    for (day in 1..7) append(cell(day, row, values[day to p].orEmpty()))
                    append("</row>")
                }
                append("</sheetData><mergeCells count=\"${merges.size}\">")
                merges.forEach { append("<mergeCell ref=\"$it\"/>") }
                append("</mergeCells><pageMargins left=\"0.25\" right=\"0.25\" top=\"0.3\" bottom=\"0.3\" header=\"0\" footer=\"0\"/><pageSetup orientation=\"landscape\"/></worksheet>")
            }
        }
        val detail = buildString {
            append("<worksheet xmlns=\"$ns\"><cols><col min=\"1\" max=\"7\" width=\"26\" customWidth=\"1\"/></cols><sheetData>")
            val rows = listOf(listOf("课程名", "教师", "教室", "星期", "节次", "周次", "备注")) + regular.map { listOf(it.name, it.teacher, roomLabel(it.room), "周${"一二三四五六日"[it.weekday - 1]}", it.periods, it.weeks, it.note) }
            rows.forEachIndexed { i, row -> append("<row r=\"${i+1}\">"); row.forEachIndexed { col, v -> require(v.length <= 32767) { "单元格内容过长" }; append(cell(col, i+1, v, if (i == 0) 1 else 0)) }; append("</row>") }
            append("</sheetData></worksheet>")
        }
        ZipOutputStream(output).use { zip ->
            fun part(path: String, value: String) { zip.putNextEntry(ZipEntry(path)); zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" + value).toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            part("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" + (1..3).joinToString("") { "<Override PartName=\"/xl/worksheets/sheet$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } + "</Types>")
            part("_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"$rel/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            part("xl/workbook.xml", "<workbook xmlns=\"$ns\" xmlns:r=\"$rel\"><sheets>" + names.mapIndexed { i, n -> "<sheet name=\"$n\" sheetId=\"${i+1}\" r:id=\"rId${i+1}\"/>" }.joinToString("") + "</sheets></workbook>")
            part("xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" + (1..3).joinToString("") { "<Relationship Id=\"rId$it\" Type=\"$rel/worksheet\" Target=\"worksheets/sheet$it.xml\"/>" } + "<Relationship Id=\"styles\" Type=\"$rel/styles\" Target=\"styles.xml\"/></Relationships>")
            part("xl/styles.xml", "<styleSheet xmlns=\"$ns\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"12\"/><name val=\"Calibri\"/></font></fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border><left style=\"thin\"/><right style=\"thin\"/><top style=\"thin\"/><bottom style=\"thin\"/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf></cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>")
            part("xl/worksheets/sheet1.xml", sheet(1)); part("xl/worksheets/sheet2.xml", sheet(0)); part("xl/worksheets/sheet3.xml", detail)
        }
    }
}
