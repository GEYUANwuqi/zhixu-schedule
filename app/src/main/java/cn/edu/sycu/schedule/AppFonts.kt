package cn.edu.sycu.schedule

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.util.AtomicFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal object AppFonts {
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "custom-font"))
    fun typeface(context: Context): Typeface? = runCatching {
        file(context).openRead().close()
        Typeface.Builder(file(context).baseFile).build()
    }.getOrNull()

    fun import(context: Context, uri: Uri) {
        val temporary = File.createTempFile("font-", ".tmp", context.cacheDir)
        try {
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 32 * 1024 * 1024) { "字体文件不能超过 32 MB" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            val signature = temporary.inputStream().use { it.readNBytesCompat() }
            require(signature in listOf("00010000", "4f54544f", "74746366", "74727565")) { "请选择有效的 TTF、OTF 或 TTC 字体文件" }
            requireNotNull(Typeface.Builder(temporary).build()) { "字体文件无效" }
            val destination = file(context)
            val stream = destination.startWrite()
            try {
                temporary.inputStream().use { it.copyTo(stream) }
                destination.finishWrite(stream)
            } catch (e: Exception) { destination.failWrite(stream); throw e }
        } finally { temporary.delete() }
    }

    private fun java.io.InputStream.readNBytesCompat(): String = (0 until 4).joinToString("") { "%02x".format(read()) }
    fun reset(context: Context) = file(context).delete()
    fun typography(context: Context): Typography {
        val base = Typography()
        val family = typeface(context)?.let { FontFamily(it) } ?: return base
        return base.copy(
            displayLarge = base.displayLarge.copy(fontFamily = family), displayMedium = base.displayMedium.copy(fontFamily = family), displaySmall = base.displaySmall.copy(fontFamily = family),
            headlineLarge = base.headlineLarge.copy(fontFamily = family), headlineMedium = base.headlineMedium.copy(fontFamily = family), headlineSmall = base.headlineSmall.copy(fontFamily = family),
            titleLarge = base.titleLarge.copy(fontFamily = family), titleMedium = base.titleMedium.copy(fontFamily = family), titleSmall = base.titleSmall.copy(fontFamily = family),
            bodyLarge = base.bodyLarge.copy(fontFamily = family), bodyMedium = base.bodyMedium.copy(fontFamily = family), bodySmall = base.bodySmall.copy(fontFamily = family),
            labelLarge = base.labelLarge.copy(fontFamily = family), labelMedium = base.labelMedium.copy(fontFamily = family), labelSmall = base.labelSmall.copy(fontFamily = family),
        )
    }
}

@Composable
internal fun FontSettings(onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(AppFonts.typeface(context) != null) }
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { AppFonts.import(context, uri) }
                custom = true
                onChanged()
                message = "字体已应用"
            } catch (e: Exception) { message = "无法使用此字体，请检查格式和文件大小（最大 32 MB）" }
            finally { busy = false }
        }
    }
    SettingsSection("字体", "选择 TTF、OTF 或 TTC 文件，应用到整个 App。字体保存在本机；缺失的字形使用系统字体。系统界面和学校网页保持原有字体。")
    Text(if (custom) "当前：自选字体" else "当前：系统默认")
    OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) { Text(if (busy) "正在导入…" else "选择字体文件") }
    TextButton(enabled = custom && !busy, onClick = { AppFonts.reset(context); custom = false; message = null; onChanged() }) { Text("恢复默认字体") }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
