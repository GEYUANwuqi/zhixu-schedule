package cn.edu.sycu.schedule

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.util.SizeF
import java.io.File
import java.io.InputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Which surface a background image belongs to; the App and the widget are chosen separately. */
enum class BackgroundKind(val fileName: String) {
    App("app-background.jpg"),
    Widget("widget-background.jpg"),
}

/** Centre-crop rectangle inside a source image, in source pixels. */
data class CropBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float
        get() = right - left
    val height: Float
        get() = bottom - top
}

/**
 * Centre crop of a [width]×[height] source for [aspect] (width ÷ height). It matches what
 * `ContentScale.Crop` and `ImageView` `centerCrop` do, so the settings preview and the rendered
 * background agree.
 */
fun centerCrop(width: Float, height: Float, aspect: Float): CropBox {
    require(width > 0f && height > 0f && aspect > 0f) { "裁剪参数必须为正数" }
    return if (width / height > aspect) {
        val cropped = height * aspect
        CropBox((width - cropped) / 2f, 0f, (width + cropped) / 2f, height)
    } else {
        val cropped = width / aspect
        CropBox(0f, (height - cropped) / 2f, width, (height + cropped) / 2f)
    }
}

/**
 * Typical home-screen geometry used by the widget background boxes.
 *
 * Home-screen cells are not square. The official sizing table measures a Pixel 4 in portrait as
 * `(73n − 16) × (118m − 16)` dp — a widget occupying 3×3 cells is about 203×338 dp, clearly taller
 * than it is wide, and 4×3 is 276×338 dp, one 73 dp column wider. Landscape cells go the other way
 * (142×66 dp) and every launcher differs, so a real widget reports its own size through
 * [Backgrounds.widgetSizePx] and this table is only the fallback and the preview guide.
 *
 * https://developer.android.com/develop/ui/views/appwidgets/layouts
 */
object WidgetGeometry {
    const val CELL_WIDTH_DP = 73f
    const val CELL_HEIGHT_DP = 118f
    const val INSET_DP = 16f

    fun widthDp(columns: Int) = CELL_WIDTH_DP * columns - INSET_DP

    fun heightDp(rows: Int) = CELL_HEIGHT_DP * rows - INSET_DP
}

/**
 * One local image per surface, copied into app storage so the widget never depends on a
 * launcher-visible URI grant.
 */
object Backgrounds {
    /** White veil over any background image; text stays readable on busy photos. */
    const val VEIL = 0.68f

    private const val LONG_EDGE = 1920

    /** Keeps a large widget background inside a sensible bitmap budget; views stretch to fit. */
    const val MAX_PIXELS = 1_200_000

    fun file(context: Context, kind: BackgroundKind) = File(context.filesDir, kind.fileName)

    fun exists(context: Context, kind: BackgroundKind) = file(context, kind).isFile

    fun save(context: Context, kind: BackgroundKind, uri: Uri, selection: CropBox? = null) {
        val source =
            decode(LONG_EDGE) { context.contentResolver.openInputStream(uri) }
                ?: error("无法读取所选图片")
        val selected = selection?.let { box ->
            require(listOf(box.left, box.top, box.right, box.bottom).all { it.isFinite() })
            val left = box.left.toInt().coerceIn(0, source.width - 1)
            val top = box.top.toInt().coerceIn(0, source.height - 1)
            val right = box.right.toInt().coerceIn(left + 1, source.width)
            val bottom = box.bottom.toInt().coerceIn(top + 1, source.height)
            Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        } ?: source
        val scaled = scaled(selected, LONG_EDGE)
        val destination = android.util.AtomicFile(file(context, kind))
        var output: java.io.FileOutputStream? = null
        try {
            output = destination.startWrite()
            check(scaled.compress(Bitmap.CompressFormat.JPEG, 88, output)) { "背景图写入失败" }
            destination.finishWrite(output)
        } catch (error: Exception) {
            destination.failWrite(output)
            throw error
        } finally {
            if (scaled !== selected) scaled.recycle()
            if (selected !== source) selected.recycle()
            source.recycle()
        }
    }

    fun preview(context: Context, uri: Uri): Bitmap? = decode(LONG_EDGE) { context.contentResolver.openInputStream(uri) }

    fun clear(context: Context, kind: BackgroundKind) {
        val file = file(context, kind)
        if (file.exists()) file.delete()
    }

    /** Raw image of [kind]; callers crop and veil it themselves. */
    fun bitmap(context: Context, kind: BackgroundKind): Bitmap? =
        if (exists(context, kind)) decode(LONG_EDGE) { file(context, kind).inputStream() } else null

