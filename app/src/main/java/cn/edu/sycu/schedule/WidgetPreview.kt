package cn.edu.sycu.schedule

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .size(width = if (large) 250.dp else 180.dp, height = 180.dp)
            .clip(shape)
            .background(Color(0xfff8f7ef))
            .border(1.dp, Color(0x33293b2b), shape)
    ) {
        background?.let {
            Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            Box(
                Modifier.matchParentSize()
                    .background(Color.White.copy(alpha = Backgrounds.VEIL))
            )
        }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                if (large) "今日课表 · ${today()}" else "今日课表",
                color = Color(0xff293b2b),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            if (!large)
                Text(
                    today().toString(),
                    color = Color(0xff667161),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            Text(
                table?.name ?: "知序",
                color = Color(0xff667161),
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (lessons.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (table == null) "尚无课表，点击添加或同步" else "今天没有课程",
                        color = Color(0xff667161),
                        fontSize = 12.sp,
                    )
                }
            } else {
                lessons.forEachIndexed { index, item ->
                    if (index > 0) Spacer(Modifier.height(6.dp))
                    Column(
                        Modifier.fillMaxWidth()
                            .background(Color(0xffe2efdb))
                            .padding(8.dp)
                    ) {
                        Text(
                            "${item.start.toLocalTime()}–${item.end.toLocalTime()}",
                            color = Color(0xff52754f),
                            fontSize = 12.sp,
                        )
                        Text(
                            item.course.name,
                            color = Color(0xff293b2b),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            courseLocation(item.course),
                            color = Color(0xff667161),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}
