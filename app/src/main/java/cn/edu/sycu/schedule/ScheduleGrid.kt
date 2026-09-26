package cn.edu.sycu.schedule

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlin.math.roundToInt

@Composable
fun ScheduleGrid(t: Timetable, courses: List<Course>, week: Int, modifier: Modifier,
    currentDate: LocalDate = today(), display: CardDisplay = CardDisplay(), onWeekSwipe: (Int) -> Unit = {},
    onMove: (Course, List<Int>, Int, Int) -> Unit = { _, _, _, _ -> }, selectableIds: Set<String> = emptySet(),
    selectionMode: Boolean = false, onDaySelect: (Int) -> Unit = {}, selectedDate: LocalDate? = null,
    selectedDates: Set<LocalDate> = emptySet(), holidayDates: Set<LocalDate> = emptySet(),
    holidayKeys: Set<String> = emptySet(),
    onStackClick: ((List<LessonBlock>) -> Unit)? = null, onClick: (Course) -> Unit,
) {
    var selectedDay by remember { mutableStateOf<Int?>(null) }
    var selectedPeriod by remember { mutableStateOf<Int?>(null) }
    var clock by remember { mutableStateOf(java.time.ZonedDateTime.now(schoolZone)) }
    LaunchedEffect(Unit) { while (isActive) { clock = java.time.ZonedDateTime.now(schoolZone); delay(1000) } }
    val visible = courses.filter { !it.deleted && (week == 0 || week in it.weekList()) }
    val stacks = remember(visible) { lessonStacks(visible) }
    val times = timesFor(t)
    val maxPeriod = maxOf(10, visible.maxOfOrNull { it.periodList().max() } ?: 10)
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var settling by remember { mutableStateOf(false) }
    val animatedOffset by animateOffsetAsState(dragOffset, if (settling) tween(110) else snap(), label = "dropSnap")
    LaunchedEffect(courses, week) { dragging = null; dragOffset = Offset.Zero; settling = false }
    BoxWithConstraints(modifier.pointerInput(onWeekSwipe) {
        var dx = 0f
        detectHorizontalDragGestures(onDragStart = { dx = 0f }, onHorizontalDrag = { change, delta -> change.consume(); dx += delta },
            onDragEnd = { if (kotlin.math.abs(dx) > 48.dp.toPx()) onWeekSwipe(if (dx < 0) 1 else -1) })
    }) {
        val rowHeight = ((maxHeight - 32.dp) / maxPeriod).coerceIn(36.dp, 72.dp)
        val dayWidth = (maxWidth - 30.dp) / 7
        Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(Modifier.width(30.dp)) {
                Spacer(Modifier.height(32.dp))
                for (p in 1..maxPeriod) Column(Modifier.height(rowHeight).fillMaxWidth()
                    .background(if (!selectionMode && selectedPeriod == p) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                    .clickable(enabled = !selectionMode) { selectedPeriod = if (selectedPeriod == p) null else p },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("$p", fontSize = 10.sp, lineHeight = 12.sp)
                    times.getOrNull(p - 1)?.let { Text(it.first, fontSize = 7.sp, lineHeight = 9.sp); Text(it.second, fontSize = 7.sp, lineHeight = 9.sp) }
                }
            }
            for (day in 1..7) {
                val date = firstWeekMonday(t).plusDays(((week - 1) * 7 + day - 1).toLong())
                val holiday = week > 0 && date in holidayDates
                val highlight = (week > 0 && date == currentDate) || if (selectionMode) date == selectedDate || date in selectedDates else selectedDay == day
                fun chooseDay() { if (selectionMode) onDaySelect(day) else selectedDay = if (selectedDay == day) null else day }
                Column(Modifier.width(dayWidth).semantics { contentDescription = "星期$day"; selected = highlight }) {
                    Column(Modifier.height(32.dp).fillMaxWidth().background(if (highlight) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).clickable { chooseDay() }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("周${"一二三四五六日"[day - 1]}", fontSize = 10.sp, lineHeight = 12.sp)
                        if (week > 0) Text(date.toString().substring(5), fontSize = 8.sp, lineHeight = 10.sp)
                    }
                    Box(Modifier.height(rowHeight * maxPeriod).fillMaxWidth()) {
                        Column {
                            for (p in 1..maxPeriod) Box(Modifier.height(rowHeight).fillMaxWidth()
                                .background(if (highlight || (!selectionMode && selectedPeriod == p)) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                                .border(.5.dp, MaterialTheme.colorScheme.outlineVariant).clickable(enabled = selectionMode) { chooseDay() })
                        }
                        stacks.filter { it.front.course.weekday == day }.forEach { stack ->
                            val c = stack.front.course
                            val group = stack.front.periods
                            val key = "${c.id}:${group.first()}"
                            val active = dragging == key
                            val frontHoliday = holiday || (week > 0 && holidayKey(t, c, date, group) in holidayKeys)
                            val hasHoliday = holiday || (week > 0 && stack.blocks.any { holidayKey(t, it.course, date, it.periods) in holidayKeys })
                            val phase = if (frontHoliday) LessonPhase.PAST else coursePhase(t, week, day, group, clock)
                            val fade = phaseAlpha(phase, LocalAppearance.current.pastCourseOpacity)
                            val color = phaseColor(LocalAppearance.current.card(c.name), phase)
                            val shape = lessonShape(stack.hasMakeup || hasHoliday)
                            fun click() { onStackClick?.invoke(stack.blocks) ?: onClick(c) }
                            Box(Modifier.offset(y = rowHeight * (stack.start - 1)).width(dayWidth).height(rowHeight * (stack.end - stack.start + 1)).padding(1.dp)
                                .zIndex(if (active) 3f else 1f).graphicsLayer {
                                    alpha = fade
                                    translationX = if (active) animatedOffset.x else 0f; translationY = if (active) animatedOffset.y else 0f
                                }.then(if (!selectionMode && !c.isMakeup && stack.blocks.size == 1 && week > 0) Modifier.pointerInput(c, group, week) {
                                    detectDragGesturesAfterLongPress(onDragStart = { dragging = key; settling = false; dragOffset = Offset.Zero },
                                        onDragCancel = { dragging = null; dragOffset = Offset.Zero },
                                        onDrag = { change, delta -> change.consume(); dragOffset += delta },
                                        onDragEnd = {
                                            val d = day + (dragOffset.x / dayWidth.toPx()).roundToInt()
                                            val p = group.first() + (dragOffset.y / rowHeight.toPx()).roundToInt()
                                            val valid = d in 1..7 && c.periodList().map { it + p - group.first() }.all { it in 1..maxPeriod && times.getOrNull(it - 1)?.first?.isNotBlank() == true }
                                            settling = true
                                            dragOffset = if (valid) Offset((d - day) * dayWidth.toPx(), (p - group.first()) * rowHeight.toPx()) else Offset.Zero
                                            scope.launch { delay(110); if (valid) onMove(c, group, d, p); dragging = null; dragOffset = Offset.Zero; settling = false }
                                        })
                                } else Modifier).clickable { click() }) {
                                Card(Modifier.fillMaxSize().makeupCorner(stack.hasMakeup, hasHoliday)
                                    .currentGlow(phase == LessonPhase.CURRENT, MaterialTheme.colorScheme.primary, shape)
                                    .then(if (stack.blocks.any { it.course.id in selectableIds }) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                                    .semantics { selected = stack.blocks.any { it.course.id in selectableIds } }, shape = shape,
                                    colors = CardDefaults.cardColors(containerColor = Color(color), contentColor = Color(readableColor(color)))) {
                                    Column(Modifier.padding(top = if (stack.hasMakeup || hasHoliday) 8.dp else 0.dp).padding(horizontal = 2.dp, vertical = 3.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(c.name, fontSize = 10.sp, lineHeight = 11.sp, fontWeight = FontWeight.SemiBold)
                                        if (display.room && c.room.isNotBlank()) Text(roomLabel(c.room), fontSize = 8.sp, lineHeight = 9.sp)
                                        if (display.teacher && c.teacher.isNotBlank()) Text(c.teacher, fontSize = 8.sp, lineHeight = 9.sp)
                                        if (display.periods) Text("${group.joinToString(",")}节", fontSize = 8.sp, lineHeight = 9.sp)
                                        if (display.note && c.note.isNotBlank()) Text(c.note, fontSize = 8.sp, lineHeight = 9.sp)
                                        if (display.weeks) Text(weekLabel(c.weekList()), fontSize = 7.sp, lineHeight = 8.sp)
                                    }
                                }
                                // Decoration only: an expanded badge touch target used to overlap
                                // the weekday header and intercept whole-day selection.
                                if (stack.blocks.size > 1) Surface(Modifier.align(Alignment.TopEnd).padding(2.dp).size(minOf(10.dp, dayWidth / 3)), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                                    Box(contentAlignment = Alignment.Center) { Text("${stack.blocks.size}", fontSize = if (stack.blocks.size < 10) 7.sp else 5.sp, lineHeight = 8.sp, style = MaterialTheme.typography.labelSmall.copy(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false))) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
