package cn.edu.sycu.schedule

import android.Manifest
import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.time.LocalDate
import kotlinx.coroutines.*
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private var openTodayVersion by mutableIntStateOf(0)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("open_today", false)) openTodayVersion++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var consented by remember { mutableStateOf(PrivacyConsent.accepted(this@MainActivity)) }
            var consentError by remember { mutableStateOf(false) }
            var themeColor by remember { mutableIntStateOf(SchedulePreferences(this@MainActivity).themeColor) }
            MaterialTheme(
                colorScheme =
                    lightColorScheme(
                        primary = Color(themeColor),
                        background = Color(0xfff8f7ef),
                        surface = Color(0xfff8f7ef),
                    )
            ) {
                if (consented) {
                    App(openTodayVersion, onThemeColor = { themeColor = it })
                } else {
                    PrivacyGate(onAgree = {
                        if (PrivacyConsent.accept(this@MainActivity)) {
                            (application as ScheduleApplication).initializeConsentedServices()
                            consented = true
                        } else consentError = true
                    }, onDecline = { finishAndRemoveTask() })
                    if (consentError) AlertDialog(onDismissRequest = { consentError = false },
                        title = { Text("无法保存选择") }, text = { Text("请检查设备空间后重试。") },
                        confirmButton = { TextButton(onClick = { consentError = false }) { Text("知道了") } })
                }
            }
        }
    }
}

private val green = Color(0xffe2efdb)
private val courseColors =
    listOf(
        Color(0xffe4ebd5),
        Color(0xffe7def0),
        Color(0xfff5e1cb),
        Color(0xffd9e8ee),
        Color(0xfff0dce0),
        Color(0xffe8e7cb),
    )

