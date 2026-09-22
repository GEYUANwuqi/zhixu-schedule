package cn.edu.sycu.schedule

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Live preview of the home-screen widget: same background, metrics and row styling, filled with the
 * current timetable instead of sample data.
 */
@Composable
fun WidgetPreview(
    large: Boolean,
    table: Timetable?,
    lessons: List<Occurrence>,
    background: ImageBitmap? = null,
    modifier: Modifier = Modifier,
) {
    val appearance = LocalAppearance.current
    var now by remember { mutableStateOf(java.time.ZonedDateTime.now(schoolZone)) }
    LaunchedEffect(Unit) { while (isActive) { now = java.time.ZonedDateTime.now(schoolZone); delay(1000) } }
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .size(width = if (large) 250.dp else 180.dp, height = 180.dp)
            .clip(shape)

    ) {
        Box(Modifier.matchParentSize().alpha(appearance.opacity / 100f).background(Color(appearance.background))) {
        background?.let {
            Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            Box(
                Modifier.matchParentSize()
                    .background(Color(appearance.background).copy(alpha = Backgrounds.VEIL))
            )
        }
        }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                if (large) "今日课表 · ${today()}" else "今日课表",
                color = Color(appearance.ink),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            if (!large)
                Text(
                    today().toString(),
                    color = Color(appearance.ink),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            Text(
                table?.name ?: "知序",
                color = Color(appearance.ink),
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (lessons.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (table == null) "尚无课表，点击添加或同步" else "今天是假日哦~好好休息一下吧~",
                        color = Color(appearance.ink),
                        fontSize = 12.sp,
                    )
                }
            } else {
                lessons.forEachIndexed { index, item ->
                    val phase = lessonPhase(item.start, item.end, now)
                    val cardColor = phaseColor(appearance.card(item.course.name), phase)
                    if (index > 0) Spacer(Modifier.height(6.dp))
                    Column(
                        Modifier.fillMaxWidth()
                            .alpha(phaseAlpha(phase, appearance.pastCourseOpacity))
                            .clip(lessonShape(item.course.isMakeup))
                            .background(Color(cardColor).copy(alpha = appearance.opacity / 100f))
                            .makeupCorner(item.course.isMakeup)
                            .padding(8.dp)
                    ) {
                        Text(
                            "${item.start.toLocalTime()}–${item.end.toLocalTime()}",
                            color = Color(readableColor(cardColor)),
                            fontSize = 12.sp,
                        )
                        Text(
                            item.course.name,
                            color = Color(readableColor(cardColor)),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            courseLocation(item.course),
                            color = Color(readableColor(cardColor)),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}
