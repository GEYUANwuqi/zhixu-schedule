package cn.edu.sycu.schedule

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class CropSaveTest {
    @Test fun rightEdgeSelectionUsesTheSameCoordinatesAsPreview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = java.io.File(context.cacheDir, "crop-regression.png")
        val output = java.io.File(context.filesDir, BackgroundKind.App.fileName)
        val previous = output.takeIf { it.exists() }?.readBytes()
        try {
            val source = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
            source.eraseColor(Color.RED)
            Canvas(source).drawRect(2400f, 0f, 4000f, 3000f, Paint().apply { color = Color.BLUE })
            input.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            source.recycle()
            val preview = Backgrounds.preview(context, Uri.fromFile(input))!!
            val base = centerCrop(preview.width.toFloat(), preview.height.toFloat(), 9f / 16f)
            val selection = CropBox(preview.width - base.width, 0f, preview.width.toFloat(), base.height)
            Backgrounds.save(context, BackgroundKind.App, Uri.fromFile(input), selection)
            val saved = BitmapFactory.decodeFile(output.path)
            assertNotNull(saved)
            // Fractional preview edges may include one additional boundary pixel.
            assertTrue(kotlin.math.abs(base.width - saved.width) <= 1f)
            assertEquals(base.height.toInt(), saved.height)
            assertTrue(Color.blue(saved.getPixel(saved.width - 10, saved.height / 2)) > 220)
            saved.recycle()
            preview.recycle()
        } finally {
            input.delete()
            if (previous != null) output.writeBytes(previous) else output.delete()
        }
    }
}
