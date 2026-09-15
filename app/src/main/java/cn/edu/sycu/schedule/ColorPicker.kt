package cn.edu.sycu.schedule

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun ThemeChoices(selected: Int, onColor: (Int) -> Unit) {
    themePalettes.forEach { palette ->
        val active = themePalette(selected) == palette
        Surface(onClick = { onColor(palette.primary) }, shape = RoundedCornerShape(16.dp),
            color = Color(palette.background), contentColor = Color(palette.primary),
            border = androidx.compose.foundation.BorderStroke(if (active) 2.dp else 1.dp, Color(if (active) palette.primary else palette.soft))) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(palette.name + if (active) " · 已选择" else "", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf(palette.primary, palette.selected) + palette.courseColors.take(3)).forEach { color ->
                        Box(Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(8.dp)).background(Color(color)))
                    }
                }
            }
        }
    }
}

@Composable
fun ColorChoices(selected: Int, onColor: (Int) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0xff52754f, 0xff3f6f8f, 0xff8a5a78, 0xffa66a2c, 0xff5d647a).forEach { value ->
            Box(Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(12.dp)).background(Color(value))
                .border(if (selected == value.toInt()) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(12.dp))
                .clickable { onColor(value.toInt()) })
        }
    }
    OutlinedButton(onClick = { custom = true }) { Text("自定义色盘") }
    if (custom) ColorPicker(selected, { custom = false }) { onColor(it); custom = false }
}

@Composable
private fun ColorPicker(initial: Int, close: () -> Unit, select: (Int) -> Unit) {
    val hsv = remember { FloatArray(3).also { AndroidColor.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var brightness by remember { mutableFloatStateOf(hsv[2]) }
    val value = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness))
    var hex by remember { mutableStateOf(String.format("%06X", initial and 0xffffff)) }
    var error by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = close) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("自定义颜色", style = MaterialTheme.typography.titleLarge)
                Box(Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(12.dp)).background(Color(value)))
                Text("色相")
                Box(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))) {
                    Slider(hue, { hue = it; error = false; hex = String.format("%06X", AndroidColor.HSVToColor(floatArrayOf(it, saturation, brightness)) and 0xffffff) }, valueRange = 0f..360f)
                }
                Text("饱和度")
                Slider(saturation, { saturation = it; error = false; hex = String.format("%06X", AndroidColor.HSVToColor(floatArrayOf(hue, it, brightness)) and 0xffffff) })
                Text("亮度")
                Slider(brightness, { brightness = it; error = false; hex = String.format("%06X", AndroidColor.HSVToColor(floatArrayOf(hue, saturation, it)) and 0xffffff) })
                OutlinedTextField(hex, { text ->
                    hex = text.removePrefix("#")
                    val parsed = hex.takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toLongOrNull(16)
                    error = parsed == null
                    if (parsed != null) { val parts = FloatArray(3); AndroidColor.colorToHSV((parsed or 0xff000000).toInt(), parts); hue = parts[0]; saturation = parts[1]; brightness = parts[2] }
                }, label = { Text("HEX 色值") }, prefix = { Text("#") }, isError = error, singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = close) { Text("取消") }
                    Button(enabled = !error, onClick = { select(value) }) { Text("使用颜色") }
                }
            }
        }
    }
}

@Composable
fun CourseColorSettings(names: List<String>, preferences: SchedulePreferences, changed: () -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    var colors by remember { mutableStateOf(preferences.cardColors) }
    Text("默认课程色由主题生成；单独指定的颜色按课程名称应用到课表和小组件。")
    if (names.isEmpty()) Text("暂无课程，请先添加或同步课表。")
    names.forEach { name ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { selected = name }) { Text(name) }
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(colors[name] ?: LocalAppearance.current.card(name))).clickable { selected = name })
        }
    }
    selected?.let { name ->
        Dialog(onDismissRequest = { selected = null }) {
            Surface(shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(20.dp)) {
                    Text(name, style = MaterialTheme.typography.titleLarge)
                    ColorChoices(colors[name] ?: LocalAppearance.current.card(name)) {
                        colors = colors + (name to it); preferences.cardColors = colors; changed(); selected = null
                    }
                    TextButton(onClick = { colors = colors - name; preferences.cardColors = colors; changed(); selected = null }) { Text("恢复跟随主题") }
                    TextButton(onClick = { selected = null }) { Text("关闭") }
                }
            }
        }
    }
}
