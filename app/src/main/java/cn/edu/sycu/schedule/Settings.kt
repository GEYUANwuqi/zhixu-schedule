package cn.edu.sycu.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.roundToInt

class SettingsNavigation {
    val section = mutableStateOf<String?>(null)
    val scrolls = mutableMapOf<String, androidx.compose.foundation.ScrollState>()
    fun parent(page: String?) = when (page) { "主题色", "课程卡片颜色" -> "个性化"; "同步规则" -> "学校同步"; else -> null }
    fun back() { section.value = parent(section.value) }
}

@Composable
fun ScheduleSettings(
    tables: List<Timetable>,
    table: Timetable?,
    courses: List<Course>,
    busy: Boolean,
    authenticated: Boolean,
    display: CardDisplay,
    onDisplay: (CardDisplay) -> Unit,
    themeColor: Int,
    onThemeColor: (Int) -> Unit,
    widgetLarge: Boolean,
    onWidgetLarge: (Boolean) -> Unit,
    lessons: List<Occurrence>,
    appBackground: ImageBitmap?,
    widgetBackground: ImageBitmap?,
    screenAspect: Float,
    pickBackground: (BackgroundKind) -> Unit,
    clearBackground: (BackgroundKind) -> Unit,
    select: (String) -> Unit,
    edit: (Timetable) -> Unit,
    addCourse: () -> Unit,
    sync: () -> Unit,
    calendar: () -> Unit,
    png: (String) -> Unit,
    exportJson: () -> Unit,
    importJson: () -> Unit,
    clear: () -> Unit,
    delete: () -> Unit,
    login: () -> Unit,
    checkLogin: () -> Unit,
    logout: () -> Unit,
    widget: (Boolean) -> Unit,
    courseNames: List<String> = emptyList(),
    navigation: SettingsNavigation,
    onAppearance: () -> Unit = {},
) {
    val context = LocalContext.current
    val preferences = remember { SchedulePreferences(context) }
    var opacity by remember { mutableIntStateOf(preferences.widgetOpacity) }
    var pastOpacity by remember { mutableIntStateOf(preferences.pastCourseOpacity) }
    var section by navigation.section
    fun parent(page: String?) = when (page) { "主题色", "课程卡片颜色" -> "个性化"; "同步规则" -> "学校同步"; else -> null }
    BackHandler(section != null) { section = parent(section) }
    AnimatedContent(
        section,
        transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(90)) },
        label = "settingsPage",
    ) { page ->
        Column(
            Modifier.fillMaxWidth().verticalScroll(navigation.scrolls.getOrPut(page ?: "root") { androidx.compose.foundation.ScrollState(0) }),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (page != null) TextButton(onClick = { section = parent(page) }) { Text("‹ 返回" + (parent(page) ?: "设置")) }
            Text(page ?: "设置", style = MaterialTheme.typography.titleLarge)
            when (page) {
                "同步规则" -> RuleSettings(preferences)
                "课程卡片颜色" -> CourseColorSettings(courseNames, preferences, onAppearance)
                null -> {
                    listOf(
                        "课表与数据" to listOf("课表管理" to "选择课表、管理课程与卡片显示", "学校同步" to "登录、同步与课程修改规则", "导出与日历" to "JSON、课表图片与系统日历"),
                        "外观与桌面" to listOf("个性化" to "主题、课程配色、透明度与背景", "桌面小组件" to "尺寸、预览与添加到桌面"),
                        "应用" to listOf("权限管理" to "通知、准时提醒、后台运行与日历权限", "状态栏与通知" to "今日课程常驻通知与上课前提醒", "关于" to "版本、更新、隐私与许可证"),
                    ).forEach { (group, entries) ->
                        SettingsSection(group)
                        entries.forEach { (title, description) ->
                        ElevatedCard(
                            onClick = { section = title },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(title, style = MaterialTheme.typography.titleMedium)
                                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("›")
                            }
                        }
                    }
                    }
                }
                "关于" -> AboutPage()
                "权限管理" -> PermissionsPage()
                "状态栏与通知" -> NotificationSettingsPage(table, courses) { section = "权限管理" }
                "课表管理" -> {
                    SettingsSection("我的课表", "选择当前课表，或编辑学期与开学日期。")
                    if (tables.isEmpty()) Text("暂无课表，可以新建或前往学校同步。", style = MaterialTheme.typography.bodyMedium)
                    tables.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(t.id == table?.id, { select(t.id) })
                            Text(t.name, Modifier.weight(1f))
                            TextButton(onClick = { edit(t) }) { Text("编辑") }
                        }
                    }
                    Button(onClick = { edit(Timetable()) }) { Text("新建课表") }
                    table?.let {
                        SettingsSection("当前课表", it.name)
                        Text("开学日期 ${it.start} · ${it.weekCount} 周")
                        OutlinedButton(onClick = addCourse) { Text("手动添加课程") }
                        MakeupManager(it, courses)
                    }
                    SettingsSection("卡片显示", "课程名称始终显示，以下显示偏好应用于所有课表。")
                    DisplaySwitch("地点", display.room) { onDisplay(display.copy(room = it)) }
                    DisplaySwitch("教师", display.teacher) { onDisplay(display.copy(teacher = it)) }
                    DisplaySwitch("第几周", display.weeks) { onDisplay(display.copy(weeks = it)) }
                    DisplaySwitch("节次", display.periods) { onDisplay(display.copy(periods = it)) }
                    DisplaySwitch("备注", display.note) { onDisplay(display.copy(note = it)) }
                    table?.let {
                        SettingsSection("清理课表", "仅操作当前课表，执行前仍需确认。")
                        TextButton(onClick = clear, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("清空课程") }
                        TextButton(onClick = delete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除课表") }
                    }
                }
                "主题色" -> {
                    Text("选择一套配色，背景、菜单、小组件和默认课程颜色一起更换。")
                    ThemeChoices(themeColor, onThemeColor)
                }
                "个性化" -> {
                    SettingsSection("配色", "整套主题与单独课程颜色。")
                    OutlinedButton(onClick = { section = "主题色" }) { Text("主题色") }
                    OutlinedButton(onClick = { section = "课程卡片颜色" }) { Text("课程卡片颜色") }
                    SettingsSection("课程状态", "调整已结束课程的显示强度。")
                    Text("已结束课程不透明度：$pastOpacity%")
                    Text("适用于课表和小组件；0% 完全透明，100% 保持原样。", style = MaterialTheme.typography.bodySmall)
                    Slider(pastOpacity.toFloat(), { pastOpacity = it.toInt(); preferences.pastCourseOpacity = pastOpacity; onAppearance() }, valueRange = 0f..100f)
                    SettingsSection("小组件背景", "调整背景强度，下方预览即时更新。")
                    Text("小组件背景不透明度：$opacity%")
                    Text("100% 完全显示，0% 完全透明；自定义图片与背景一起调整。", style = MaterialTheme.typography.bodySmall)
                    Slider(opacity.toFloat(), { opacity = it.toInt(); preferences.widgetOpacity = opacity; onAppearance() }, valueRange = 0f..100f)
                    WidgetPreview(widgetLarge, table, lessons, widgetBackground)
                    SettingsSection("背景图片")
                    Text("App 背景和桌面小组件背景可以分别设置，图片只保存在本机，不会上传。")
                    BackgroundPicker(
                        title = "App 背景",
                        hint = "整屏铺满，按屏幕比例居中裁剪。",
                        image = appBackground,
                        guide = CropGuide(outer = screenAspect),
                        onPick = { pickBackground(BackgroundKind.App) },
                        onClear = { clearBackground(BackgroundKind.App) },
                    )
                    BackgroundPicker(
                        title = "小组件背景",
                        hint = "实线是 4×3 的裁切范围，虚线是 3×3：高度相同，4×3 左右各多出半格；桌面网格不同时比例略有差异。",
                        image = widgetBackground,
                        guide =
                            CropGuide(
                                outer =
                                    WidgetGeometry.widthDp(4) / WidgetGeometry.heightDp(3),
                                inner =
                                    WidgetGeometry.widthDp(3) / WidgetGeometry.heightDp(3),
                            ),
                        onPick = { pickBackground(BackgroundKind.Widget) },
                        onClear = { clearBackground(BackgroundKind.Widget) },
                    )
                    Text("建议选浅色或低对比度的图片，课程文字更清楚。")
                }
                "学校同步" -> {
                    SettingsSection("同步课程")
                    Text(if (authenticated) "已保存登录状态 · 同步时检查有效性" else "未登录")
                    Button(enabled = !busy, onClick = sync) { Text("同步学校课表") }
                    if (table == null) Text("首次同步默认创建秋冬学期，8 月 24 日开学，共 20 周。")
                    SettingsSection("同步规则", "完全匹配学校返回的课程字段，只修改指定内容。")
                    OutlinedButton(enabled = !busy, onClick = { section = "同步规则" }) { Text("管理同步规则（${preferences.rules.size}）") }
                    SettingsSection("登录状态")
                    Button(enabled = !busy, onClick = login) { Text("打开学校登录页") }
                    TextButton(enabled = !busy, onClick = checkLogin) { Text("检查登录状态") }
                    TextButton(enabled = !busy, onClick = logout) { Text("清除登录状态") }
                }
                "导出与日历" -> {
                    BackupSettings(enabled = !busy)
                    SettingsSection("课表 JSON")
                    Text("JSON 保存当前课表的课程与时间设置；导入会新建课表，保留现有数据。")
                    OutlinedButton(enabled = table != null && !busy, onClick = exportJson) { Text("导出 JSON") }
                    OutlinedButton(enabled = !busy, onClick = importJson) { Text("导入 JSON") }
                    SettingsSection("系统日历")
                    Text("日历导入会完全替换本课表专用日历内的所有日程，其他日历保留。")
                    OutlinedButton(enabled = table != null && !busy, onClick = calendar) {
                        Text("导入系统日历")
                    }
                    SettingsSection("课表文件", "导出单双周课表，不包含补课。Excel 可编辑；PDF 保留图片版布局，可缩放查看或打印。通过系统分享保存。")
                    listOf("PNG", "Excel", "PDF").forEach { format ->
                    OutlinedButton(enabled = table != null && !busy, onClick = { png(format) }) {
                        Text("导出 / 分享 $format")
                    }
                    }
                }
                "桌面小组件" -> {
                    SettingsSection("尺寸与预览")
                    Text("显示当前选中课表的当日课程、时间、地点和教师；点击小组件打开课表。")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !widgetLarge,
                            onClick = { onWidgetLarge(false) },
                            label = { Text("3×3") },
                        )
                        FilterChip(
                            selected = widgetLarge,
                            onClick = { onWidgetLarge(true) },
                            label = { Text("4×3") },
                        )
                    }
                    Text("实时预览 · ${today()}", style = MaterialTheme.typography.labelMedium)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        WidgetPreview(widgetLarge, table, lessons, widgetBackground)
                    }
                    SettingsSection("添加小组件")
                    Button(onClick = { widget(widgetLarge) }) { Text("添加到桌面") }
                    Text("也可长按桌面，在小组件列表中选择「知序 · 今日课表」。")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun SettingsSection(title: String, description: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * Crop window drawn over a background preview. [outer] is the aspect ratio (width ÷ height) of the
 * region a surface keeps; the optional [inner] box shares that height and is narrower — the way the
 * 4×3 widget region contains the 3×3 one.
 */
private data class CropGuide(val outer: Float, val inner: Float? = null)

@Composable
private fun BackgroundPicker(
    title: String,
    hint: String,
    image: ImageBitmap?,
    guide: CropGuide,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Box(
        Modifier.fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (image == null) Text("当前为默认背景")
        else BackgroundCropPreview(image, guide, Modifier.matchParentSize())
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onPick) { Text(if (image == null) "选择背景图" else "更换背景图") }
        if (image != null) TextButton(onClick = onClear) { Text("移除背景图") }
    }
    Text(hint, style = MaterialTheme.typography.labelSmall)
}