    /**
     * Widget background: already cropped to [width]×[height], rounded and veiled, and small enough
     * to survive the RemoteViews binder limit.
     */
    fun widgetBitmap(context: Context, width: Int, height: Int): Bitmap? {
        val appearance = SchedulePreferences(context).appearance()
        val budget = widgetBitmapSize(width, height)
        val source =
            if (exists(context, BackgroundKind.Widget)) decode(maxOf(budget.width, budget.height)) {
                file(context, BackgroundKind.Widget).inputStream()
            } else null
        val target = Bitmap.createBitmap(budget.width, budget.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        val corner = budget.height / 9f
        canvas.clipPath(
            Path().apply {
                addRoundRect(
                    RectF(0f, 0f, budget.width.toFloat(), budget.height.toFloat()),
                    corner,
                    corner,
                    Path.Direction.CW,
                )
            }
        )
        canvas.drawColor(appearance.background)
        source?.let {
            canvas.drawBitmap(it, cropMatrix(it, budget), Paint(Paint.FILTER_BITMAP_FLAG))
            it.recycle()
            canvas.drawColor((appearance.background and 0x00ffffff) or ((VEIL * 255).toInt() shl 24))
        }
        // Apply opacity to the completed image + theme veil, never the text.
        canvas.drawColor(Color.argb(appearance.opacity * 255 / 100, 255, 255, 255), PorterDuff.Mode.DST_IN)
        return target
    }

    /**
     * Size in px of a [columns]×[rows] widget: what the launcher reports for this instance when it
     * reports anything, otherwise the typical table size from [WidgetGeometry]. Reconciling the
     * launcher's minimum and maximum bounds with the rendered widget is what keeps a photo from
     * being squashed, because home-screen cells are not square and the span is not guaranteed.
     */
    fun widgetSizePx(context: Context, columns: Int, rows: Int, options: Bundle?): Size {
        val density = context.resources.displayMetrics.density
        val reported = options?.let { reportedSizeDp(context, it) }
        val width = reported?.width ?: WidgetGeometry.widthDp(columns)
        val height = reported?.height ?: WidgetGeometry.heightDp(rows)
        return Size(
            (width * density).roundToInt().coerceAtLeast(1),
            (height * density).roundToInt().coerceAtLeast(1),
        )
    }

    /** Downsizes a requested widget background that would otherwise need a very large bitmap. */
    fun widgetBitmapSize(width: Int, height: Int): Size {
        val pixels = width.toLong() * height
        if (pixels <= MAX_PIXELS) return Size(width, height)
        val factor = sqrt(MAX_PIXELS.toFloat() / pixels)
        val scaled = (width * factor).toInt().coerceAtLeast(1)
        val limited = (height * factor).toInt().coerceAtLeast(1)
        return Size(scaled, limited.coerceAtMost(MAX_PIXELS / scaled))
    }

    private fun reportedSizeDp(context: Context, options: Bundle): SizeF? {
        val landscape =
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // Launchers publish the narrower (portrait) width as the minimum and the taller (portrait)
        // height as the maximum — on API 28 Launcher3 reports 214×208dp / 336×306dp for an instance
        // that really measures 214×306dp in portrait. Picking both minima would squash the image.
        val width =
            options.getInt(
                if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
            )
        val height =
            options.getInt(
                if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
            )
        return SizeF(width.toFloat(), height.toFloat()).takeIf { it.width > 0f && it.height > 0f }
    }

    /** Matrix that centre-crops [source] into a [target]×[target] surface. */
    private fun cropMatrix(source: Bitmap, target: Size): Matrix {
        val box =
            centerCrop(
                source.width.toFloat(),
                source.height.toFloat(),
                target.width.toFloat() / target.height,
            )
        return Matrix().apply {
            setRectToRect(
                RectF(box.left, box.top, box.right, box.bottom),
                RectF(0f, 0f, target.width.toFloat(), target.height.toFloat()),
                Matrix.ScaleToFit.FILL,
            )
        }
    }

    private fun scaled(source: Bitmap, max: Int): Bitmap {
        val long = maxOf(source.width, source.height)
        if (long <= max) return source
        val ratio = max.toFloat() / long
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).roundToInt().coerceAtLeast(1),
            (source.height * ratio).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    /** Bounds probe then a sampled decode; null whenever the stream cannot be read as an image. */
    private fun decode(max: Int, open: () -> InputStream?): Bitmap? =
        runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var sample = 1
                while (
                    bounds.outWidth / (sample * 2) >= max || bounds.outHeight / (sample * 2) >= max
                )
                    sample *= 2
                open()?.use {
                    BitmapFactory.decodeStream(
                        it,
                        null,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                }
            }
            .getOrNull()
}
