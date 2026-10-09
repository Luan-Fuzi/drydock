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
import java.io.File

/**
 * 主页（D27 界面定义 v1）：底部三栏——会话 / 文件 / 设置。
 * 空状态承载首启教育；旧验收仪器（MainActivity）收进设置的「开发者工具」。
 */
class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // debug 注入口与 MainActivity 同源（无视觉环境验收经 am start --es 驱动）
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            intent?.getStringExtra("drydock_endpoint")?.takeIf { it.contains("|") }?.let { spec ->
                // D30 列表化：格式 "PROTOCOL|base_url|model|context[|provider|envvar[|output]]"，追加进端点列表
                val p = spec.split("|")
                runCatching { EndpointStore.Protocol.valueOf(p[0]) }.getOrNull()?.let { proto ->
                    EndpointStore.add(this, proto, p[1], p.getOrElse(2) { "" },
                        p.getOrElse(3) { "" }.trim().takeIf { it.isNotBlank() && it != "-" }?.toLongOrNull(),
                        p.getOrElse(6) { "" }.trim().takeIf { it.isNotBlank() && it != "-" }?.toLongOrNull(),
                        p.getOrElse(5) { "DRYDOCK_API_KEY" }, p.getOrElse(4) { "" })
                }
            }
            intent?.getStringExtra("drydock_recipe")?.takeIf { it.isNotBlank() }?.let { ids ->
                val recipes = ids.split(",").mapNotNull { RecipeManager.byId(it.trim()) }
                Thread {
                    val appCtx = applicationContext
                    if (!RootfsManager.isDeployed(appCtx)) {
                        RootfsManager.deploy(appCtx) { }
                        if (!RootfsManager.isDeployed(appCtx)) return@Thread
                    }
                    recipes.forEach { r ->
                        val res = RecipeManager.ensure(appCtx, r) { }
                        android.util.Log.i("DrydockRecipe", "ensure ${r.id} <${res.output.takeLast(300)}>")
                        if (res.output.contains("RECIPE_RC=0")) RecipeManager.markInstalled(appCtx, r.id)
                    }
                    android.util.Log.i("DrydockRecipe", "cfg <${RecipeManager.applyEndpointConfig(appCtx).output.takeLast(300)}>")
                }.start()
            }
            intent?.getStringExtra("drydock_exec64")?.takeIf { it.isNotBlank() }?.let { b64 ->
                Thread {
                    val cmd = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                    val r = RootfsManager.runInEnv(applicationContext, cmd)
                    try {
                        File(applicationContext.filesDir, "exec-out.txt").writeText("EXEC_DONE exit=${r.exitCode}\n${r.output}")
                    } catch (_: Exception) {
                    }
                    android.util.Log.i("DrydockExec", "EXEC_DONE exit=${r.exitCode} (full output in files/exec-out.txt)")
                }.start()
            }
            // 环境导出（夜批验收通道，产品入口在设置页「环境与备份」）
            if (intent?.getStringExtra("drydock_export") != null) {
                Thread {
                    val out = try {
                        "EXPORT_DONE " + RootfsManager.exportEnvTar(applicationContext)
                    } catch (e: Exception) {
                        "EXPORT_FAILED $e"
                    }
                    try {
                        File(applicationContext.filesDir, "exec-out.txt").writeText(out)
                    } catch (_: Exception) {
                    }
                    android.util.Log.i("DrydockExec", out.take(200))
                }.start()
            }
            // 环境救援转发（原 MainActivity 通道）：drydock_rescue → RescueActivity
            intent?.getStringExtra("drydock_rescue")?.takeIf { it.isNotBlank() }?.let { rc ->
                startActivity(
                    android.content.Intent(this, RescueActivity::class.java).putExtra("drydock_cmd", rc),
                )
            }
            // D25 文件互通：系统分享目标（文件流或文本 → workspace Inbox）——原 MainActivity 通道迁入
            if (android.content.Intent.ACTION_SEND == intent.action) handleSend(intent)
            // provider 全回路自测（写→读→改名→列举→删除），写→读经 contentResolver 走
            // grant 免权限路径，与 DocumentsUI 同口径；结果落 files/exec-out.txt
            if (intent?.getStringExtra("drydock_provider_test") != null) {
                Thread {
                    val sb = StringBuilder()
                    try {
                        val resolver = contentResolver
                        val uri = android.provider.DocumentsContract.buildDocumentUri(
                            WorkspaceProvider.AUTHORITY, "/zz-provider-test.txt")
                        resolver.openOutputStream(uri, "w")!!.use { it.write("PROBE_WRITE_OK\n".toByteArray()) }
                        sb.append("write=ok\n")
                        val txt = resolver.openInputStream(uri)!!.bufferedReader().readText()
                        sb.append("read_ok=").append(txt.contains("PROBE_WRITE_OK")).append('\n')
                        val renamed = android.provider.DocumentsContract.renameDocument(
                            resolver, uri, "zz-provider-renamed.txt")
                        sb.append("rename_uri=").append(renamed != null).append('\n')
                        val kids = android.provider.DocumentsContract.buildChildDocumentsUri(
                            WorkspaceProvider.AUTHORITY, "/")
                        var listed = false
                        resolver.query(
                            kids, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                            null, null, null,
                        )?.use { c ->
                            while (c.moveToNext()) if (c.getString(0) == "zz-provider-renamed.txt") listed = true
                        }
                        sb.append("children_listed=").append(listed).append('\n')
                        android.provider.DocumentsContract.deleteDocument(resolver, renamed ?: uri)
                        val gone = !File(RootfsManager.rootfsDir(applicationContext), "root/zz-provider-renamed.txt").exists() &&
                            !File(RootfsManager.rootfsDir(applicationContext), "root/zz-provider-test.txt").exists()
                        sb.append("delete_gone=").append(gone).append('\n')
                        sb.append("PROVIDER_TEST_RC=0")
                    } catch (e: Exception) {
                        sb.append("EXCEPTION ").append(e).append("\nPROVIDER_TEST_RC=1")
                    }
                    try {
                        File(applicationContext.filesDir, "exec-out.txt").writeText(sb.toString())
                    } catch (_: Exception) {
                    }
                    android.util.Log.i("DrydockExec", "provider selftest done")
                }.start()
            }
        }
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
