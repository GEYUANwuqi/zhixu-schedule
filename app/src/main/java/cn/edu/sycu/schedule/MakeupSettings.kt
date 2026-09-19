package cn.edu.sycu.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
internal fun MakeupManager(table: Timetable, courses: List<Course>) {
    val context = LocalContext.current
    val revision = MakeupStore.revision.intValue
    val entries = remember(revision, table.id) { MakeupStore.read(context).filter { it.tableId == table.id }.sortedBy { it.date } }
    var opened by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Makeup?>(null) }
    var deleting by remember { mutableStateOf<Makeup?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    OutlinedButton(onClick = { editing = null; opened = true }) { Text("补课调整") }
    Text("仅叠加指定日期的补课，不修改原课程；红色角标表示补课。非教学周安排会显示在本列表及对应日期的小组件、通知中。", style = MaterialTheme.typography.bodySmall)
    entries.forEach { entry ->
        Row(Modifier.fillMaxWidth()) {
            Text("${entry.date} · ${entry.name}\n第${entry.periods}节", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { editing = entry; opened = true }) { Text("编辑") }
            TextButton(onClick = { deleting = entry }) { Text("撤销") }
        }
    }
    if (opened) MakeupEditor(table, courses, editing) { opened = false }
    deleting?.let { entry -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("撤销这次补课？") }, text = { Text("${entry.date} · ${entry.name}，原课程不受影响。") }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }, confirmButton = {
        TextButton(onClick = { try { MakeupStore.remove(context, entry.id); deleting = null } catch (_: Exception) { error = "撤销失败，请检查设备空间" } }) { Text("撤销补课") }
    }) }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
internal fun MakeupEditor(table: Timetable, courses: List<Course>, initial: Makeup?, close: () -> Unit) {
    if (initial == null) { MakeupPreview(table, courses, close); return }
    val context = LocalContext.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var teacher by remember { mutableStateOf(initial?.teacher ?: "") }
    var room by remember { mutableStateOf(initial?.room ?: "") }
    var date by remember { mutableStateOf(initial?.date ?: today().toString()) }
    var periods by remember { mutableStateOf(initial?.periods ?: "1,2") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var choose by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = close, title = { Text(if (initial == null) "添加一次补课" else "编辑这次补课") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { choose = true }) { Text("从当前课表选择课程") }
            OutlinedTextField(name, { name = it }, label = { Text("课程名称") }, singleLine = true)
            OutlinedTextField(date, { date = it }, label = { Text("日期 YYYY-MM-DD") }, singleLine = true)
            OutlinedTextField(periods, { periods = it }, label = { Text("节次，例如 3,4 或 3-4") }, singleLine = true)
            OutlinedTextField(room, { room = it }, label = { Text("教室") }, singleLine = true)
            OutlinedTextField(teacher, { teacher = it }, label = { Text("教师") }, singleLine = true)
            OutlinedTextField(note, { note = it }, label = { Text("备注") })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, dismissButton = { TextButton(onClick = close) { Text("取消") } }, confirmButton = {
        TextButton(onClick = {
            try {
                val entry = Makeup(id = initial?.id ?: java.util.UUID.randomUUID().toString(), tableId = table.id,
                    date = LocalDate.parse(date.trim()).toString(), name = name.trim(), teacher = teacher.trim(), room = room.trim(), periods = periods.trim(), note = note)
                require(entry.name.isNotBlank())
                makeupOccurrences(table, entry.course(table))
                MakeupStore.save(context, entry); close()
            } catch (_: Exception) { error = "无法保存，请检查日期、课程名、有效节次和设备空间。" }
        }) { Text("保存补课") }
    })
    if (choose) AlertDialog(onDismissRequest = { choose = false }, title = { Text("选择课程") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val choices = courses.filter { it.timetableId == table.id && !it.deleted && !it.isMakeup }.distinctBy { Triple(it.name, it.teacher, it.room) }
            if (choices.isEmpty()) Text("暂无课程，可以返回直接填写。")
            choices.forEach { c -> TextButton(onClick = { name = c.name; teacher = c.teacher; room = c.room; periods = c.periods; note = c.note; choose = false }) { Text("${c.name} · ${courseLocation(c)}") } }
        }
    }, confirmButton = { TextButton(onClick = { choose = false }) { Text("返回") } })
}
