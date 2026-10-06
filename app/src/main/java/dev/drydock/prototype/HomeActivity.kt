package dev.drydock.prototype

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 外观偏好（D27）：跟随系统 / 浅色 / 深色。 */
object ThemeStore {
    enum class Mode { SYSTEM, LIGHT, DARK }
    fun load(context: Context): Mode =
        context.getSharedPreferences("drydock", Context.MODE_PRIVATE)
            .getString("theme_mode", null)?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.SYSTEM

    fun save(context: Context, mode: Mode) {
        context.getSharedPreferences("drydock", Context.MODE_PRIVATE)
            .edit().putString("theme_mode", mode.name).apply()
    }
}

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
                // D30 列表化：格式 "PROTOCOL|base_url|model|context[|provider|envvar]"，追加进端点列表
                val p = spec.split("|")
                runCatching { EndpointStore.Protocol.valueOf(p[0]) }.getOrNull()?.let { proto ->
                    EndpointStore.add(this, proto, p[1], p.getOrElse(2) { "" },
                        p.getOrElse(3) { "" }.trim().takeIf { it.isNotBlank() && it != "-" }?.toLongOrNull(),
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

/** 全 app 主题入口：按 ThemeStore 切换（会话/文件/设置三栏生效；向导与救援页维持深色）。 */
@Composable
fun DrydockTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val mode = remember { ThemeStore.load(context) }
    val dark = when (mode) {
        ThemeStore.Mode.DARK -> true
        ThemeStore.Mode.LIGHT -> false
        ThemeStore.Mode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen() {
    var tab by remember { mutableStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("▶") }, label = { Text("会话") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("▤") }, label = { Text("文件") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("⚙") }, label = { Text("设置") })
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

/** 会话页：管理已开终端、新建；空状态 = 首启教育。 */
/** 等会话 ttyd 就绪：注册表项在（既有会话）不代表 holder/ttyd 活着，且重建 ttyd
 *  会换端口——每轮重读注册表并对当前端口做 TCP 探活。冷启动串行重建每会话
 *  ~10-15s（proot+ttyd 就绪探测自身 20s 上限），预算默认 120s。超时返回 null。 */
private suspend fun awaitSessionReady(
    appCtx: Context,
    name: String,
    timeoutMs: Long = 120_000,
): TerminalManager.Session? {
    val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
    while (android.os.SystemClock.elapsedRealtime() < deadline) {
        val s = withContext(Dispatchers.IO) {
            TerminalManager.readSessions(appCtx).firstOrNull { it.name == name }?.let { sess ->
                try {
                    java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", sess.port), 500) }
                    sess
                } catch (_: Exception) {
                    null
                }
            }
        }
        if (s != null) return s
        delay(1000)
    }
    return null
}

@Composable
private fun SessionPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf("") }
    var closeTarget by remember { mutableStateOf<TerminalManager.Session?>(null) }
    val sessions = remember(tick) { TerminalManager.readSessions(context) }

    // 会话列表保鲜：回主页/停留期间 5s 轮询注册表（修「回来不刷新」）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            tick++
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("终端会话", style = MaterialTheme.typography.titleLarge)

        if (sessions.isEmpty()) {
            Text(
                "Drydock 让 coding agent 在手机上常驻干活。\n\n" +
                    "第一次使用：先到「设置 → 初始设置」完成三步配置（保活、端点与模型、安装 agent），" +
                    "然后点下面的按钮打开终端——OpenCode 或 pi 会直接可用。\n\n" +
                    "API key 走环境变量：打开终端后把 key 发给 agent，它会帮你写进 ~/.drydock/env.sh；" +
                    "锁屏挂机不中断、产物在手机文件管理器可见。",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(
            enabled = busy.isBlank(),
            onClick = {
                scope.launch {
                    val appCtx = context.applicationContext
                    try {
                        if (!RootfsManager.isDeployed(appCtx)) {
                            busy = "部署 Linux 环境（首次约 1 分钟）…"
                            withContext(Dispatchers.IO) { RootfsManager.deploy(appCtx) { } }
                        }
                        busy = "准备终端层…"
                        withContext(Dispatchers.IO) { TerminalManager.ensureTerminalLayer(appCtx) }
                        busy = "启动会话…"
                        // main 插队：new_session 通道先建目标再 ensureAll 其余（串行 ~10-15s/会话）
                        val si = Intent(appCtx, EnvService::class.java).putExtra("new_session", TerminalManager.MAIN)
                        appCtx.startForegroundService(si)
                        val ready = awaitSessionReady(appCtx, TerminalManager.MAIN)
                        busy = ""
                        if (ready != null) context.startActivity(Intent(context, TerminalActivity::class.java))
                        else busy = "会话启动失败（设置 → 开发者工具看日志）"
                    } catch (e: Exception) {
                        busy = ""
                    }
                }
            },
        ) { Text(if (busy.isBlank()) "打开终端（agent 在这里）" else busy) }

        sessions.forEach { s ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(if (s.name == TerminalManager.MAIN) "主终端" else s.name, style = MaterialTheme.typography.titleMedium)
                        Text("本地端口 :${s.port}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row {
                        TextButton(onClick = {
                            scope.launch {
                                val appCtx = context.applicationContext
                                // 全进程死亡后（重启/force-stop/pm install）holder/ttyd 不在；
                                // spawn 必须走 :env 进程组（app 进程直起的子进程会被 AMS 清剿，D18）
                                busy = "接回会话 ${s.name}…"
                                try {
                                    val i = Intent(appCtx, EnvService::class.java).putExtra("new_session", s.name)
                                    appCtx.startForegroundService(i)
                                    val ready = awaitSessionReady(appCtx, s.name)
                                    busy = ""
                                    if (ready != null) {
                                        context.startActivity(
                                            Intent(context, TerminalActivity::class.java).putExtra("session", s.name))
                                    } else busy = "会话接回失败（设置 → 开发者工具看日志）"
                                } catch (e: Exception) {
                                    busy = ""
                                }
                            }
                        }) { Text("打开") }
                        TextButton(onClick = { closeTarget = s }) { Text("关闭") }
                    }
                }
            }
        }

        closeTarget?.let { t ->
            AlertDialog(
                onDismissRequest = { closeTarget = null },
                title = { Text("关闭会话 ${t.name}？") },
                text = {
                    Text(
                        "dtach 会话无服务进程：关闭后该会话的内容（含 agent TUI）丢失，" +
                            "重新打开会是全新 shell。" +
                            (if (t.name == TerminalManager.MAIN) "main 关闭后会在下次「打开终端」时自动重建。" else ""),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        closeTarget = null
                        scope.launch {
                            val appCtx = context.applicationContext
                            busy = "关闭会话 ${t.name}…"
                            try {
                                appCtx.startForegroundService(
                                    Intent(appCtx, EnvService::class.java).putExtra("stop_session", t.name))
                                // 就绪判据：注册表文件里该会话消失（stop 在 :env 进程执行）
                                var closed = false
                                repeat(15) {
                                    if (it > 0) delay(1000)
                                    if (TerminalManager.readSessions(appCtx).none { s2 -> s2.name == t.name }) {
                                        closed = true; return@repeat
                                    }
                                }
                                busy = ""
                                if (!closed) busy = "关闭失败（设置 → 开发者工具看日志）"
                                tick++
                            } catch (e: Exception) {
                                busy = ""
                            }
                        }
                    }) { Text("关闭") }
                },
                dismissButton = { TextButton(onClick = { closeTarget = null }) { Text("取消") } },
            )
        }

        if (sessions.isNotEmpty()) {
            // 新建会话直建（D29 后无密钥选择；key 统一走环境变量，会话内生效）
            OutlinedButton(enabled = busy.isBlank(), onClick = {
                val name = TerminalManager.newSessionName(context)
                scope.launch {
                    busy = "新建会话 $name…"
                    try {
                        context.startForegroundService(
                            Intent(context, EnvService::class.java).putExtra("new_session", name))
                        var found = false
                        repeat(60) {
                            if (it > 0) delay(1000)
                            if (TerminalManager.readSessions(context).any { s2 -> s2.name == name }) { found = true; return@repeat }
                        }
                        busy = ""
                        tick++
                        if (found) context.startActivity(Intent(context, TerminalActivity::class.java).putExtra("session", name))
                    } catch (e: Exception) {
                        busy = ""
                    }
                }
            }) { Text("新建会话") }
            TextButton(onClick = { tick++ }) { Text("刷新") }
        }
    }
}

