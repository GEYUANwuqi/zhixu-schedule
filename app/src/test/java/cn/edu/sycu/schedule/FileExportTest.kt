package cn.edu.sycu.schedule

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileExportTest {
    private val table = Timetable(id = "t", start = "2026-08-24")
    private val course = Course(id = "a", timetableId = "t", name = "=数学<&>", teacher = "教师", room = "B101", periods = "5,6", weeks = "1,3", note = "第一行\n第二行")
    private fun parts(courses: List<Course>): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        ExcelExport.encode(out, table, courses)
        return buildMap { ZipInputStream(out.toByteArray().inputStream()).use { zip -> while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()) } } }
    }
    @Test fun excelIsEditableEscapedAndAlignedWithoutMakeups() {
        val files = parts(listOf(course, course.copy(id = "makeup:test:2026-09-21", name = "补课不导出"), course.copy(id = "other", timetableId = "other"), course.copy(id = "deleted", deleted = true)))
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        files.values.forEach { parser.parse(it.inputStream()) }
        val odd = parser.parse(files.getValue("xl/worksheets/sheet1.xml").inputStream())
        assertEquals(0, odd.getElementsByTagName("f").length)
        val cells = odd.getElementsByTagName("c")
        val values = (0 until cells.length).associate { val e = cells.item(it) as org.w3c.dom.Element; e.getAttribute("r") to e.textContent }
        assertEquals("", values["B4"])
        assertTrue(values.getValue("B8").contains(course.name))
        assertTrue(values.getValue("B8").contains("13:30–15:10"))
        assertFalse(values.values.any { it.contains("补课不导出") })
        assertTrue(files.getValue("xl/worksheets/sheet1.xml").toString(Charsets.UTF_8).contains("B8:B9"))
        assertFalse(files.getValue("xl/worksheets/sheet2.xml").toString(Charsets.UTF_8).contains("数学"))
        val detail = parser.parse(files.getValue("xl/worksheets/sheet3.xml").inputStream())
        assertEquals(2, detail.getElementsByTagName("row").length)
        assertTrue(parts(emptyList()).containsKey("xl/worksheets/sheet2.xml"))
    }
}
