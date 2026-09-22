package cn.edu.sycu.schedule

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.YearMonth
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

@Composable
internal fun CourseDetailStack(blocks: List<LessonBlock>, table: Timetable, onClose: () -> Unit, onEdit: (Course) -> Unit, onDelete: (Course) -> Unit,
    isResting: (LessonBlock) -> Boolean = { false }, onToggleRest: ((LessonBlock) -> Unit)? = null) {
    var index by remember(blocks) { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val movement = remember { Animatable(0f) }
    val opacity = remember { Animatable(1f) }
    var drag by remember { mutableFloatStateOf(0f) }
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(150)) }
    val scope = rememberCoroutineScope()
    val block = blocks[index]
    val c = block.course
    val times = timesFor(table)
    fun advance(direction: Int) {
        if (busy || blocks.size < 2) return
        busy = true
        scope.launch {
            movement.snapTo(drag)
            drag = 0f
            coroutineScope {
                launch { movement.animateTo(direction.toFloat(), tween(190, easing = FastOutLinearInEasing)) }
                launch { opacity.animateTo(0f, tween(190)) }
            }
            index = Math.floorMod(index + direction, blocks.size)
            movement.snapTo(-direction * 0.25f)
            coroutineScope {
                launch { movement.animateTo(0f, tween(240, easing = FastOutSlowInEasing)) }
                launch { opacity.animateTo(1f, tween(220)) }
            }
            busy = false
        }
    }
    val threshold = with(LocalDensity.current) { 35.dp.toPx() }
    val travel = with(LocalDensity.current) { 100.dp.toPx() }
    val cycle by rememberUpdatedState<(Int) -> Unit> { advance(it) }
    val overflowSwipe = remember(blocks) {
        object : NestedScrollConnection {
            var distance = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && blocks.size > 1) {
                    if (consumed.y != 0f) distance = 0f
                    distance += available.y
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (kotlin.math.abs(distance) > threshold) cycle(if (distance > 0) 1 else -1)
                distance = 0f
                return Velocity.Zero
            }
        }
    }
    Dialog(onDismissRequest = onClose) {
        Box(Modifier.fillMaxWidth().animateContentSize(tween(220)).graphicsLayer { alpha = entrance.value; scaleX = .9f + .1f * entrance.value; scaleY = scaleX }) {
            if (blocks.size > 1) {
                val nextBlock = blocks[(index + 1) % blocks.size]
                val next = nextBlock.course
                Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp).graphicsLayer {
                    translationY = (1f - opacity.value) * 12.dp.toPx()
                    scaleX = 1f + (1f - opacity.value) * .03f
                }.makeupCorner(next.isMakeup, isResting(nextBlock)), shape = lessonShape(next.isMakeup || isResting(nextBlock)), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(next.name, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), maxLines = 1, style = MaterialTheme.typography.titleSmall)
                }
            }
            Surface(Modifier.padding(top = if (blocks.size > 1) 34.dp else 0.dp).fillMaxWidth().graphicsLayer {
                translationY = (movement.value + drag) * travel
                alpha = opacity.value * (1f - kotlin.math.abs(drag) * .3f)
                scaleX = .97f + .03f * opacity.value
                scaleY = scaleX
            }.makeupCorner(c.isMakeup, isResting(block)).nestedScroll(overflowSwipe), shape = lessonShape(c.isMakeup || isResting(block)), tonalElevation = 6.dp) {
                Column(Modifier.heightIn(max = 680.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Keep the swipe handle outside the scrolling body. The old parent
                    // detector lost every drag to verticalScroll before reaching slop.
                    Column(Modifier.fillMaxWidth().testTag("detail-swipe").pointerInput(blocks, index, busy) {
                        var distance = 0f
                        fun settle() {
                            scope.launch {
                                movement.snapTo(drag); drag = 0f
                                movement.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
                            }
                        }
                        detectVerticalDragGestures(onDragStart = { distance = 0f }, onVerticalDrag = { change, amount ->
                            if (!busy && blocks.size > 1) { distance += amount; drag = (distance / travel).coerceIn(-.6f, .6f); change.consume() }
                        }, onDragCancel = { settle() }, onDragEnd = {
                            if (kotlin.math.abs(distance) > threshold) cycle(if (distance > 0) 1 else -1) else settle()
                        })
                    }) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            FilledIconToggleButton(checked = isResting(block), enabled = onToggleRest != null,
                                onCheckedChange = { onToggleRest?.invoke(block) }) { Text("休") }
                        }
                        if (blocks.size > 1) Text("${index + 1}/${blocks.size} · 上下滑动切换课程", style = MaterialTheme.typography.labelSmall)
                        Text("周${"一二三四五六日"[c.weekday - 1]} ${block.periods.joinToString(",")}节  ${times.getOrNull(block.periods.first() - 1)?.first ?: ""}–${times.getOrNull(block.periods.last() - 1)?.second ?: ""}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (c.room.isNotBlank()) Text(roomLabel(c.room))
                    if (c.teacher.isNotBlank()) Text(c.teacher)
                    if (c.note.isNotBlank()) Text(c.note)
                    key(c.id) { CourseDates(table, c) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onDelete(c) }) { Text("删除") }
                        TextButton(onClick = { onEdit(c) }) { Text("编辑") }
                        TextButton(onClick = onClose) { Text("关闭") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseDates(table: Timetable, course: Course) {
    val dates = remember(table, course) { occurrences(table, course).map { it.date }.toSet() }
    val first = YearMonth.from(java.time.LocalDate.parse(table.start))
    val last = YearMonth.from(firstWeekMonday(table).plusDays(table.weekCount * 7L - 1))
    var month by remember { mutableStateOf(YearMonth.from(dates.filter { it >= today() }.minOrNull() ?: dates.minOrNull() ?: today()).coerceIn(first, last)) }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = month > first, onClick = { month = month.minusMonths(1) }) { Text("‹") }
                Text("${month.year}年${month.monthValue}月")
                TextButton(enabled = month < last, onClick = { month = month.plusMonths(1) }) { Text("›") }
            }
            Row { Text("周", Modifier.width(30.dp)); "一二三四五六日".forEach { Text(it.toString(), Modifier.weight(1f)) } }
            val start = month.atDay(1).minusDays((month.atDay(1).dayOfWeek.value - 1).toLong())
            val rows = (month.atDay(1).dayOfWeek.value - 1 + month.lengthOfMonth() + 6) / 7
            repeat(rows) { row ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val week = weekOn(table, start.plusDays(row * 7L))
                    Text(if (week in 1..table.weekCount) "$week" else "", Modifier.width(30.dp), style = MaterialTheme.typography.labelSmall)
                    repeat(7) { col ->
                        val date = start.plusDays(row * 7L + col)
                        Box(Modifier.weight(1f).height(32.dp).background(if (date in dates && YearMonth.from(date) == month) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                            if (YearMonth.from(date) == month) Text("${date.dayOfMonth}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text("高亮日期有本课程 · 左侧为教学周", style = MaterialTheme.typography.labelSmall)
        }
    }
}