/** 文件页：Linux 目录树浏览（有界，D7：管理甩给系统）。文件经 WorkspaceProvider 打开。 */
@Composable
private fun FilePane() {
    val context = LocalContext.current
    val root = remember { File(RootfsManager.rootfsDir(context), "root") }
    var relPath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    val dir = File(root, relPath)

    androidx.compose.runtime.LaunchedEffect(relPath) {
        entries = withContext(Dispatchers.IO) {
            dir.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }) ?: emptyList()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("文件", style = MaterialTheme.typography.titleLarge)
        Text(
            "环境内路径 ${if (relPath.isBlank()) "/" else relPath}",
            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "这些文件同时出现在手机自带文件管理器（Drydock workspace）；点文件用系统应用打开。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (relPath.isNotBlank()) {
            TextButton(onClick = { relPath = relPath.substringBeforeLast('/') }) { Text("← 上一级") }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        if (!RootfsManager.isDeployed(context)) {
            Text("环境未部署——先在「设置 → 初始设置」完成配置。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn {
                items(entries) { f ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (f.isDirectory) relPath = "$relPath/${f.name}"
                                else openExternal(context, root, f)
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    ) {
                        Text(if (f.isDirectory) "📁" else "📄", modifier = Modifier.padding(end = 10.dp))
                        Column {
                            Text(f.name, fontSize = 14.sp)
                            if (!f.isDirectory) {
                                Text("${f.length() / 1024} KB", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 经 DocumentsProvider 的 content URI 甩系统应用打开（D7：编辑/查看交给外部 App）。 */
private fun openExternal(context: Context, root: File, f: File) {
    val docId = f.absolutePath.removePrefix(root.absolutePath).ifBlank { "/" }
    val uri = android.provider.DocumentsContract.buildDocumentUri(WorkspaceProvider.AUTHORITY, docId)
    val mime = android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (_: Exception) {
        android.widget.Toast.makeText(context, "没有应用能打开 ${f.name}（可先在系统文件管理器里试）", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** 设置页：镜像源（= ~/.drydock/mirrors 的 GUI 编辑器）、外观、初始设置、开发者工具。 */
@Composable
private fun SettingsPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }

    data class MirrorOpt(val id: String, val label: String, val aptUrl: String?, val npmUrl: String?)

    val aptOpts = listOf(
        MirrorOpt("default", "默认（国内镜像 + 官方自动回退）", null, null),
        MirrorOpt("tuna", "清华 TUNA", "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports", null),
        MirrorOpt("ustc", "中科大 USTC", "http://mirrors.ustc.edu.cn/ubuntu-ports", null),
        MirrorOpt("nju", "南京大学 NJU", "http://mirror.nju.edu.cn/ubuntu-ports", null),
        MirrorOpt("official", "官方源（海外网络）", "http://ports.ubuntu.com/ubuntu-ports", null),
    )
    val npmOpts = listOf(
        MirrorOpt("npmmirror", "npmmirror（默认，国内）", null, "https://registry.npmmirror.com"),
        MirrorOpt("npmjs", "npm 官方源", null, "https://registry.npmjs.org"),
    )

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge)

        Text("Coding 端点", style = MaterialTheme.typography.titleMedium)
        Text(
            "自定义端点列表（写进 opencode/pi 的配置文件；内置目录厂商不需要在这——" +
                "往 ~/.drydock/env.sh 放标准变量名即自动识别）",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 已添加端点列表（D30：可见、可删）
        val endpoints = remember(tick) { EndpointStore.all(context) }
        if (endpoints.isEmpty()) {
            Text("（暂无自定义端点）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        endpoints.forEach { e ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${e.providerId} · ${e.model}", fontSize = 13.sp)
                    Text(
                        "${e.protocol.label} · ${e.baseUrl} · key=\${e.envVar}",
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = {
                    EndpointStore.remove(context, e.providerId)
                    scope.launch(Dispatchers.IO) {
                        runCatching { RecipeManager.applyEndpointConfig(context.applicationContext) }
                        tick++
                    }
                }) { Text("删除") }
            }
        }
        // 追加表单（折叠式）
        var showForm by remember { mutableStateOf(false) }
        if (!showForm) {
            OutlinedButton(onClick = { showForm = true }) { Text("添加自定义端点") }
        } else {
            var fProtocol by remember { mutableStateOf(EndpointStore.Protocol.CHAT_COMPLETIONS) }
            var fBaseUrl by remember { mutableStateOf("") }
            var fModel by remember { mutableStateOf("") }
            var fContext by remember { mutableStateOf("") }
            var fEnvVar by remember { mutableStateOf("") }
            var fProvider by remember { mutableStateOf("") }
            val fOk = fBaseUrl.startsWith("http://") || fBaseUrl.startsWith("https://")
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EndpointStore.Protocol.entries.forEach { pr ->
                    Row(modifier = Modifier.fillMaxWidth().clickable { fProtocol = pr }) {
                        RadioButton(selected = fProtocol == pr, onClick = { fProtocol = pr })
                        Text(pr.label, modifier = Modifier.padding(top = 12.dp))
                    }
                }
                OutlinedTextField(value = fBaseUrl, onValueChange = { fBaseUrl = it },
                    label = { Text("Base URL") }, singleLine = true, isError = fBaseUrl.isNotBlank() && !fOk,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = fModel, onValueChange = { fModel = it },
                    label = { Text("模型 ID（端点实际服务的名字）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = fContext, onValueChange = { fContext = it.filter { c -> c.isDigit() } },
                        label = { Text("上下文（可选，如 1048576）") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = fEnvVar, onValueChange = { fEnvVar = it.filter { c -> c.isLetterOrDigit() || c == '_' }.uppercase() },
                        label = { Text("Key 变量名") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(value = fProvider, onValueChange = { fProvider = it.filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' } },
                    label = { Text("Provider 名（可留空自动生成）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = fOk, onClick = {
                        EndpointStore.add(context, fProtocol, fBaseUrl, fModel,
                            fContext.trim().takeIf { it.isNotBlank() }?.toLongOrNull(), fEnvVar, fProvider)
                        showForm = false
                        scope.launch(Dispatchers.IO) {
                            runCatching { RecipeManager.applyEndpointConfig(context.applicationContext) }
                            tick++
                        }
                    }) { Text("保存并写入配置") }
                    OutlinedButton(onClick = { showForm = false }) { Text("取消") }
                }
                Text(
                    "保存后新会话生效；别忘往 ~/.drydock/env.sh 放上 key（变量名用上面填的名字）。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(onClick = { context.startActivity(Intent(context, WizardActivity::class.java)) }) {
            Text(if (EndpointStore.wizardDone(context)) "重新运行初始设置" else "初始设置（保活 / 端点 / agent）")
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("环境变量（~/.drydock/env.sh）", style = MaterialTheme.typography.titleMedium)
        Text(
            "每个新会话生效。key 写成 export DRYDOCK_API_KEY=…（opencode/pi 的配置已引用它），" +
                "其他工具要的变量（代理、各家 key）也放这里；复杂改动也可以直接让 agent 帮你改。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (RootfsManager.isDeployed(context)) {
            // 不挂 tick：5s 注册表轮询会把编辑中的内容重置回文件（打字被清），只在进入时读一次
            var envText by remember {
                mutableStateOf(
                    runCatching { RootfsManager.envShFile(context).readText() }.getOrElse { "" },
                )
            }
            var envSaving by remember { mutableStateOf(false) }
            var envMsg by remember { mutableStateOf("") }
            OutlinedTextField(
                value = envText,
                onValueChange = { envText = it },
                label = { Text("env.sh（bash 语法，逐行 export）") },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Button(
                    enabled = !envSaving,
                    onClick = {
                        envSaving = true; envMsg = ""
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching { RootfsManager.envShFile(context).writeText(envText) }
                            }
                            envSaving = false
                            envMsg = r.fold({ "✓ 已保存" }, { "✗ 保存失败：${it.message}" })
                            tick++
                        }
                    },
                ) { Text(if (envSaving) "保存中…" else "保存") }
                if (envMsg.isNotBlank()) {
                    Text(envMsg, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Text(
                "保存后新会话生效；已开着的终端输入 . ~/.drydock/env.sh 立即生效。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("（部署 Linux 环境后可编辑）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("环境与备份", style = MaterialTheme.typography.titleMedium)
        var exporting by remember { mutableStateOf(false) }
        var exportMsg by remember { mutableStateOf("") }
        Button(
            enabled = !exporting && RootfsManager.isDeployed(context),
            onClick = {
                exporting = true; exportMsg = ""
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching { RootfsManager.exportEnvTar(context.applicationContext) }
                    }
                    exporting = false
                    exportMsg = r.fold({ "✓ 已导出到 Downloads/Drydock（$it）" }, { "✗ 导出失败：${it.message}" })
                }
            },
        ) { Text(if (exporting) "导出中…（约 1 分钟）" else "导出工作区与配置（tar.gz）") }
        if (exportMsg.isNotBlank()) Text(exportMsg, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(
            "导出 /root 工作区与 drydock 配置（系统层按配方版本可重放，不进导出）；" +
                "含 ~/.drydock/env.sh——你写入的环境变量（含自行存放的 key）会进导出包。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("高级：目录直通绑定（实验）", style = MaterialTheme.typography.titleMedium)
        Text(
            "把手机 Download 目录绑进环境 ${BindStore.ENV_DIR}（proot -b，双向直通）。" +
                "需要系统「所有文件访问」权限；绑定目录读写都经 proot 翻译，比环境内慢。默认关闭。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val bindOn = remember(tick) { BindStore.enabled(context) }
        val permOk = remember(tick) { android.os.Environment.isExternalStorageManager() }
        Text(
            if (permOk) "✓ 已获「所有文件访问」授权" else "未授权（开启前需在系统设置里本人授予）",
            fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            color = if (permOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            androidx.compose.material3.Switch(
                checked = bindOn,
                onCheckedChange = { on ->
                    if (on && !permOk) {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    android.net.Uri.parse("package:dev.drydock.prototype"),
                                ),
                            )
                        }
                    } else {
                        BindStore.setEnabled(context, on); tick++
                    }
                },
            )
            Text(
                if (bindOn) "已开启（新建会话生效）" else "已关闭",
                modifier = Modifier.padding(top = 14.dp), fontSize = 13.sp,
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("镜像源", style = MaterialTheme.typography.titleMedium)
        Text("仅影响安装下载速度；也可手编 ~/.drydock/mirrors 或让 agent 改，三者等价。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var aptChoice by remember { mutableStateOf(aptOpts.first()) }
        var npmChoice by remember { mutableStateOf(npmOpts.first()) }
        var mirrorMsg by remember { mutableStateOf("") }
        aptOpts.forEach { o ->
            Row(modifier = Modifier.fillMaxWidth().clickable { aptChoice = o }) {
                RadioButton(selected = aptChoice == o, onClick = { aptChoice = o })
                Text(o.label, modifier = Modifier.padding(top = 12.dp), fontSize = 13.sp)
            }
        }
        npmOpts.forEach { o ->
            Row(modifier = Modifier.fillMaxWidth().clickable { npmChoice = o }) {
                RadioButton(selected = npmChoice == o, onClick = { npmChoice = o })
                Text(o.label, modifier = Modifier.padding(top = 12.dp), fontSize = 13.sp)
            }
        }
        Button(enabled = mirrorMsg.isBlank() && RootfsManager.isDeployed(context), onClick = {
            mirrorMsg = "应用中…"
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    RecipeManager.applyMirrors(context.applicationContext, aptChoice.id, npmChoice.npmUrl)
                }
                mirrorMsg = if (r.output.contains("MIRROR_RC=0")) "✓ 已生效（新安装走新源）" else "✗ ${r.output.takeLast(200)}"
            }
        }) { Text(if (mirrorMsg.isBlank()) "应用镜像设置" else mirrorMsg) }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("外观", style = MaterialTheme.typography.titleMedium)
        Row {
            listOf(
                ThemeStore.Mode.SYSTEM to "跟随系统",
                ThemeStore.Mode.LIGHT to "浅色",
                ThemeStore.Mode.DARK to "深色",
            ).forEach { (m, label) ->
                // recreate() 让 DrydockTheme 重读偏好（组合期只读一次，否则切换不生效）
                TextButton(onClick = { ThemeStore.save(context, m); (context as? android.app.Activity)?.recreate() }) { Text(label) }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("开发者工具", style = MaterialTheme.typography.titleMedium)
        Text(
            "debug 验收通道随本 Activity（am start --es：drydock_endpoint/recipe/exec64/export/provider_test/rescue）。" +
                "救援通道见桌面入口；原型验收仪器（部署/AV1/AV2/AV3 手动页）随 MainActivity 一并移除。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text(
            "Drydock 原型 · 从 main tag 构建（git 纪律）\n环境 Ubuntu ${RootfsManifest.UBUNTU_VERSION} · 配方 ${RecipeManager.installedIds(context).joinToString("、").ifBlank { "未安装" }}" +
                "\n⚠ 卸载或清除应用数据会连同 Linux 环境一起删除——删除前先用上面的导出备份。",
            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
