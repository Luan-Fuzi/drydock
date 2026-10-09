package dev.drydock.prototype

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource

/**
 * 主页（D27 界面定义 v1）：底部三栏——会话 / 文件 / 设置。
 * 空状态承载首启教育；旧验收仪器（MainActivity）收进设置的「开发者工具」。
 */
class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 验收注入口只在 debug 包里有实现（src/debug/DebugHooks），release 为空
        DebugHooks.handle(this, intent)
        // D25 文件互通：系统分享目标（文件流或文本 → workspace Inbox）
        if (android.content.Intent.ACTION_SEND == intent.action) handleSend(intent)
        ThemeStore.init(this)
        setContent {
            DrydockTheme { HomeScreen() }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (android.content.Intent.ACTION_SEND == intent.action) handleSend(intent)
    }

    private fun handleSend(intent: android.content.Intent) {
        val stream = intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
        val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
        if (stream == null && text.isNullOrBlank()) return
        Thread {
            val f = if (stream != null) {
                FileBridge.importUri(this, stream)
            } else {
                FileBridge.importText(this, text!!)
            }
            android.util.Log.i(
                "DrydockFile",
                if (f != null) "分享已导入 Inbox：${f.name}" else "分享导入失败",
            )
        }.start()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen() {
    // saveable：旋转/重建后停在原 tab（主题等触发 recreate 的场景不再跳页）
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("▶") }, label = { Text(stringResource(R.string.nav_label_sessions)) }, modifier = Modifier.testTag("nav_sessions"))
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("▤") }, label = { Text(stringResource(R.string.nav_label_files)) }, modifier = Modifier.testTag("nav_files"))
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("⚙") }, label = { Text(stringResource(R.string.nav_label_settings)) }, modifier = Modifier.testTag("nav_settings"))
            }
        },
    ) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> SessionPane()
                1 -> FilePane()
                else -> SettingsPane()
            }
        }
    }
}
