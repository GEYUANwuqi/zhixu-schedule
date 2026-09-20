package cn.edu.sycu.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

data class RulePreview(val original: Course, val result: Course, val hits: List<IndexedValue<CourseRule>>) {
    val changed get() = original != result
}
data class SyncProposal(val previousTable: Timetable?, val table: Timetable, val previous: List<Course>, val rows: List<RulePreview>)
fun previewCourseRules(c: Course, rules: List<CourseRule>, table: Timetable) =
    RulePreview(c, applyCourseRules(c, rules, table), rules.withIndex().filter { it.value.matches(c) })

internal fun coursePreviewFields(c: Course) = listOf(
    "课程名" to c.name, "教师" to c.teacher, "教室" to c.room,
    "星期" to "周${"一二三四五六日"[c.weekday - 1]}", "节次" to c.periods,
    "周次" to c.weeks, "备注" to c.note,
)

@Composable
internal fun SyncPreviewDialog(proposal: SyncProposal, busy: Boolean, cancel: () -> Unit, confirm: () -> Unit) {
    val protected = proposal.previous.filter { it.edited || it.deleted }.map { it.id }.toSet()
    Dialog(onDismissRequest = { if (!busy) cancel() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("同步预览", style = MaterialTheme.typography.headlineSmall)
                Text("${proposal.table.name} · ${proposal.rows.size} 项课程，${proposal.rows.count { it.changed }} 项经规则修改")
                Text("尚未写入课表。规则按列表顺序执行，均匹配学校原始内容；后面的规则覆盖已填写字段。手动课程、本地编辑和删除标记继续保留，补课不受影响。", style = MaterialTheme.typography.bodySmall)
                if (proposal.rows.isEmpty()) Text("学校返回空课表：确认后将移除原有未手动修改的同步课程。", color = MaterialTheme.colorScheme.error)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(proposal.rows) { _, row ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(row.original.name, style = MaterialTheme.typography.titleMedium)
                                Text(if (row.changed) "规则已修改" else if (row.hits.isEmpty()) "未命中规则 · 内容不变" else "命中规则 · 内容不变", color = MaterialTheme.colorScheme.primary)
                                if (row.original.id in protected) Text("此课程有本地编辑或删除标记，将保留本地状态，不覆盖。", color = MaterialTheme.colorScheme.error)
                                row.hits.forEach { hit -> Text("规则 ${hit.index + 1}：${hit.value.matchField} 完全匹配「${hit.value.matchValue}」", style = MaterialTheme.typography.bodySmall) }
                                val before = coursePreviewFields(row.original)
                                val after = coursePreviewFields(row.result)
                                before.zip(after).forEach { (a, b) ->
                                    Text("${a.first}：${a.second.ifBlank { "（空）" }}" + if (a.second != b.second) " → ${b.second.ifBlank { "（空）" }}" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (a.second != b.second) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(enabled = !busy, onClick = cancel, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(enabled = !busy, onClick = confirm, modifier = Modifier.weight(1f)) { Text(if (busy) "正在同步…" else "确认同步") }
                }
            }
        }
    }
}
