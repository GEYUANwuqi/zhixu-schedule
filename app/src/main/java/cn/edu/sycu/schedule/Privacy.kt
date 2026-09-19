package cn.edu.sycu.schedule

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

object PrivacyConsent {
    const val VERSION = 2
    fun accepted(context: Context) = context.getSharedPreferences("privacy", Context.MODE_PRIVATE).getInt("version", 0) == VERSION
    fun accept(context: Context): Boolean = context.getSharedPreferences("privacy", Context.MODE_PRIVATE).edit().putInt("version", VERSION).commit()
}

internal fun canAcceptPrivacy(seconds: Int, bottom: Boolean) = seconds >= 5 && bottom

const val PRIVACY_TEXT = """欢迎使用知序课表。请阅读以下隐私说明后，自主决定是否使用。

1. 本地数据
课表、课程、显示设置与背景图片保存在本机。应用不内置广告或行为统计。卸载应用会删除本地应用数据。

2. 登录与同步
仅在你操作登录、同步时连接服务提供方。账号、密码和验证码在其真实登录页面中提交，应用不保存密码；必要登录凭证加密保存在本机，用于后续同步，可在设置中清除。服务提供方按其自身规则处理收到的信息。

3. 文件、日历、小组件与通知
选取图片或导入导出文件由你主动操作。日历权限仅用于写入本课表专用日历，并替换该日历中的旧课程。小组件会在桌面显示课程信息；导出文件和截图可能包含教师、地点等个人信息，请谨慎分享。
课程通知默认关闭，开启后会在通知栏显示课程信息，可能出现在锁屏；可自行选择提醒字段。通知和准时提醒权限仅用于课程提醒，可随时关闭。

4. 更新与反馈
检查更新时连接 GitHub 获取正式版本和更新日志；启动检查默认关闭，可自行开启。点击前往更新会在浏览器中打开发布页和安装包下载链接，下载由浏览器处理。项目与反馈链接通过浏览器打开；提交反馈前，请确认提交的信息中没有密码、登录凭证等不希望公开的个人信息。

5. 你的选择
可不登录使用本地课表功能，可拒绝可选权限、删除课表、清除登录状态或卸载应用。已导出的文件、已写入的系统日历和已提交的反馈需在相应位置另行管理。隐私问题可通过项目 Issues 联系维护者。

点击同意表示你已阅读并同意以上说明。"""

@Composable
fun PrivacyGate(onAgree: () -> Unit, onDecline: () -> Unit) {
    val scroll = rememberScrollState()
    var seconds by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (seconds < 5) { delay(1000); seconds++ }
        }
    }
    val bottom = scroll.maxValue != Int.MAX_VALUE && scroll.value >= scroll.maxValue
    BackHandler { onDecline() }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("隐私协议", style = MaterialTheme.typography.headlineSmall)
            Text("知序课表", style = MaterialTheme.typography.titleMedium)
            Surface(Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                Text(PRIVACY_TEXT, Modifier.verticalScroll(scroll).padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Text(if (bottom) "已到达协议底部" else "请向下滚动，阅读至协议底部", style = MaterialTheme.typography.bodySmall)
            Button(enabled = canAcceptPrivacy(seconds, bottom), onClick = onAgree, modifier = Modifier.fillMaxWidth()) {
                Text(if (seconds < 5) "我已完整阅读并同意（${5 - seconds}s）" else "我已完整阅读并同意")
            }
            TextButton(onClick = onDecline, modifier = Modifier.fillMaxWidth()) { Text("不同意并退出") }
        }
    }
}