@Composable
fun App(openTodayVersion: Int = 0, onThemeColor: (Int) -> Unit = {}) {
    val context = LocalContext.current
    val view = LocalView.current
    var backdrop by remember { mutableStateOf<ImageBitmap?>(null) }
    val db = remember { ScheduleDb.open(context) }
    val dao = db.dao()
    val tables by dao.timetables().collectAsState(initial = emptyList())
    val allCourses by dao.courses().collectAsState(initial = emptyList())
    DisposableEffect(db) { onDispose { db.close() } }
    var currentDate by remember { mutableStateOf(today()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentDate = today()
            delay(30_000)
        }
    }
    val scope = rememberCoroutineScope()
    val preferences = remember { SchedulePreferences(context) }
    var themeColor by remember { mutableIntStateOf(preferences.themeColor) }
    var display by remember { mutableStateOf(preferences.read()) }
    var widgetLarge by remember { mutableStateOf(preferences.widgetLarge) }
    var backgroundVersion by remember { mutableIntStateOf(0) }
    val appBackground by
        produceState<Bitmap?>(null, backgroundVersion) {
            value = withContext(Dispatchers.IO) { Backgrounds.bitmap(context, BackgroundKind.App) }
        }
    val widgetBackground by
        produceState<Bitmap?>(null, backgroundVersion) {
            value =
                withContext(Dispatchers.IO) { Backgrounds.bitmap(context, BackgroundKind.Widget) }
        }
    val metrics = context.resources.displayMetrics
    val screenAspect = metrics.widthPixels.toFloat() / metrics.heightPixels
    var activeId by remember { mutableStateOf(preferences.activeId) }
    val table = tables.find { it.id == activeId } ?: tables.firstOrNull()
    val courses = allCourses.filter { it.timetableId == table?.id }
    // Feeds the widget preview in settings; recomputed only when the timetable or data changes.
    val todayRows =
        remember(table?.id, table?.start, table?.weekCount, allCourses, currentDate) {
            table?.let { t ->
                todayLessons(t, allCourses.filter { it.timetableId == t.id }, currentDate)
            } ?: emptyList()
        }
    var settings by remember { mutableStateOf(false) }
    BackHandler(settings) { settings = false }
    var pendingSync by remember { mutableStateOf(false) }
    var tableEditor by remember { mutableStateOf<Timetable?>(null) }
    var courseEditor by remember { mutableStateOf<Course?>(null) }
    var detail by remember { mutableStateOf<Course?>(null) }
    var login by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmed by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var authenticated by remember { mutableStateOf(false) }
    fun task(block: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                message = block().takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is AuthFailure && e.reason == "login_required") {
                    authenticated = false
                    message = null
                    login = true
                    return@launch
                }
                pendingSync = false
                message =
                    if (e is IllegalStateException || e is IllegalArgumentException)
                        e.message ?: "操作失败"
                    else "操作未完成，请检查权限、网络或设备空间"
            } finally {
                busy = false
            }
        }
    }
    var pendingJson by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val jsonWriter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val file = pendingJson?.let { java.io.File(context.cacheDir, it) }
        pendingJson = null
        if (uri == null) file?.delete()
        if (uri != null && file != null) task {
            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入所选文件")
                try { output.use { target -> file.inputStream().use { it.copyTo(target) } } }
                finally { file.delete() }
            }
            "JSON 已导出"
        }
    }
    val jsonReader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) task {
            val imported = withContext(Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
                input.use { JsonTransfer.read(it) }
            }
            confirmed = "新建课表「${imported.table.name}」，包含 ${imported.courses.size} 门课程、${imported.table.weekCount} 周。现有课表保留。" to {
                task {
                    dao.importSchedule(imported)
                    activeId = imported.table.id
                    preferences.activeId = imported.table.id
                    settings = false
                    TodayWidget.refresh(context)
                    "JSON 已导入"
                }
            }
            ""
        }
    }
    LaunchedEffect(Unit) {
        try {
            authenticated = withContext(Dispatchers.IO) { NativeCore.request(BuildConfig.INITIALIZE) }.optBoolean("hasLogin")
        } catch (_: Exception) {
            message = "无法读取登录凭证，可清除登录状态后重试"
        }
    }
    var picking by remember { mutableStateOf(BackgroundKind.App) }
    var pendingPhoto by remember { mutableStateOf<android.net.Uri?>(null) }
    val backgroundPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) pendingPhoto = uri
        }
    pendingPhoto?.let { uri ->
        CropPhotoDialog(uri, picking) { selection ->
            pendingPhoto = null
            if (selection != null) task {
                withContext(Dispatchers.IO) { Backgrounds.save(context, picking, uri, selection) }
                backgroundVersion++
                TodayWidget.refresh(context)
                if (picking == BackgroundKind.App) "已更新 App 背景" else "已更新小组件背景"
            }
        }
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            granted ->
            if (granted.values.all { it } && table != null) {
                val t = table
                val snapshot = courses
                task {
                    val n =
                        withContext(Dispatchers.IO) { CalendarExport.write(context, t, snapshot) }
                    "已覆盖本课表专用日历，写入 $n 次课程"
                }
            } else message = "未取得日历权限，课表仍可正常使用"
        }
    var week by
        remember(table?.id) {
            mutableIntStateOf(table?.let { weekOn(it).coerceIn(1, it.weekCount) } ?: 1)
        }
    LaunchedEffect(openTodayVersion) {
        if (openTodayVersion > 0) {
            settings = false
            currentDate = today()
            table?.let { week = weekOn(it).coerceIn(1, it.weekCount) }
        }
    }
    LaunchedEffect(table?.id, table?.start, table?.weekCount, currentDate) {
        table?.let { week = weekOn(it, currentDate).coerceIn(1, it.weekCount) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        currentDate = today()
        table?.let { week = weekOn(it, currentDate).coerceIn(1, it.weekCount) }
        TodayWidget.refresh(context)
    }
    LaunchedEffect(table?.id, allCourses, tables) {
        if (table != null) preferences.activeId = table.id
        TodayWidget.refresh(context)
    }
    fun syncSchool() {
        pendingSync = true
        task {
            val t = table ?: Timetable()
            val data =
                withContext(Dispatchers.IO) {
                    NativeCore.request(BuildConfig.SYNC_TIMETABLE,
                        JSONObject().put("year", t.year).put("semester", t.semester),
                    )
                }
            val incoming = parseCourses(data, t)
            dao.merge(t.copy(times = data.getJSONArray("times").toString()), incoming)
            activeId = t.id
            pendingSync = false
            settings = false
            "已同步 ${incoming.size} 项课程安排；保留手动课程与本地修改"
        }
    }
    val blur by animateDpAsState(if (detail != null) 8.dp else 0.dp, tween(170), label = "backdrop")
    Surface(
        Modifier.fillMaxSize(),
        color =
            if (appBackground == null) MaterialTheme.colorScheme.background else Color.Transparent,
    ) {
        Box(Modifier.fillMaxSize()) {
            appBackground?.let {
                Image(
                    it.asImageBitmap(),
                    null,
                    Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    Modifier.matchParentSize()
                        .background(Color.White.copy(alpha = Backgrounds.VEIL))
                )
            }
            Column(
                Modifier.safeDrawingPadding().fillMaxSize().blur(blur).padding(horizontal = 16.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "知序",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "只关心下一节课",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xff667161),
                        )
                    }
                    TextButton(onClick = { settings = !settings }) {
                        Text(if (settings) "课表" else "设置")
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                AnimatedContent(
                    settings,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        (fadeIn(tween(120)) +
                            slideInHorizontally(tween(140)) { it / 12 }) togetherWith
                            fadeOut(tween(90))
                    },
                    label = "mainPage",
                ) { showingSettings ->
                    Column(Modifier.fillMaxSize()) {
                        if (showingSettings) {
                            ScheduleSettings(
                                tables,
                                table,
                                busy,
                                authenticated,
                                display,
                                onDisplay = {
                                    display = it
                                    preferences.save(it)
                                },
                                themeColor = themeColor,
                                onThemeColor = { themeColor = it; preferences.themeColor = it; onThemeColor(it) },
                                widgetLarge = widgetLarge,
                                onWidgetLarge = {
                                    widgetLarge = it
                                    preferences.widgetLarge = it
                                },
                                lessons = todayRows,
                                appBackground = appBackground?.asImageBitmap(),
                                widgetBackground = widgetBackground?.asImageBitmap(),
                                screenAspect = screenAspect,
                                pickBackground = { kind ->
                                    picking = kind
                                    backgroundPicker.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                                clearBackground = { kind ->
                                    task {
                                        withContext(Dispatchers.IO) {
                                            Backgrounds.clear(context, kind)
                                        }
                                        backgroundVersion++
                                        TodayWidget.refresh(context)
                                        if (kind == BackgroundKind.App) "已移除 App 背景"
                                        else "已移除小组件背景"
                                    }
                                },
                                select = { activeId = it },
                                edit = { tableEditor = it },
                                addCourse = {
                                    table?.let { courseEditor = Course(timetableId = it.id) }
                                },
                                sync = { syncSchool() },
                                calendar = {
                                    permission.launch(
                                        arrayOf(
                                            Manifest.permission.READ_CALENDAR,
                                            Manifest.permission.WRITE_CALENDAR,
                                        )
                                    )
                                },
                                png = {
                                    table?.let { t ->
                                        val snapshot = courses
                                        task {
                                            val file =
                                                withContext(Dispatchers.Default) {
                                                    PngExport.write(context, t, snapshot)
                                                }
                                            PngExport.share(context, file)
                                            "PNG 已生成"
                                        }
                                    }
                                },
                                exportJson = {
                                    table?.let { t ->
                                        val snapshot = courses.toList()
                                        task {
                                            pendingJson = withContext(Dispatchers.IO) {
                                                val text = JsonTransfer.encode(t, snapshot)
                                                java.io.File.createTempFile("schedule-json-", ".json", context.cacheDir).apply { writeText(text, Charsets.UTF_8) }.name
                                            }
                                            jsonWriter.launch("zhixu-${t.year}-${t.semester}.json")
                                            ""
                                        }
                                    }
                                },
                                importJson = { jsonReader.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                                clear = {
                                    table?.let { t ->
                                        confirmed =
                                            "清空这张课表的所有课程和同步修改记录？" to
                                                {
                                                    task {
                                                        dao.clearCourses(t.id)
                                                        "本地课表已清空"
                                                    }
                                                }
                                    }
                                },
                                delete = {
                                    table?.let { t ->
                                        confirmed =
                                            "删除「${t.name}」及本地课程？已导入的日历保留。" to
                                                {
                                                    task {
                                                        dao.deleteTable(t.id)
                                                        "课表已删除"
                                                    }
                                                }
                                    }
                                },
                                login = {
                                    pendingSync = false
                                    login = true
                                },
                                checkLogin = {
                                    task {
                                        withContext(Dispatchers.IO) {
                                            NativeCore.request(BuildConfig.AUTH_STATUS)
                                        }
                                        "登录状态有效"
                                    }
                                },
                                logout = {
                                    confirmed =
                                        "清除设备上的登录状态？" to
                                            {
                                                task {
                                                    withContext(Dispatchers.IO) {
                                                        NativeCore.request(BuildConfig.LOGOUT)
                                                    }
                                                    BrowserHost.clear()
                                                    authenticated = false
                                                    "登录状态已清除"
                                                }
                                            }
                                },
                                widget = { large ->
                                    message =
                                        if (TodayWidget.pin(context, large))
                                            "已请求在桌面添加${if (large) "4×3" else "3×3"}小组件，请在桌面确认"
                                        else "请长按桌面，在小组件列表中选择「知序 · 今日课表」"
                                },
                            )
                        } else if (table == null) {
                            Column(
                                Modifier.fillMaxWidth().padding(top = 80.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("把这一学期，安排好", style = MaterialTheme.typography.headlineSmall)
                                Text("无需登录，也能创建自己的课表", Modifier.padding(16.dp))
                                Button(onClick = { tableEditor = Timetable() }) { Text("创建第一张课表") }
                                OutlinedButton(enabled = !busy, onClick = { syncSchool() }) {
                                    Text("同步学校课表")
                                }
                            }
                        } else {
                            val t = table
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    t.name,
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                TextButton(
                                    onClick = { courseEditor = Course(timetableId = t.id) }
                                ) {
                                    Text("＋ 课程")
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                IconButton(
                                    onClick = { week = if (week <= 1) t.weekCount else week - 1 }
                                ) {
                                    WeekArrow(false)
                                }
                                TextButton(
                                    onClick = {
                                        week =
                                            if (week == 0) weekOn(t).coerceIn(1, t.weekCount) else 0
                                    }
                                ) {
                                    Text(
                                        if (week == 0) "全部教学周"
                                        else "第${week}周 · ${if(week%2==1)"单周"else"双周"}"
                                    )
                                }
                                IconButton(
                                    onClick = { week = if (week >= t.weekCount) 1 else week + 1 }
                                ) {
                                    WeekArrow(true)
                                }
                            }
                            AnimatedContent(
                                targetState = week,
                                modifier = Modifier.weight(1f),
                                transitionSpec = {
                                    val direction = if (targetState > initialState) 1 else -1
                                    (slideInHorizontally(tween(140)) { it / 6 * direction } +
                                        fadeIn(tween(120))) togetherWith
                                        (slideOutHorizontally(tween(140)) { -it / 6 * direction } +
                                            fadeOut(tween(90)))
                                },
                                label = "weekTransition",
                            ) { shownWeek ->
                                ScheduleGrid(
                                    t,
                                    courses,
                                    shownWeek,
                                    Modifier.fillMaxSize(),
                                    currentDate,
                                    display,
                                    onWeekSwipe = { delta ->
                                        week = (week + delta).coerceIn(1, t.weekCount)
                                    },
                                ) {
                                    if (android.os.Build.VERSION.SDK_INT < 31)
                                        backdrop = runCatching { blurredBackdrop(view) }.getOrNull()
                                    detail = it
                                }
                            }
                            Text(
                                "今天 $currentDate" +
                                    (weekOn(t, currentDate).let {
                                        if (it in 1..t.weekCount) " · 第${it}周" else " · 非教学周"
                                    }),
                                Modifier.padding(vertical = 12.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            backdrop?.let {
                Image(
                    it,
                    null,
                    Modifier.matchParentSize().alpha((blur.value / 8f).coerceIn(0f, 1f)),
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
    }
    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("知序") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } },
        )
    }
    confirmed?.let { pair ->
        AlertDialog(
            onDismissRequest = { confirmed = null },
            title = { Text("确认操作") },
            text = { Text(pair.first) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmed = null
                        pair.second()
                    }
                ) {
                    Text("确认")
                }
            },
            dismissButton = { TextButton(onClick = { confirmed = null }) { Text("取消") } },
        )
    }
    tableEditor?.let { t ->
        TableEditor(
            t,
            onClose = { tableEditor = null },
            onSave = { new ->
                task {
                    require(
                        dao.allCourses(new.id)
                            .filter { !it.deleted }
                            .all { c -> c.weekList().all { it <= new.weekCount } }
                    ) {
                        "已有课程超出新的教学周数，请先调整课程"
                    }
                    dao.save(new)
                    activeId = new.id
                    tableEditor = null
                    "课表已保存"
                }
            },
        )
    }
    courseEditor?.let { c ->
        CourseEditor(
            c,
            onClose = { courseEditor = null },
            onSave = { new ->
                task {
                    require(
                        new.weekList().all {
                            it <= (tables.find { t -> t.id == new.timetableId }?.weekCount ?: 64)
                        }
                    ) {
                        "课程周次超出本课表的教学周数"
                    }
                    dao.save(new.copy(edited = new.sourceId != null))
                    courseEditor = null
                    "课程已保存"
                }
            },
        )
    }
    detail?.let { c ->
        CourseDetail(
            c,
            table!!,
            onClose = { detail = null },
            onEdit = {
                detail = null
                courseEditor = c
            },
            onDelete = {
                detail = null
                confirmed =
                    "删除「${c.name}」？同步课程的删除会在后续同步时保留。" to
                        {
                            task {
                                if (c.sourceId == null) dao.removeCourse(c.id)
                                else dao.save(c.copy(deleted = true))
                                "课程已删除"
                            }
                        }
            },
        )
    }
    if (login)
        LoginDialog(
            onClose = {
                login = false
                pendingSync = false
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { NativeCore.request(BuildConfig.INITIALIZE) } }
                }
            },
            onSuccess = {
                login = false
                authenticated = true
                if (pendingSync) syncSchool() else message = "登录状态已加密保存"
            },
        )
}

@Composable
fun ScheduleGrid(
    t: Timetable,
    courses: List<Course>,
    week: Int,
    modifier: Modifier,
    currentDate: LocalDate = today(),
    display: CardDisplay = CardDisplay(),
    onWeekSwipe: (Int) -> Unit = {},
    onClick: (Course) -> Unit,
) {
    var selectedDay by remember { mutableStateOf<Int?>(null) }
    var selectedPeriod by remember { mutableStateOf<Int?>(null) }
    val visible = courses.filter { week == 0 || week in it.weekList() }
    val dayToday =
        if (week != 0 && week == weekOn(t, currentDate)) currentDate.dayOfWeek.value else null
    val lessonTimes = timesFor(t)
    val maxPeriod = maxOf(10, visible.maxOfOrNull { it.periodList().max() } ?: 10)
    BoxWithConstraints(
        modifier.pointerInput(onWeekSwipe) {
            var drag = 0f
            detectHorizontalDragGestures(
                onDragStart = { drag = 0f },
                onHorizontalDrag = { change, amount ->
                    drag += amount
                    change.consume()
                },
                onDragCancel = { drag = 0f },
                onDragEnd = {
                    if (kotlin.math.abs(drag) > 48.dp.toPx()) onWeekSwipe(if (drag < 0) 1 else -1)
                },
            )
        }
    ) {
        val headerHeight = 32.dp
        val rowHeight = ((maxHeight - headerHeight) / maxPeriod).coerceIn(36.dp, 72.dp)
        val labelWidth = 30.dp
        val dayWidth = (maxWidth - labelWidth) / 7
        Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(Modifier.width(labelWidth)) {
                Spacer(Modifier.height(headerHeight))
                for (p in 1..maxPeriod) {
                    Column(
                        Modifier.height(rowHeight)
                            .fillMaxWidth()
                            .background(if (selectedPeriod == p) green else Color.Transparent)
                            .clickable { selectedPeriod = if (selectedPeriod == p) null else p },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "$p",
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        lessonTimes.getOrNull(p - 1)?.let {
                            Text(it.first, fontSize = 7.sp, lineHeight = 9.sp)
                            Text(it.second, fontSize = 7.sp, lineHeight = 9.sp)
                        }
                    }
                }
            }
            for (day in 1..7) {
                val highlight = selectedDay == day || dayToday == day
                Column(
                    Modifier.width(dayWidth).semantics {
                        contentDescription = "星期$day"
                        selected = dayToday == day
                    }
                ) {
                    Column(
                        Modifier.height(headerHeight)
                            .fillMaxWidth()
                            .background(if (highlight) green else Color.Transparent)
                            .clickable { selectedDay = if (selectedDay == day) null else day },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "周${"一二三四五六日"[day-1]}",
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        if (week > 0)
                            Text(
                                firstWeekMonday(t)
                                    .plusDays(((week - 1) * 7 + day - 1).toLong())
                                    .toString()
                                    .substring(5),
                                fontSize = 8.sp,
                                lineHeight = 10.sp,
                            )
                    }
                    Box(Modifier.height(rowHeight * maxPeriod).fillMaxWidth()) {
                        Column {
                            for (p in 1..maxPeriod) Box(
                                Modifier.height(rowHeight)
                                    .fillMaxWidth()
                                    .background(
                                        if (highlight || selectedPeriod == p) Color(0xfff0f7e9)
                                        else Color.Transparent
                                    )
                                    .border(.5.dp, Color(0xffe7e8de))
                            )
                        }
                        val items =
                            visible
                                .filter { it.weekday == day }
                                .sortedBy { it.periodList().first() }
                        val lanes = mutableListOf<MutableList<Course>>()
                        items.forEach { c ->
                            val lane =
                                lanes.firstOrNull { l ->
                                    l.none { other ->
                                        c.periodList()
                                            .intersect(other.periodList().toSet())
                                            .isNotEmpty()
                                    }
                                } ?: mutableListOf<Course>().also { lanes.add(it) }
                            lane.add(c)
                        }
                        lanes.forEachIndexed { index, lane ->
                            lane.forEach { c ->
                                val groups = mutableListOf<MutableList<Int>>()
                                c.periodList().forEach {
                                    if (groups.isEmpty() || groups.last().last() + 1 != it)
                                        groups.add(mutableListOf(it))
                                    else groups.last().add(it)
                                }
                                groups.forEach { group ->
                                    Card(
                                        Modifier.offset(
                                                x = dayWidth * index / lanes.size,
                                                y = rowHeight * (group.first() - 1),
                                            )
                                            .width(dayWidth / lanes.size)
                                            .height(rowHeight * group.size)
                                            .padding(1.dp)
                                            .clickable { onClick(c) },
                                        shape = RoundedCornerShape(5.dp),
                                        colors =
                                            CardDefaults.cardColors(
                                                containerColor =
                                                    courseColors[
                                                        Math.floorMod(
                                                            c.name.hashCode(),
                                                            courseColors.size,
                                                        )]
                                            ),
                                    ) {
                                        Column(
                                            Modifier.padding(horizontal = 2.dp, vertical = 3.dp)
                                                .verticalScroll(rememberScrollState()),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(
                                                c.name,
                                                fontSize = 10.sp,
                                                lineHeight = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            if (display.room && c.room.isNotBlank())
                                                Text(
                                                    roomLabel(c.room),
                                                    fontSize = 8.sp,
                                                    lineHeight = 9.sp,
                                                )
                                            if (display.teacher && c.teacher.isNotBlank())
                                                Text(c.teacher, fontSize = 8.sp, lineHeight = 9.sp)
                                            if (display.periods)
                                                Text(
                                                    "${c.periods}节",
                                                    fontSize = 8.sp,
                                                    lineHeight = 9.sp,
                                                )
                                            if (display.note && c.note.isNotBlank())
                                                Text(c.note, fontSize = 8.sp, lineHeight = 9.sp)
                                            if (display.weeks)
                                                Text(
                                                    weekLabel(c.weekList()),
                                                    fontSize = 7.sp,
                                                    lineHeight = 8.sp,
                                                )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseDetail(
    c: Course,
    t: Timetable,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scale by
        animateFloatAsState(
            if (shown) 1f else .88f,
            tween(170, easing = FastOutSlowInEasing),
            label = "detailScale",
        )
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(130), label = "detailAlpha")
    Dialog(onDismissRequest = onClose) {
        Surface(
            Modifier.fillMaxWidth().scale(scale).alpha(alpha),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(c.name, style = MaterialTheme.typography.headlineSmall)
                if (c.room.isNotBlank()) Text(roomLabel(c.room))
                if (c.teacher.isNotBlank()) Text(c.teacher)
                Text("周${"一二三四五六日"[c.weekday-1]} · ${c.periods}节")
                Text("第${c.weeks}周")
                if (c.note.isNotBlank()) Text(c.note)
                val dates = runCatching { occurrences(t, c) }.getOrDefault(emptyList())
                Text(
                    dates.joinToString("\n") {
                        "${it.date}  ${it.start.toLocalTime()}–${it.end.toLocalTime()}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Row {
                    TextButton(onClick = onEdit) { Text("编辑") }
                    TextButton(onClick = onDelete) { Text("删除") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun TableEditor(t: Timetable, onClose: () -> Unit, onSave: (Timetable) -> Unit) {
    var name by remember { mutableStateOf(t.name) }
    var year by remember { mutableStateOf(t.year.toString()) }
    var semester by remember { mutableStateOf(t.semester.toString()) }
    var start by remember { mutableStateOf(t.start) }
    var count by remember { mutableStateOf(t.weekCount.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("课表设置") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Field(name, { name = it }, "名称")
                Field(year, { year = it }, "学年起始年份")
                Field(semester, { semester = it }, "学期：1 秋冬 / 2 春夏")
                Field(start, { start = it }, "开学日期 · YYYY-MM-DD")
                Text("开学日期所在周为第 1 周，周一至周日固定排列。", style = MaterialTheme.typography.bodySmall)
                Field(count, { count = it }, "教学周数")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    try {
                        val y = year.toInt()
                        val s = semester.toInt()
                        val n = count.toInt()
                        require(name.isNotBlank() && y in 2000..2100 && s in 1..2 && n in 1..64) {
                            "请填写有效名称、学年、学期和周数"
                        }
                        LocalDate.parse(start)

                        onSave(
                            t.copy(
                                name = name.trim(),
                                year = y,
                                semester = s,
                                start = start,
                                weekCount = n,
                            )
                        )
                    } catch (_: Exception) {
                        error = "请检查输入：日期格式 YYYY-MM-DD，周数 1–64，学期 1 或 2"
                    }
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

@Composable
private fun CourseEditor(c: Course, onClose: () -> Unit, onSave: (Course) -> Unit) {
    var name by remember { mutableStateOf(c.name) }
    var teacher by remember { mutableStateOf(c.teacher) }
    var room by remember { mutableStateOf(c.room) }
    var day by remember { mutableStateOf(c.weekday.toString()) }
    var periods by remember { mutableStateOf(c.periods) }
    var weeks by remember { mutableStateOf(c.weeks) }
    var note by remember { mutableStateOf(c.note) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("编辑课程") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Field(name, { name = it }, "课程名")
                Field(teacher, { teacher = it }, "教师")
                Field(room, { room = it }, "教室，例如 B233")
                Field(day, { day = it }, "星期 1–7")
                Field(periods, { periods = it }, "节次，例如 5-6")
                Field(weeks, { weeks = it }, "周次，例如 1-4,6,8")
                Field(note, { note = it }, "备注")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    try {
                        require(name.isNotBlank()) { "课程名不能为空" }
                        val d = day.toInt()
                        require(d in 1..7) { "星期应为 1–7" }
                        numbers(periods, 30)
                        numbers(weeks, 64)
                        onSave(
                            c.copy(
                                name = name.trim(),
                                teacher = teacher,
                                room = room,
                                weekday = d,
                                periods = periods,
                                weeks = weeks,
                                note = note,
                            )
                        )
                    } catch (e: Exception) {
                        error = e.message ?: "请检查课程信息"
                    }
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

@Composable
private fun Field(value: String, change: (String) -> Unit, label: String) {
    OutlinedTextField(
        value,
        onValueChange = change,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
private fun LoginDialog(onClose: () -> Unit, onSuccess: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val options = remember { JSONObject() }
    var browser by remember { mutableStateOf<WebView?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        (context as? ComponentActivity)?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            BrowserHost.clear(browser)
            (context as? ComponentActivity)
                ?.window
                ?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    Dialog(
        onDismissRequest = { if (!busy) onClose() },
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = true,
                securePolicy = SecureFlagPolicy.SecureOn,
            ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("学校登录", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(enabled = !busy, onClick = onClose) { Text("关闭") }
                }
                AndroidView(
                    factory = { BrowserHost.create(it, options).also { v -> browser = v } },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(
                    enabled = !busy,
                    onClick = {
                        browser?.let { NativeCore.browser(it, null, options.toString()) }
                        busy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { NativeCore.request(BuildConfig.LOGIN) }
                                onSuccess()
                            } catch (_: Exception) {
                                error = "尚未验证到有效登录，请完成学校页面中的登录；网络异常时稍后再试"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("已完成登录，验证并保存")
                }
            }
        }
    }
}

@Composable
private fun WeekArrow(right: Boolean) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(20.dp)) {
        val path =
            androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * (if (right) .3f else .7f), size.height * .2f)
                lineTo(size.width * (if (right) .7f else .3f), size.height * .5f)
                lineTo(size.width * (if (right) .3f else .7f), size.height * .8f)
            }
        drawPath(
            path,
            color,
            style =
                androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 2.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
        )
    }
}
