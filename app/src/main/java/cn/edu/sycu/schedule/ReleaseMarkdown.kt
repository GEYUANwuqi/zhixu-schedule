package cn.edu.sycu.schedule

import android.content.Intent
import android.net.Uri
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin

@Composable
internal fun ReleaseMarkdown(markdown: String) {
    val context = LocalContext.current
    val renderer = remember(context) {
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver { view, link ->
                        val uri = Uri.parse(link)
                        if (uri.scheme?.lowercase() in listOf("https", "http")) {
                            try { view.context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                            catch (_: Exception) { Toast.makeText(view.context, "无法打开链接，请安装浏览器后重试", Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
            }).build()
    }
    val rendered = remember(renderer, markdown) { renderer.toMarkdown(markdown) }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { TextView(it).apply { textSize = 14f; setLineSpacing(0f, 1.15f); setPadding(0, 0, 0, 0) } },
        update = { view ->
            view.setTextColor(textColor)
            view.setLinkTextColor(linkColor)
            renderer.setParsedMarkdown(view, rendered)
        },
    )
}
