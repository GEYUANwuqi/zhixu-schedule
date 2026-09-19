package cn.edu.sycu.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate

@Composable
internal fun MakeupPreview(table: Timetable, courses: List<Course>, close: () -> Unit) {
    val context = LocalContext.current
    val display = remember { SchedulePreferences(context).read() }
    var week by remember { mutableIntStateOf(weekOn(table).coerceIn(1, table.weekCount)) }
    var sourceDate by remember { mutableStateOf<LocalDate?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var destination by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(emptyList<Course>()) }
    var targetDate by remember { mutableStateOf<LocalDate?>(null) }
    var periods by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<List<LessonBlock>?>(null) }
    val shown = remember(table, courses, week) { makeupPreviewCourses(table, courses, week) }
    val preview = remember(chosen, targetDate, periods) { targetDate?.let { date -> runCatching { makeupsFromSelection(table, chosen, date, periods.takeIf { chosen.size == 1 }) }.getOrNull() } }
    val existing = if (destination) MakeupStore.courses(context, table).filter { weekOn(table, LocalDate.parse(it.id.substringAfterLast(':'))) == week } else emptyList()
    val gridCourses = shown + existing + (preview?.map { it.course(table) } ?: emptyList())
    fun dateOf(day: Int) = firstWeekMonday(table).plusDays(((week - 1) * 7 + day - 1).toLong())
    fun chooseDay(day: Int) {
        error = null
        if (destination) targetDate = dateOf(day)
        else {
            selected = toggleMakeupDay(shown, day, selected)
            sourceDate = dateOf(day).takeIf { selected.isNotEmpty() }
        }
    }
    fun chooseCourse(c: Course) {
        val date = dateOf(c.weekday)
        if (destination) { targetDate = date; return }
        selected = if (sourceDate != date) setOf(c.id) else if (c.id in selected) selected - c.id else selected + c.id
        sourceDate = date; error = null
    }
    fun changeWeek(delta: Int) {
        val next = (week + delta).coerceIn(1, table.weekCount)
        if (next != week) {
            week = next
            if (destination) targetDate = null else { sourceDate = null; selected = emptySet() }
        }
    }
    Dialog(onDismissRequest = { if (destination) { destination = false; targetDate = null; sourceDate = null; selected = emptySet() } else close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (destination) "选择补课目标日期" else "选择补课来源", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { if (destination) { destination = false; targetDate = null; sourceDate = null; selected = emptySet() } else close() }) { Text(if (destination) "返回" else "取消") }
                }
                Text(if (destination) "点击一天预览补课；切换日期会移除上一次预览，确认后才保存。" else "点击星期选择当天全部课程，点击卡片单独选课。每次只能选择一天，原课程保留。", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = week > 1, onClick = { changeWeek(-1) }) { Text("上一周") }
                    Text("第 $week 周")
                    TextButton(enabled = week < table.weekCount, onClick = { changeWeek(1) }) { Text("下一周") }
                }
                // Recreate interaction nodes when their calendar changes. Cached local
                // callbacks must never pair the new header with the previous week's data.
                key(table.id, week, destination) {
                ScheduleGrid(table, gridCourses, week, Modifier.weight(1f).fillMaxWidth(), display = display, selectionMode = true,
                    selectedDate = if (destination) targetDate else sourceDate, selectableIds = if (destination) emptySet() else selected,
                    onWeekSwipe = ::changeWeek, onDaySelect = ::chooseDay, onStackClick = { blocks ->
                        if (destination) chooseDay(blocks.first().course.weekday)
                        else if (blocks.size == 1) chooseCourse(blocks.first().course) else picking = blocks
                    }, onClick = ::chooseCourse)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!destination) {
                    Text(sourceDate?.let { "$it · 已选 ${selected.size} 节" } ?: "请选择来源日期", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = selected.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                        chosen = shown.filter { it.id in selected }; periods = chosen.singleOrNull()?.periods ?: ""
                        destination = true; targetDate = null; error = null
                    }) { Text("确认来源，选择目标日期") }
                } else {
                    if (chosen.size == 1) OutlinedTextField(periods, { periods = it }, label = { Text("目标节次") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("${sourceDate ?: "来源日期"} → ${targetDate ?: "请选择一天"} · ${chosen.size} 节；原课程不变", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = preview != null, modifier = Modifier.fillMaxWidth(), onClick = {
                        try { MakeupStore.addAll(context, requireNotNull(preview)); close() }
                        catch (_: Exception) { error = "无法保存，请检查节次、补课数量和设备空间。" }
                    }) { Text("确认添加补课") }
                }
            }
        }
    }
    picking?.let { blocks -> AlertDialog(onDismissRequest = { picking = null }, title = { Text("选择这一天的课程") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            blocks.forEach { block -> Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(block.course.id in selected, { chooseCourse(block.course) })
                Text("${block.course.name} · ${block.periods.joinToString(",")}节")
            } }
        }
    }, confirmButton = { TextButton(onClick = { picking = null }) { Text("完成") } }) }
}
