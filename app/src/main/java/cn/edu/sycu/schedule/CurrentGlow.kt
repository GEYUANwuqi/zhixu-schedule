package cn.edu.sycu.schedule

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Soft inner halo remains visible inside clipped cards and narrow timetable cells. */
internal fun Modifier.currentGlow(active: Boolean, color: Color, shape: Shape): Modifier =
    if (!active) this else drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        onDrawWithContent {
            drawContent()
            drawOutline(outline, color.copy(alpha = .08f), style = Stroke(8.dp.toPx()))
            drawOutline(outline, color.copy(alpha = .18f), style = Stroke(5.dp.toPx()))
            drawOutline(outline, color.copy(alpha = .8f), style = Stroke(2.dp.toPx()))
        }
    }
