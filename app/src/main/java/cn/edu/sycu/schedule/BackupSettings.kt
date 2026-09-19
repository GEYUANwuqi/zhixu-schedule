package cn.edu.sycu.schedule

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun BackupSettings(enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("知序备份-${today()}") }
    var pending by remember { mutableStateOf<AppBackup?>(null) }
    var exportedFile by rememberSaveable { mutableStateOf<String?>(null) }
    val writer = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val file = exportedFile?.let { File(context.cacheDir, it) }
        exportedFile = null
        if (uri == null) file?.delete()
        else if (file != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    try { requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { target -> file.inputStream().use { it.copyTo(target) } } }
                    finally { file.delete() }
                }
                notice = "完整备份已导出"
            } catch (e: Exception) { if (e is CancellationException) throw e; notice = "备份写入失败，请检查文件位置和可用空间" }
            finally { busy = false }
        }
    }
    val reader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                pending = withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openInputStream(uri)).use { BackupCodec.read(it) } }
            } catch (e: Exception) { if (e is CancellationException) throw e; notice = "无法读取备份：文件无效、损坏、超过16 MB或版本不受支持，现有数据未修改" }
            finally { busy = false }
        }
    }
    SettingsSection("完整备份与恢复", "包含全部课表和课程、同步规则及顺序、主题与课程颜色、显示、小组件和通知设置。")
    Text("不包含应用背景、小组件背景、学校登录凭证和认证状态。恢复时保留本机背景与登录状态；不会修改已导出的文件和系统日历。", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(enabled = enabled && !busy, onClick = { naming = true }) { Text("导出完整备份") }
    OutlinedButton(enabled = enabled && !busy, onClick = { reader.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }) { Text("从备份恢复") }
    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (naming) AlertDialog(onDismissRequest = { naming = false }, title = { Text("备份名称") }, text = {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("保存到备份内的名称") }, singleLine = true, isError = name.isBlank() || name.length > 120)
    }, dismissButton = { TextButton(onClick = { naming = false }) { Text("取消") } }, confirmButton = {
        TextButton(enabled = name.isNotBlank() && name.length <= 120, onClick = {
            naming = false
            scope.launch {
                busy = true
                try {
                    val text = BackupStore.export(context, name.trim())
                    val file = withContext(Dispatchers.IO) {
                        File.createTempFile("full-backup-", ".json", context.cacheDir).also { it.writeText(text, Charsets.UTF_8) }
                    }
                    exportedFile = file.name
                    writer.launch("zhixu-backup-${today()}.json")
                } catch (e: Exception) { if (e is CancellationException) throw e; notice = "无法生成备份，请检查数据和可用空间" }
                finally { busy = false }
            }
        }) { Text("选择保存位置") }
    })
    pending?.let { backup ->
        AlertDialog(onDismissRequest = { if (!busy) pending = null }, title = { Text("确认恢复完整备份") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("名称：${backup.name}")
                Text("创建时间：${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX").format(Instant.parse(backup.createdAt).atZone(ZoneId.systemDefault()))}")
                Text("${backup.schedules.size} 张课表 · ${backup.courseCount} 条课程（含删除标记） · ${backup.makeupCount} 条补课 · ${backup.rulesCount} 条同步规则")
                backup.schedules.forEach { Text("${it.table.name}：${it.courses.count { c -> !c.deleted }} 条可见课程") }
                Text("同时恢复主题、颜色、显示、小组件与通知设置。")
                Text("确认后将替换现有全部课表、课程、规则及上述设置。背景图片、学校登录状态、系统日历保持不变。", color = MaterialTheme.colorScheme.error)
            }
        }, dismissButton = { TextButton(enabled = !busy, onClick = { pending = null }) { Text("取消") } }, confirmButton = {
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    withContext(NonCancellable) {
                    busy = true
                    BackupStore.restoring = true
                    BackupStore.inProgress.value = true
                    try {
                        BackupStore.restore(context, backup)
                        pending = null
                        BackupStore.revision.intValue++
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        BackupStore.recoveryRequired.value = BackupStore.recoveryPending(context)
                        notice = if (BackupStore.recoveryRequired.value) "恢复中断，恢复前数据的保护副本仍保留在本机。请释放空间后重试回退。" else "恢复失败，已保留恢复前数据。请检查设备空间后重试。"
                        pending = null
                    } finally {
                        BackupStore.restoring = BackupStore.recoveryRequired.value
                        BackupStore.inProgress.value = false
                        busy = false
                        TodayWidget.refresh(context)
                        CourseAlerts.request(context)
                    }
                    }
                }
            }) { Text("确认替换并恢复") }
        })
    }
    if (busy && !BackupStore.inProgress.value) {
        BackHandler { }
        AlertDialog(onDismissRequest = {}, title = { Text("正在处理备份") }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {})
    }
}

@Composable
internal fun BackupRecoveryGate() {
    if (BackupStore.inProgress.value) {
        BackHandler { }
        AlertDialog(onDismissRequest = {}, title = { Text("正在恢复备份") }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {})
        return
    }
    if (!BackupStore.recoveryRequired.value) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("恢复前的数据保护副本仍保留。请检查设备空间后重试，回退完成前不能继续编辑。") }
    BackHandler { }
    AlertDialog(onDismissRequest = {}, title = { Text("需要完成数据回退") }, text = { Text(notice) }, confirmButton = {
        TextButton(enabled = !busy, onClick = { scope.launch {
            withContext(NonCancellable) {
                busy = true
                try {
                    withContext(Dispatchers.IO) { BackupStore.recover(context) }
                    BackupStore.recoveryRequired.value = false
                    BackupStore.restoring = false
                    BackupStore.revision.intValue++
                    TodayWidget.refresh(context)
                    CourseAlerts.request(context)
                } catch (_: Exception) { notice = "仍无法回退，请检查设备空间；保护副本未删除。" }
                finally { busy = false }
            }
        } }) { Text(if (busy) "正在回退" else "重试回退") }
    })
}