/** Shows the whole picked image with the kept region highlighted and the rest dimmed. */
@Composable
private fun BackgroundCropPreview(image: ImageBitmap, guide: CropGuide, modifier: Modifier, selected: CropBox? = null) {
    val selectionColor = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val scale = minOf(size.width / image.width, size.height / image.height)
        val drawn = Size(image.width * scale, image.height * scale)
        val origin = Offset((size.width - drawn.width) / 2f, (size.height - drawn.height) / 2f)
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
            dstSize = IntSize(drawn.width.roundToInt(), drawn.height.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
        val box = selected ?: centerCrop(image.width.toFloat(), image.height.toFloat(), guide.outer)
        val crop =
            Rect(
                origin + Offset(box.left, box.top) * scale,
                Size(box.width * scale, box.height * scale),
            )
        val scrim = Color.Black.copy(alpha = 0.45f)
        drawRect(scrim, Offset.Zero, Size(size.width, crop.top))
        drawRect(scrim, Offset(0f, crop.bottom), Size(size.width, size.height - crop.bottom))
        drawRect(scrim, Offset(0f, crop.top), Size(crop.left, crop.height))
        drawRect(scrim, Offset(crop.right, crop.top), Size(size.width - crop.right, crop.height))
        drawRect(
            color = selectionColor,
            topLeft = crop.topLeft,
            size = crop.size,
            style = Stroke(2.dp.toPx()),
        )
        guide.inner?.let { inner ->
            val width = crop.width * (inner / guide.outer)
            drawRect(
                color = Color.White,
                topLeft = Offset(crop.center.x - width / 2f, crop.top),
                size = Size(width, crop.height),
                style =
                    Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
                    ),
            )
        }
    }
}

