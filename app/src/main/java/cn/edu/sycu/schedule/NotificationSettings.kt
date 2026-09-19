package cn.edu.sycu.schedule

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

@Composable
internal fun PermissionsPage() {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++; CourseAlerts.request(context) }
    val calendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { revision++ }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++; CourseAlerts.request(context) }
    val enabled = remember(revision) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    fun open(intent: Intent) {
        try { context.startActivity(intent) }
        catch (_: Exception) { message = "无法打开该系统页面，请在系统设置中进入知序的应用信息。" }
    }
    fun appSettings() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    SettingsSection("通知", "用于今日课程常驻通知和上课前提醒；拒绝不影响课表查看。")
    Text(if (enabled) "应用通知已允许" else "应用通知未允许")
    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        OutlinedButton(onClick = { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("申请通知权限") }
    OutlinedButton(onClick = { open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("系统通知设置") }
    SettingsSection("准时提醒", "允许闹钟与提醒后，可按所选的提前时间安排通知；未允许时系统可能延迟提醒。")
    val exact = remember(revision) { Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }
    Text(if (exact) "精确提醒已可用" else "尚未允许精确提醒")
    if (Build.VERSION.SDK_INT >= 31) OutlinedButton(onClick = { open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) { Text("闹钟与提醒设置") }
    SettingsSection("后台运行", "部分手机需要在应用启动管理中改为手动管理，并允许自启动、关联启动和后台运行。系统名称可能不同，这些开关无法由应用统一读取或代为开启。")
    val exempt = remember(revision) { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
    Text(if (exempt) "系统电池优化：已豁免" else "系统电池优化：未豁免")
    OutlinedButton(onClick = { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("电池优化设置") }
    OutlinedButton(onClick = { open(appSettings()) }) { Text("应用信息 / 启动管理") }
    Text("强行停止应用后，需重新打开才能恢复提醒。厂商省电限制仍可能影响后台通知。", style = MaterialTheme.typography.bodySmall)
    SettingsSection("日历", "仅用于把课程写入本课表专用系统日历；不用于课程通知。")
    val calendarGranted = remember(revision) { listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } }
    Text(if (calendarGranted) "日历权限已允许" else "日历权限未允许")
    OutlinedButton(onClick = { if (calendarGranted) open(appSettings()) else calendar.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }) { Text(if (calendarGranted) "管理日历权限" else "申请日历权限") }
    OutlinedButton(onClick = { open(appSettings()) }) { Text("全部应用权限") }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
internal fun NotificationSettingsPage(table: Timetable?, courses: List<Course>, openPermissions: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AlertPreferences(context) }
    var options by remember { mutableStateOf(prefs.read()) }
    var editingMinutes by remember { mutableStateOf(false) }
    var minutesText by remember { mutableStateOf("") }
    var muted by remember(table?.id) { mutableStateOf(table?.let { prefs.mutedNames(it.id) } ?: emptySet()) }
    var revision by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++; CourseAlerts.request(context) }
    fun save(value: AlertOptions) { options = value; prefs.save(value); CourseAlerts.request(context) }
    val enabled = remember(revision) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    Text("使用当前选中的课表，仅显示和提醒实际当天课程。")
    if (!enabled) Text("通知权限未开启，启用下面选项后仍需在权限管理中授权。", color = MaterialTheme.colorScheme.error)
    OutlinedButton(onClick = openPermissions) { Text("权限管理") }
    SettingsSection("今日课程常驻通知", "显示正在上的课程及剩余时间，或下一节课倒计时；无课与全部结束时显示对应状态。")
    AlertSwitch("显示今日课程", options.ongoing) { save(options.copy(ongoing = it)) }
    Text("常驻通知内容：标题保持不变，第一行显示倒计时和时间，第二行显示教室和教师。", style = MaterialTheme.typography.bodySmall)
    AlertSwitch("倒计时", options.ongoingCountdown) { save(options.copy(ongoingCountdown = it)) }
    AlertSwitch("上课时间", options.ongoingTime) { save(options.copy(ongoingTime = it)) }
    AlertSwitch("教室", options.ongoingRoom) { save(options.copy(ongoingRoom = it)) }
    AlertSwitch("教师", options.ongoingTeacher) { save(options.copy(ongoingTeacher = it)) }
    Text("仅展示今天，不提供日期切换按钮。部分系统允许划掉常驻通知，课程状态变化或重新打开应用时会更新。", style = MaterialTheme.typography.bodySmall)
    SettingsSection("上课前提醒")
    AlertSwitch("开启课程提醒", options.reminders) { save(options.copy(reminders = it)) }
    OutlinedButton(onClick = { minutesText = options.minutes.toString(); editingMinutes = true }) { Text("提前 ${options.minutes} 分钟 · 修改") }
    if (editingMinutes) {
        val minutes = reminderMinutes(minutesText)
        AlertDialog(
            onDismissRequest = { editingMinutes = false },
            title = { Text("提前提醒时间") },
            text = {
                OutlinedTextField(
                    value = minutesText, onValueChange = { minutesText = it },
                    label = { Text("提前分钟数") },
                    supportingText = { Text("请输入 1–60 的整数，默认 20 分钟") },
                    isError = minutes == null, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = { TextButton(enabled = minutes != null, onClick = {
                minutes?.let { save(options.copy(minutes = it)); editingMinutes = false }
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editingMinutes = false }) { Text("取消") } },
        )
    }
    SettingsSection("选择提醒课程", "仅对当前课表生效，同名课程共用开关；关闭后不再发送上课前提醒，今日常驻通知仍展示完整安排。新课程默认允许提醒。")
    if (table == null) Text("暂无课表，请先创建或同步课表。")
    else {
        Text(table.name, style = MaterialTheme.typography.titleSmall)
        if (!options.reminders) Text("课程提醒总开关未开启，可以先选择需要提醒的课程。", style = MaterialTheme.typography.bodySmall)
        val names = (courses + MakeupStore.courses(context, table)).filter { it.timetableId == table.id && !it.deleted }.map { it.name }.distinct().sorted()
        if (names.isEmpty()) Text("当前课表暂无课程。")
        names.forEach { name ->
            AlertSwitch(name, name !in muted) { enabled ->
                muted = if (enabled) muted - name else muted + name
                prefs.saveMutedNames(table.id, muted)
                CourseAlerts.request(context)
            }
        }
    }
    SettingsSection("提醒内容", "可自由选择；全部关闭时仅提示即将上课。通知可能在锁屏显示，请按需设置系统通知隐私。")
    AlertSwitch("课程名称", options.name) { save(options.copy(name = it)) }
    AlertSwitch("上课时间", options.time) { save(options.copy(time = it)) }
    AlertSwitch("教室", options.room) { save(options.copy(room = it)) }
    SettingsSection("通知通道", "若应用通知已允许但仍未收到提醒，请检查通道是否被单独关闭。")
    listOf(CourseAlerts.STATUS_CHANNEL to "今日课程安排", CourseAlerts.REMINDER_CHANNEL to "上课前提醒").forEach { (id, label) ->
        val allowed = remember(revision) { context.getSystemService(NotificationManager::class.java).getNotificationChannel(id)?.importance != NotificationManager.IMPORTANCE_NONE }
        Text("$label：${if (allowed) "通道可用" else "通道已关闭"}", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            CourseAlerts.channels(context)
            try { context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, id)) }
            catch (_: Exception) { openPermissions() }
        }) { Text("设置$label") }
    }
}

@Composable
private fun AlertSwitch(title: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f)); Switch(checked, change)
    }
}
