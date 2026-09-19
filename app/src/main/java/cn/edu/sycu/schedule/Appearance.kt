package cn.edu.sycu.schedule

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.lightColorScheme

fun tintColor(base: Int, white: Float): Int {
    val amount = white.coerceIn(0f, 1f)
    fun channel(shift: Int) = (((base ushr shift) and 255) * (1 - amount) + 255 * amount).toInt()
    return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}
fun readableColor(background: Int): Int = if (Color(background).luminance() > .42f) 0xff202420.toInt() else 0xffffffff.toInt()
data class ThemePalette(val name: String, val primary: Int, val background: Int, val surface: Int, val selected: Int, val soft: Int, val outline: Int, val courseColors: List<Int>)
private fun palette(name: String, vararg rgb: Long) = ThemePalette(name, rgb[0].toInt(), rgb[1].toInt(), rgb[2].toInt(), rgb[3].toInt(), rgb[4].toInt(), rgb[5].toInt(), rgb.drop(6).map { it.toInt() })
val themePalettes = listOf(
    palette("青苔", 0xff52754f, 0xfff5f7ef, 0xfffcfdf8, 0xffd4e4c9, 0xffe9eee1, 0xffaabca1, 0xffdce7cc, 0xffeee4cf, 0xffd3e5df, 0xffe6dceb, 0xffefdad2),
    palette("海盐", 0xff3f6f8f, 0xfff1f6f8, 0xfffafcfd, 0xffcde3ed, 0xffe3edf2, 0xff9cb9c9, 0xffd2e5ef, 0xffd5e8e1, 0xffe4dff0, 0xffece4d3, 0xfff0dce2),
    palette("暮樱", 0xff8a5a78, 0xfffaf3f6, 0xfffffafc, 0xffeed4e3, 0xfff1e5ec, 0xffc7a5b9, 0xffeed7e3, 0xffe2dcee, 0xfff1dfd2, 0xffdce8df, 0xffdbe5ef),
    palette("麦茶", 0xffa66a2c, 0xfffaf6ee, 0xfffffcf6, 0xfff1dfbf, 0xfff2eadb, 0xffcbb28d, 0xffefdfbd, 0xffe9d6c7, 0xffdfe4ca, 0xffd9e5e3, 0xffe6dce7),
    palette("远山", 0xff5d647a, 0xfff4f5f8, 0xfffcfcfe, 0xffdce0ed, 0xffe8eaf1, 0xffadb3c5, 0xffdce1ef, 0xffe5dcec, 0xffd5e5e0, 0xffebe2d2, 0xffeadbdc),
)
fun themePalette(seed: Int) = themePalettes.firstOrNull { it.primary == seed } ?: themePalettes.first()
data class Appearance(private val theme: Int = 0xff52754f.toInt(), val opacity: Int = 100, val cards: Map<String, Int> = emptyMap(), val pastCourseOpacity: Int = 60) {
    private val palette get() = themePalette(theme)
    val seed get() = palette.primary
    val background get() = palette.background
    val surface get() = palette.surface
    val selected get() = palette.selected
    val soft get() = palette.soft
    val outline get() = palette.outline
    val ink get() = readableColor(background)
    fun card(name: String) = cards[name] ?: palette.courseColors[Math.floorMod(name.hashCode(), palette.courseColors.size)]
    fun scheme() = lightColorScheme(primary = Color(seed), onPrimary = Color(readableColor(seed)),
        primaryContainer = Color(selected), onPrimaryContainer = Color(readableColor(selected)),
        secondary = Color(seed), onSecondary = Color(readableColor(seed)), secondaryContainer = Color(soft), onSecondaryContainer = Color(ink),
        tertiary = Color(seed), onTertiary = Color(readableColor(seed)), tertiaryContainer = Color(selected), onTertiaryContainer = Color(ink),
        background = Color(background), onBackground = Color(ink), surface = Color(surface), onSurface = Color(ink),
        surfaceVariant = Color(soft), onSurfaceVariant = Color(ink), outline = Color(outline), outlineVariant = Color(soft),
        surfaceContainer = Color(soft), surfaceContainerHigh = Color(soft), surfaceContainerHighest = Color(selected),
        surfaceContainerLow = Color(background), surfaceContainerLowest = Color(surface))
}
val LocalAppearance = staticCompositionLocalOf { Appearance() }
