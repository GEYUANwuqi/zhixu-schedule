package cn.edu.sycu.schedule

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate

internal object HolidayStore {
    val revision = mutableIntStateOf(0)
    fun dates(context: Context, tableId: String): Set<LocalDate> = context.getSharedPreferences("holidays", 0)
        .getStringSet(tableId, emptySet())!!.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet()
    fun keys(context: Context, tableId: String): Set<String> = context.getSharedPreferences("holidays", 0).getStringSet("lessons:$tableId", emptySet())!!.toSet()
    fun save(context: Context, tableId: String, keys: Set<String>) {
        check(context.getSharedPreferences("holidays", 0).edit().remove(tableId).putStringSet("lessons:$tableId", keys).commit())
        revision.intValue++
        TodayWidget.refresh(context)
        CourseAlerts.request(context)
    }
}

internal fun holidayKey(table: Timetable, course: Course, date: LocalDate, periods: List<Int>): String =
    "${course.id}:${date.atTime(java.time.LocalTime.parse(timesFor(table)[periods.first() - 1].first)).atZone(schoolZone).toInstant().toEpochMilli()}"

@Composable
internal fun HolidayManager(table: Timetable, courses: List<Course>) {
    var opened by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { opened = true }) { Text("放假调整") }
    if (opened) HolidayPreview(table, courses) { opened = false }
}

@Composable
private fun HolidayPreview(table: Timetable, courses: List<Course>, close: () -> Unit) {
    val context = LocalContext.current
    val display = remember { SchedulePreferences(context).read() }
    var selected by remember(table.id) { mutableStateOf(HolidayStore.keys(context, table.id) +
        HolidayStore.dates(context, table.id).flatMap { date -> todayLessons(table, courses, date).map(::occurrenceKey) }) }
    var week by remember { mutableIntStateOf(weekOn(table).coerceIn(1, table.weekCount)) }
    var error by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<List<LessonBlock>?>(null) }
    fun dateOf(day: Int) = firstWeekMonday(table).plusDays(((week - 1) * 7 + day - 1).toLong())
    fun blockKey(block: LessonBlock) = holidayKey(table, block.course, dateOf(block.course.weekday), block.periods)
    fun toggleBlock(block: LessonBlock) { val key = blockKey(block); selected = if (key in selected) selected - key else selected + key }
    fun toggle(day: Int) {
        val keys = todayLessons(table, courses, dateOf(day)).map(::occurrenceKey).toSet()
        selected = if (keys.all { it in selected }) selected - keys else selected + keys
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("选择放假课程", style = MaterialTheme.typography.titleLarge)
                Text("点击课程单独选择，点击日期全选当天课程；已全选时再次点击取消。可跨周多选，原课程保留。", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = week > 1, onClick = { week-- }) { Text("上一周") }
                    Text("第 $week 周")
                    TextButton(enabled = week < table.weekCount, onClick = { week++ }) { Text("下一周") }
                }
                key(table.id, week) {
                    ScheduleGrid(table, courses, week, Modifier.weight(1f).fillMaxWidth(), display = display,
                        selectionMode = true, holidayKeys = selected,
                        onDaySelect = ::toggle, onWeekSwipe = { week = (week + it).coerceIn(1, table.weekCount) },
                        onStackClick = { if (it.size == 1) toggleBlock(it.first()) else picking = it }, onClick = { toggleBlock(LessonBlock(it, it.periodList(), 0)) })
                }
                Text("已选择 ${selected.size} 次课程")
                if (error) Text("保存失败，请检查设备空间后重试", color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = close) { Text("取消") }
                    TextButton(onClick = { selected = emptySet() }) { Text("清除全部") }
                    Button(onClick = { try { HolidayStore.save(context, table.id, selected); close() } catch (_: Exception) { error = true } }) { Text("保存") }
                }
            }
        }
    }
    picking?.let { blocks -> AlertDialog(onDismissRequest = { picking = null }, title = { Text("选择放假课程") }, text = {
        Column { blocks.forEach { block -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(blockKey(block) in selected, { toggleBlock(block) })
            Text("${block.course.name} · ${block.periods.joinToString(",")}节")
        } } }
    }, confirmButton = { TextButton(onClick = { picking = null }) { Text("完成") } }) }
}
