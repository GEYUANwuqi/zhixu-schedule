package cn.edu.sycu.schedule

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

const val PROJECT_URL = "https://github.com/GEYUANwuqi/zhixu-schedule"

internal fun versionParts(value: String): List<java.math.BigInteger> {
    require(Regex("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matches(value))
    return value.removePrefix("v").split('.').map { it.toBigInteger() }
}

internal fun isNewerVersion(remote: String, local: String): Boolean {
    val a = versionParts(remote); val b = versionParts(local)
    for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
    return false
}

internal data class ReleaseCheck(val message: String, val tag: String? = null)

internal fun releaseResult(status: Int, body: String, current: String): ReleaseCheck {
    if (status == 404) return ReleaseCheck("暂未发布正式版本")
    if (status == 403 || status == 429) return ReleaseCheck("检查过于频繁，请稍后重试")
    require(status == 200) { "检查更新失败，请稍后重试" }
    val json = JSONObject(body)
    require(!json.getBoolean("prerelease") && !json.getBoolean("draft")) { "暂未找到可用的正式版本" }
    val tag = json.getString("tag_name")
    return if (isNewerVersion(tag, current)) ReleaseCheck("发现新版本 ${tag.removePrefix("v")}", tag)
        else ReleaseCheck("当前已是最新版本（$current）")
}

private fun checkRelease(): ReleaseCheck {
    val connection = URL("https://api.github.com/repos/GEYUANwuqi/zhixu-schedule/releases/latest").openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "Zhixu-Schedule/${BuildConfig.VERSION_NAME}")
        val status = connection.responseCode
        val body = if (status == 200) connection.inputStream.use { input ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(bytes.size() + n <= 1024 * 1024) { "更新信息异常" }
                bytes.write(buffer, 0, n)
            }
            bytes.toString("UTF-8")
        } else ""
        return releaseResult(status, body, BuildConfig.VERSION_NAME)
    } finally { connection.disconnect() }
}

@Composable
fun AboutPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ReleaseCheck?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var document by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun open(url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { notice = "无法打开链接，请安装浏览器后重试" }
    }
    fun readLicense(path: String) {
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { context.assets.open(path).bufferedReader().use { it.readText() } }
                document = path.substringAfterLast('/') to text
            } catch (_: Exception) { notice = "无法读取许可证" }
        }
    }
    Text(context.applicationInfo.loadLabel(context.packageManager).toString(), style = MaterialTheme.typography.headlineSmall)
    Text("版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
    Text("一款独立的课表应用。")
    OutlinedButton(onClick = { document = "隐私协议" to PRIVACY_TEXT }) { Text("隐私协议") }
    OutlinedButton(onClick = { readLicense("licenses/PROJECT-LICENSE.txt") }) { Text("项目许可证 · 自定义部分开放") }
    Text("第三方内容适用其各自许可证。", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { open(PROJECT_URL) }) { Text("项目地址") }
    OutlinedButton(enabled = !checking, onClick = {
        checking = true; result = null
        scope.launch {
            try { result = withContext(Dispatchers.IO) { checkRelease() } }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                result = ReleaseCheck("无法检查更新，请检查网络后重试")
            } finally { checking = false }
        }
    }) { Text(if (checking) "正在检查…" else "检查更新") }
    result?.let { r ->
        Text(r.message)
        r.tag?.let { tag -> TextButton(onClick = { open("$PROJECT_URL/releases/tag/$tag") }) { Text("前往下载新版本") } }
    }
    OutlinedButton(onClick = { open("$PROJECT_URL/issues") }) { Text("反馈问题") }
    notice?.let { text -> Text(text, color = MaterialTheme.colorScheme.error) }
    document?.let { (title, text) ->
        AlertDialog(onDismissRequest = { document = null }, title = { Text(title) },
            text = { SelectionContainer { Text(text, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = { document = null }) { Text("关闭") } })
    }
}
