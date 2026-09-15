package cn.edu.sycu.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun RuleSettings(preferences: SchedulePreferences) {
    var rules by remember { mutableStateOf(preferences.rules) }
    var editing by remember { mutableStateOf<Int?>(null) }
    Text("规则按学校返回的原始字段完全匹配，不忽略空格。留空或显示“默认”的修改项保持原值；多条命中按列表顺序覆盖填写项。下次同步生效，手动修改仍保留。")
    Button(onClick = { editing = -1 }) { Text("新增规则") }
    if (rules.isEmpty()) Text("暂无规则。例如：教室完全匹配 G411 → 教室改为建工4楼。")
    rules.forEachIndexed { index, rule ->
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("${index + 1}. ${rule.matchField} = ${rule.matchValue}")
                Text(listOf("课程名" to rule.name, "教师" to rule.teacher, "教室" to rule.room, "星期" to (rule.weekday?.toString() ?: ""), "节次" to rule.periods, "周次" to rule.weeks, "备注" to rule.note).filter { it.second.isNotBlank() }.joinToString("；") { "${it.first} → ${it.second}" })
                Row {
                    TextButton(onClick = { editing = index }) { Text("编辑") }
                    TextButton(onClick = { rules = rules.toMutableList().apply { removeAt(index) }; preferences.rules = rules }) { Text("删除") }
                    TextButton(enabled = index > 0, onClick = { rules = rules.toMutableList().apply { add(index - 1, removeAt(index)) }; preferences.rules = rules }) { Text("上移") }
                }
            }
        }
    }
    editing?.let { index ->
        key(index) { RuleEditor(rules.getOrNull(index), { editing = null }) { value ->
            rules = rules.toMutableList().apply { if (index < 0) add(value) else set(index, value) }
            preferences.rules = rules
            editing = null
        } }
    }
}

@Composable
private fun RuleEditor(initial: CourseRule?, close: () -> Unit, save: (CourseRule) -> Unit) {
    var field by remember { mutableStateOf(initial?.matchField ?: "课程名") }
    var match by remember { mutableStateOf(initial?.matchValue ?: "") }
    var menu by remember { mutableStateOf(false) }
    var values by remember { mutableStateOf(listOf(initial?.name ?: "", initial?.teacher ?: "", initial?.room ?: "", initial?.weekday?.toString() ?: "", initial?.periods ?: "", initial?.weeks ?: "", initial?.note ?: "")) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = close) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(20.dp).heightIn(max = 560.dp)) {
                Text(if (initial == null) "新增同步规则" else "编辑同步规则", style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        OutlinedButton(onClick = { menu = true }) { Text("匹配字段：$field") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            ruleFields.forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { field = item; menu = false }) }
                        }
                    }
                    OutlinedTextField(match, { match = it }, label = { Text("完全匹配值") }, singleLine = true)
                    Text("只填写需要修改的项目，空白表示默认。备注填写后替换原备注。")
                    listOf("课程名", "教师", "教室", "星期（1–7）", "节次（例如 5-6）", "周次（例如 1-16）", "备注").forEachIndexed { i, name ->
                        OutlinedTextField(values[i], { text -> values = values.toMutableList().apply { set(i, text) } }, label = { Text(name) }, placeholder = { Text("默认 · 使用同步原值") }, singleLine = i != 6)
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = close) { Text("取消") }
                    Button(onClick = {
                        try {
                            val day = if (values[3].isBlank()) null else values[3].toIntOrNull() ?: error("星期应为 1–7")
                            val rule = CourseRule(field, match, values[0], values[1], values[2], day, values[4], values[5], values[6])
                            rule.validate(); save(rule)
                        } catch (e: Exception) { error = e.message ?: "请检查规则" }
                    }) { Text("保存") }
                }
            }
        }
    }
}
