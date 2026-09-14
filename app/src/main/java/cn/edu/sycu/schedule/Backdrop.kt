package cn.edu.sycu.schedule

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Small in-memory software blur for Android 8–11, where Compose RenderEffect is unavailable. */
fun blurredBackdrop(view: View): ImageBitmap {
    val width = (view.width / 8).coerceAtLeast(1)
    val height = (view.height / 8).coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.scale(width.toFloat() / view.width, height.toFloat() / view.height)
    view.draw(canvas)
    var pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    repeat(3) {
        val output = IntArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) {
            var red = 0
            var green = 0
            var blue = 0
            var count = 0
            for (dy in -2..2) for (dx in -2..2) {
                val p =
                    pixels[
                        (y + dy).coerceIn(0, height - 1) * width + (x + dx).coerceIn(0, width - 1)]
                red += p shr 16 and 255
                green += p shr 8 and 255
                blue += p and 255
                count++
            }
            output[y * width + x] =
                (255 shl 24) or ((red / count) shl 16) or ((green / count) shl 8) or (blue / count)
        }
        pixels = output
    }
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap.asImageBitmap()
}
