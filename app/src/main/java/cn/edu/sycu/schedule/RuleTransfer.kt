package cn.edu.sycu.schedule

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.InputStream

internal object RuleTransfer {
    const val LIMIT = 256 * 1024
    fun encode(rules: List<CourseRule>): String {
        require(rules.size <= 500) { "规则数量不能超过 500 条" }
        rules.forEach { it.validate() }
        val text = JSONObject().put("format", "zhixu-sync-rules").put("version", 1)
            .put("rules", org.json.JSONArray(RuleCodec.encode(rules))).toString(2)
        require(text.toByteArray(Charsets.UTF_8).size <= LIMIT) { "规则文件超过 256 KiB" }
        return text
    }
    fun read(input: InputStream): List<CourseRule> {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(bytes.size() + n <= LIMIT) { "规则文件超过 256 KiB" }
            bytes.write(buffer, 0, n)
        }
        return decode(bytes.toString("UTF-8"))
    }
    fun decode(text: String): List<CourseRule> {
        require(text.toByteArray(Charsets.UTF_8).size <= LIMIT) { "规则文件超过 256 KiB" }
        val root = JSONObject(text.removePrefix("\uFEFF"))
        require(root.opt("format") == "zhixu-sync-rules" && root.opt("version") == 1) { "不是支持的同步规则文件" }
        val rows = root.getJSONArray("rules")
        require(rows.length() <= 500) { "规则数量不能超过 500 条" }
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            for (key in listOf("matchField", "matchValue", "name", "teacher", "room", "periods", "weeks", "note")) {
                require(row.opt(key) is String) { "第 ${i + 1} 条规则的 $key 应为文本" }
            }
            val day = row.opt("weekday")
            require(day is Int && day in 0..7) { "第 ${i + 1} 条规则星期无效" }
        }
        return RuleCodec.decode(rows.toString()).also { rules -> rules.forEach { it.validate() } }
    }
}

@Composable
internal fun RuleTransferButtons(rules: List<CourseRule>, save: (List<CourseRule>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<CourseRule>?>(null) }
    var replace by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<String?>(null) }
    val writer = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot = exported
        exported = null
        if (uri != null && snapshot != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { it.write(snapshot) } }
                notice = "规则已导出"
            } catch (e: Exception) { if (e is CancellationException) throw e; notice = "导出失败，请检查文件位置与空间" }
            finally { busy = false }
        }
    }
    val reader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                pending = withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openInputStream(uri)).use { RuleTransfer.read(it) } }
                replace = false
            } catch (e: Exception) { if (e is CancellationException) throw e; notice = "导入失败：请检查规则文件格式、字段和大小" }
            finally { busy = false }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = { reader.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }) { Text("导入 JSON") }
        OutlinedButton(enabled = !busy, onClick = {
            try { exported = RuleTransfer.encode(rules); writer.launch("zhixu-sync-rules-${today()}.json") }
            catch (e: Exception) { notice = e.message ?: "无法导出规则" }
        }) { Text("导出 JSON") }
    }
    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    pending?.let { incoming ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("导入同步规则") }, text = {
            Column {
                Text("文件包含 ${incoming.size} 条规则。默认追加到现有 ${rules.size} 条规则之后，顺序保持不变；下次同步生效。")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(replace, { replace = it })
                    Text("替换现有全部规则")
                }
                if (replace) Text("确认后将删除原有规则，只保留文件中的规则。")
            }
        }, dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } }, confirmButton = {
            TextButton(onClick = {
                val result = if (replace) incoming else rules + incoming
                if (result.size > 500) notice = "导入后超过 500 条，请减少规则或选择替换"
                else { save(result); pending = null; notice = "已导入 ${incoming.size} 条规则" }
            }) { Text(if (replace) "确认替换" else "追加导入") }
        })
    }
}
