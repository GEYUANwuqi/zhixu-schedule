package cn.edu.sycu.schedule

import android.appwidget.AppWidgetManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The launcher renders `previewLayout` live on Android 12+; older launchers show the pre-rendered
 * PNG. Both are driven by the same preview layout, and this test regenerates the PNG at 3× density.
 */
@RunWith(AndroidJUnit4::class)
class WidgetPreviewTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun previewLayoutsRenderToRegeneratedPreviewImages() {
        val targets =
            listOf(
                Triple("widget_preview_3x3", R.layout.widget_preview_3x3, 180 to 180),
                Triple("widget_preview_4x3", R.layout.widget_preview_4x3, 250 to 180),
            )
        targets.forEach { (name, layout, size) ->
            val bitmap = render(layout, size.first * 3, size.second * 3)
            val file = File(context.getExternalFilesDir(null) ?: context.cacheDir, "$name.png")
            try {
                file.outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
            assertTrue("$name should render a widget, not a flat image", file.length() > 4_000)
        }
    }

    @Test
    fun bothWidgetSizesPublishNameSizeIconAndPreview() {
        val manager = AppWidgetManager.getInstance(context)
        val declared =
            manager.getInstalledProviders().filter {
                it.provider.packageName == context.packageName
            }
        assertEquals(2, declared.size)
        declared.forEach { info ->
            val large = info.provider.className.endsWith("TodayWidgetLarge")
            assertEquals("知序 · 今日课表", info.loadLabel(context.packageManager).toString())
            assertNotEquals("widget needs an icon for the picker", 0, info.icon)
            assertNotEquals("widget needs a pre-rendered preview image", 0, info.previewImage)
            // Cell metrics are the only size contract available on every platform level,
            // and the platform reports them in pixels.
            val density = context.resources.displayMetrics.density
            assertEquals(Math.round((if (large) 250 else 180) * density), info.minWidth)
            assertEquals(Math.round(180 * density), info.minHeight)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                assertNotEquals("Android 12+ needs a live preview layout", 0, info.previewLayout)
                assertEquals(
                    if (large) 4 to 3 else 3 to 3,
                    info.targetCellWidth to info.targetCellHeight,
                )
            }
            // The picker description must name the real grid, never the old 3×4 wording.
            assertFalse(
                context.resources
                    .getString(
                        if (large) R.string.widget_large_description
                        else R.string.widget_description
                    )
                    .contains("3×4")
            )
        }
    }

    private fun render(layout: Int, width: Int, height: Int): Bitmap {
        val configuration =
            Configuration(context.resources.configuration).apply { densityDpi = 480 }
        val scaled = context.createConfigurationContext(configuration)
        var bitmap: Bitmap? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = LayoutInflater.from(scaled).inflate(layout, FrameLayout(scaled), false)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, width, height)
            bitmap =
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    view.draw(Canvas(it))
                }
        }
        return bitmap!!
    }
}
