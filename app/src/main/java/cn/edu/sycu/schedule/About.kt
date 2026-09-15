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

internal data class ReleaseCheck(val message: String, val tag: String? = null, val published: String = "", val notes: String = "", val download: String? = null)

internal fun releaseResult(status: Int, body: String, current: String): ReleaseCheck {
    if (status == 404) return ReleaseCheck("暂未发布正式版本")
    if (status == 403 || status == 429) return ReleaseCheck("检查过于频繁，请稍后重试")
    require(status == 200) { "检查更新失败，请稍后重试" }
    val json = JSONObject(body)
    require(!json.getBoolean("prerelease") && !json.getBoolean("draft")) { "暂未找到可用的正式版本" }
    val tag = json.getString("tag_name")
    val expected = "$PROJECT_URL/releases/download/$tag/zhixu-${tag.removePrefix("v")}.apk"
    val assets = json.optJSONArray("assets")
    val download = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
        .firstOrNull { it.optString("browser_download_url") == expected }?.optString("browser_download_url")
    return if (isNewerVersion(tag, current)) ReleaseCheck("发现新版本", tag, json.optString("published_at"), json.optString("body").takeUnless { it == "null" }.orEmpty(), download)
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
        connection.useCaches = false
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
    var autoCheck by remember { mutableStateOf(SchedulePreferences(context).autoCheckUpdates) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("启动时检查更新", Modifier.weight(1f))
        Switch(autoCheck, { autoCheck = it; SchedulePreferences(context).autoCheckUpdates = it })
    }
    Text("开启后将在启动时检查是否为最新版本", style = MaterialTheme.typography.bodySmall)
    result?.let { r ->
        if (r.tag == null) Text(r.message)
        else UpdateDialog(r) { result = null }
    }
    OutlinedButton(onClick = { open("$PROJECT_URL/issues") }) { Text("反馈问题") }
    notice?.let { text -> Text(text, color = MaterialTheme.colorScheme.error) }
    document?.let { (title, text) ->
        AlertDialog(onDismissRequest = { document = null }, title = { Text(title) },
            text = { SelectionContainer { Text(text, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = { document = null }) { Text("关闭") } })
    }
}

@Composable
internal fun UpdateDialog(release: ReleaseCheck, close: () -> Unit) {
    val context = LocalContext.current
    var opening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!opening) close() }, title = { Text("发现新版本") }, text = {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("版本 ${release.tag?.removePrefix("v")}")
            val date = remember(release.published) { runCatching { java.time.Instant.parse(release.published).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }.getOrDefault("暂无发布时间") }
            Text("发布时间：$date")
            ReleaseMarkdown(release.notes.ifBlank { "此版本尚未填写更新日志。" })
            if (release.download == null) Text("暂未找到安装包，可前往发布页查看。")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, dismissButton = { TextButton(enabled = !opening, onClick = close) { Text("取消") } }, confirmButton = {
        TextButton(enabled = !opening, onClick = {
            opening = true
            fun browser(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                // Resolve a browser instead of a GitHub app or APK installer.
                selector = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
                putExtra("create_new_tab", true)
            }
            try {
                context.startActivity(browser("$PROJECT_URL/releases/tag/${release.tag}"))
                release.download?.let { url ->
                    try { context.startActivity(browser(url)) }
                    catch (_: Exception) { android.widget.Toast.makeText(context, "无法打开下载链接，请在发布页点击 APK 下载", android.widget.Toast.LENGTH_LONG).show() }
                }
                close()
            } catch (_: Exception) { error = "无法打开浏览器，请安装浏览器后重试" }
            opening = false
        }) { Text(if (opening) "正在前往…" else "前往更新") }
    })
}

@Composable
fun StartupUpdateCheck() {
    val context = LocalContext.current
    var checked by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var release by remember { mutableStateOf<ReleaseCheck?>(null) }
    LaunchedEffect(Unit) {
        if (!checked) {
            checked = true
            if (SchedulePreferences(context).autoCheckUpdates) {
                try { release = withContext(Dispatchers.IO) { checkRelease() }.takeIf { it.tag != null } }
                catch (e: Exception) { if (e is CancellationException) throw e }
            }
        }
    }
    release?.let { UpdateDialog(it) { release = null } }
}