@Composable
private fun DisplaySwitch(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { change(!value) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(value, change)
    }
}

@Composable
fun CropPhotoDialog(uri: Uri, kind: BackgroundKind, onResult: (CropBox?) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap = remember(uri) { Backgrounds.preview(context, uri) }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val dialogHeight = with(androidx.compose.ui.platform.LocalDensity.current) { window.height.toDp() }
    val aspect = if (kind == BackgroundKind.App) window.width.coerceAtLeast(1).toFloat() / window.height.coerceAtLeast(1) else WidgetGeometry.widthDp(4) / WidgetGeometry.heightDp(3)
    var zoom by remember { mutableFloatStateOf(1f) }
    var horizontal by remember { mutableFloatStateOf(0.5f) }
    var vertical by remember { mutableFloatStateOf(0.5f) }
    Dialog(onDismissRequest = { onResult(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(20.dp), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.heightIn(max = (dialogHeight - 64.dp).coerceAtLeast(160.dp)).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("调整显示区域", style = MaterialTheme.typography.titleLarge)
                Text("拖动滑块调整缩放和位置，绿色边框始终不会超出图片。")
                if (image != null) {
                    Box(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(12.dp))) {
                        val b = bitmap!!
                        val base = centerCrop(b.width.toFloat(), b.height.toFloat(), aspect)
                        val w = (base.width / zoom).coerceAtLeast(1f)
                        val h = (base.height / zoom).coerceAtLeast(1f)
                        val left = ((b.width - w) * horizontal).coerceIn(0f, b.width - w)
                        val top = ((b.height - h) * vertical).coerceIn(0f, b.height - h)
                        BackgroundCropPreview(image, CropGuide(aspect), Modifier.matchParentSize(), CropBox(left, top, left + w, top + h))
                    }
                } else Text("无法读取所选图片", color = MaterialTheme.colorScheme.error)
                CompactSlider("缩放", zoom, { zoom = it }, 1f..3f)
                CompactSlider("横向", horizontal, { horizontal = it })
                CompactSlider("纵向", vertical, { vertical = it })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onResult(null) }) { Text("取消") }
                    Button(enabled = bitmap != null, onClick = {
                        val b = bitmap!!
                        val base = centerCrop(b.width.toFloat(), b.height.toFloat(), aspect)
                        val w = (base.width / zoom).coerceAtLeast(1f)
                        val h = (base.height / zoom).coerceAtLeast(1f)
                        val left = ((b.width - w) * horizontal).coerceIn(0f, b.width - w)
                        val top = ((b.height - h) * vertical).coerceIn(0f, b.height - h)
                        onResult(CropBox(left, top, left + w, top + h))
                    }) { Text("使用此区域") }
                }
            }
        }
    }
}

@Composable
private fun CompactSlider(label: String, value: Float, change: (Float) -> Unit, range: ClosedFloatingPointRange<Float> = 0f..1f) {
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(48.dp), style = MaterialTheme.typography.labelSmall)
        Slider(value, change, valueRange = range, modifier = Modifier.weight(1f))
    }
}
