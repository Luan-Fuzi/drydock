package dev.drydock.prototype

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
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

/** 外观偏好（D27）：跟随系统 / 浅色 / 深色。组合状态驱动——切换写 mode 即时
 *  重组全树（原 recreate() 实现会重建 Activity，底栏 tab 随 remember 丢失，
 *  点外观跳回会话页）；落盘只为下次冷启恢复。 */
object ThemeStore {
    enum class Mode { SYSTEM, LIGHT, DARK }
    val mode = mutableStateOf(Mode.SYSTEM)

    fun init(context: Context) {
        mode.value = context.getSharedPreferences("drydock", Context.MODE_PRIVATE)
            .getString("theme_mode", null)?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.SYSTEM
    }

    fun save(context: Context, m: Mode) {
        context.getSharedPreferences("drydock", Context.MODE_PRIVATE)
            .edit().putString("theme_mode", m.name).apply()
        mode.value = m
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

/** 全 app 主题入口：按 ThemeStore 切换（会话/文件/设置三栏与向导页生效，向导
 *  2026-10-07 起跟随主题不再锁深色；救援页维持深色）。 */
@Composable
fun DrydockTheme(content: @Composable () -> Unit) {
    val dark = when (ThemeStore.mode.value) {
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
    // saveable：旋转/重建后停在原 tab（主题等触发 recreate 的场景不再跳页）
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
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
    var renameTarget by remember { mutableStateOf<TerminalManager.Session?>(null) }
    var showNewDialog by remember { mutableStateOf(false) }
    val sessions = remember(tick) { TerminalManager.readSessions(context) }
    val displayNames = remember(tick) { SessionNames.load(context) }

    fun displayName(name: String): String =
        displayNames[name] ?: if (name == TerminalManager.MAIN) "主终端" else name

    // 会话列表保鲜：回主页/停留期间 5s 轮询注册表（修「回来不刷新」）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            tick++
        }
    }

    // 打开（或接回）会话：卡片即入口。全进程死亡后 holder/ttyd 不在，spawn 必须走
    // :env 进程组（app 进程直起的子进程会被 AMS 清剿，D18）；就绪判据见 awaitSessionReady
    fun openSession(name: String) {
        if (busy.isNotBlank()) return
        scope.launch {
            val appCtx = context.applicationContext
            busy = "接回会话 ${displayName(name)}…"
            try {
                val i = Intent(appCtx, EnvService::class.java).putExtra("new_session", name)
                appCtx.startForegroundService(i)
                val ready = awaitSessionReady(appCtx, name)
                busy = ""
                if (ready != null) {
                    context.startActivity(
                        Intent(context, TerminalActivity::class.java).putExtra("session", name))
                } else busy = "会话接回失败（设置 → 开发者工具看日志）"
            } catch (e: Exception) {
                busy = ""
            }
        }
    }

    // 新建会话（唯一入口；注册表空 = 建主终端）。首启部署与终端层安装（原
    // 「打开终端」大按钮职责）一并承担：新用户从这里一步进终端
    fun createSession(display: String) {
        if (busy.isNotBlank()) return
        scope.launch {
            val appCtx = context.applicationContext
            try {
                val tech = if (sessions.isEmpty()) TerminalManager.MAIN else TerminalManager.newSessionName(appCtx)
                if (display.isNotBlank()) SessionNames.set(appCtx, tech, display)
                if (!RootfsManager.isDeployed(appCtx)) {
                    busy = "部署 Linux 环境（首次约 1 分钟）…"
                    withContext(Dispatchers.IO) { RootfsManager.deploy(appCtx) { } }
                }
                busy = "准备终端层…"
                withContext(Dispatchers.IO) { TerminalManager.ensureTerminalLayer(appCtx) }
                busy = "启动会话…"
                context.startForegroundService(
                    Intent(appCtx, EnvService::class.java).putExtra("new_session", tech))
                val ready = awaitSessionReady(appCtx, tech)
                busy = ""
                tick++
                if (ready != null) {
                    context.startActivity(
                        Intent(context, TerminalActivity::class.java).putExtra("session", tech))
                } else busy = "会话启动失败（设置 → 开发者工具看日志）"
            } catch (e: Exception) {
                busy = ""
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("会话", style = MaterialTheme.typography.titleLarge)

        if (busy.isNotBlank()) {
            Text(busy, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }

        if (sessions.isEmpty()) {
            Text(
                "Drydock 让 coding agent 在手机上常驻干活。\n\n" +
                    "第一次使用：先到「设置 → 初始设置向导」完成配置（保活、连接大模型、安装 agent），"
                    + "然后点「新建会话」进入终端——OpenCode 或 pi 会直接可用。\n\n" +
                    "API key 走环境变量：进入终端后把 key 发给 agent，它会帮你写进 ~/.drydock/env.sh；" +
                    "锁屏挂机不中断、产物在手机文件管理器可见。",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(enabled = busy.isBlank(), onClick = { showNewDialog = true }) { Text("新建会话") }

        sessions.forEach { s ->
            Card(modifier = Modifier.fillMaxWidth().clickable { openSession(s.name) }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(displayName(s.name), style = MaterialTheme.typography.titleMedium)
                        Text(
                            (if (displayName(s.name) != s.name) "${s.name} · " else "") +
                                "本地端口 :${s.port}",
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(enabled = busy.isBlank(), onClick = { renameTarget = s }) { Text("改名") }
                    TextButton(enabled = busy.isBlank(), onClick = { closeTarget = s }) { Text("关闭") }
                }
            }
        }

        // 新建：默认名可改（display 层，SessionNames 落盘；technical 名照旧自动分配）
        if (showNewDialog) {
            var nameInput by remember(showNewDialog) {
                mutableStateOf(if (sessions.isEmpty()) "主终端" else "会话 ${sessions.size + 1}")
            }
            AlertDialog(
                onDismissRequest = { showNewDialog = false },
                title = { Text("新建会话") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "每个会话是独立的终端，互不影响、可同时跑不同任务。",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            label = { Text("会话名称") },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    TextButton(enabled = nameInput.isNotBlank() && busy.isBlank(), onClick = {
                        showNewDialog = false
                        createSession(nameInput)
                    }) { Text("创建") }
                },
                dismissButton = { TextButton(onClick = { showNewDialog = false }) { Text("取消") } },
            )
        }

        renameTarget?.let { t ->
            var nameInput by remember(t.name) { mutableStateOf(displayName(t.name)) }
            AlertDialog(
                onDismissRequest = { renameTarget = null },
                title = { Text("重命名会话") },
                text = {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("会话名称") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(enabled = nameInput.isNotBlank(), onClick = {
                        SessionNames.set(context, t.name, nameInput)
                        renameTarget = null
                        tick++
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
            )
        }

        closeTarget?.let { t ->
            AlertDialog(
                onDismissRequest = { closeTarget = null },
                title = { Text("关闭会话「${displayName(t.name)}」？") },
                text = {
                    Text(
                        "dtach 会话无服务进程：关闭后该会话的内容（含 agent TUI）丢失，" +
                            "重新打开会是全新 shell。" +
                            (if (t.name == TerminalManager.MAIN) "全部会话都关闭后，下次打开会自动重建主终端。" else ""),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        closeTarget = null
                        scope.launch {
                            val appCtx = context.applicationContext
                            busy = "关闭会话 ${displayName(t.name)}…"
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

/** 设置页（2026-10-07 重构）：主页面 = 分组卡片 + 一行一入口 + 当前值摘要，各区块的
 *  表单/编辑器/单选收进二级页（页内导航，系统返回键回主页面）；验收通道与诊断收进
 *  「开发者选项」。动机：旧版八区块平铺一页（摊大饼）、说明 11sp 正文 13sp 主次不清。 */
private enum class SettingsPage {
    ROOT, ENDPOINTS, ENV_SH, MIRRORS, BACKUP, BIND, APPEARANCE, TERMINAL, DEV
}

@Composable
private fun SettingsPane() {
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(SettingsPage.ROOT) }
    BackHandler(enabled = page != SettingsPage.ROOT) { page = SettingsPage.ROOT }
    when (page) {
        SettingsPage.ROOT -> SettingsRoot { page = it }
        SettingsPage.ENDPOINTS -> EndpointSettingsPage { page = SettingsPage.ROOT }
        SettingsPage.ENV_SH -> EnvShSettingsPage { page = SettingsPage.ROOT }
        SettingsPage.MIRRORS -> MirrorSettingsPage { page = SettingsPage.ROOT }
        SettingsPage.BACKUP -> BackupSettingsPage { page = SettingsPage.ROOT }
        SettingsPage.BIND -> BindSettingsPage { page = SettingsPage.ROOT }
        SettingsPage.APPEARANCE -> AppearanceSettingsPage { page = SettingsPage.ROOT }
        SettingsPage.TERMINAL -> TerminalSettingsPage { page = SettingsPage.ROOT }
        SettingsPage.DEV -> DevSettingsPage { page = SettingsPage.ROOT }
    }
}

/** 设置行：标题 + 当前值摘要 + chevron；divider = 组内非末行画分隔线。 */
@Composable
private fun SettingsRow(title: String, summary: String, divider: Boolean = true, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (summary.isNotBlank()) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (divider) HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** 分组卡片：组标签（primary 色）+ Card 容器。 */
@Composable
private fun SettingsGroup(label: String, content: @Composable () -> Unit) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
        )
        Card(Modifier.fillMaxWidth()) { Column { content() } }
    }
}

/** 二级页骨架：「‹ 设置」返回 + 页标题 + 内容（整页可滚）。 */
@Composable
private fun SettingsSubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            TextButton(
                onClick = onBack,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            ) { Text("‹ 设置") }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        content()
    }
}

/** 设置主页面：摘要当前值只在本组合读（when 分支切换销毁重建，返回时自然重读）。 */
@Composable
private fun SettingsRoot(onOpen: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val endpoints = remember { EndpointStore.all(context) }
    val wizardDone = remember { EndpointStore.wizardDone(context) }
    val mirrorSummary = remember { mirrorSummaryOf(context) }
    val bindOn = remember { BindStore.enabled(context) }
    val themeLabel = when (ThemeStore.mode.value) {
        ThemeStore.Mode.SYSTEM -> "跟随系统"
        ThemeStore.Mode.LIGHT -> "浅色"
        ThemeStore.Mode.DARK -> "深色"
    }
    val termSummary = "字号 ${TermPrefs.fontSize(context)} · 回滚 ${TermPrefs.scrollback(context)} 行"

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge)

        SettingsGroup("模型与端点") {
            SettingsRow(
                "Coding 端点",
                if (endpoints.isEmpty()) "未添加（内置厂商自动识别）" else "${endpoints.size} 个自定义端点",
            ) { onOpen(SettingsPage.ENDPOINTS) }
            SettingsRow(
                "初始设置向导",
                if (wizardDone) "已完成 · 可重新运行" else "保活 / 连接大模型 / 安装 agent",
                divider = false,
            ) { context.startActivity(Intent(context, WizardActivity::class.java)) }
        }

        SettingsGroup("环境") {
            SettingsRow("环境变量", "~/.drydock/env.sh（新会话生效）") { onOpen(SettingsPage.ENV_SH) }
            SettingsRow("镜像源", mirrorSummary) { onOpen(SettingsPage.MIRRORS) }
            SettingsRow("备份与导出", "工作区与配置 → Downloads/Drydock") { onOpen(SettingsPage.BACKUP) }
            SettingsRow(
                "目录直通绑定",
                "实验 · " + if (bindOn) "已开启" else "已关闭",
                divider = false,
            ) { onOpen(SettingsPage.BIND) }
        }

        SettingsGroup("应用") {
            SettingsRow("外观", themeLabel) { onOpen(SettingsPage.APPEARANCE) }
            SettingsRow("终端", termSummary, divider = false) { onOpen(SettingsPage.TERMINAL) }
        }

        SettingsGroup("更多") {
            SettingsRow("开发者选项", "验收通道、时间线导出与版本详情", divider = false) { onOpen(SettingsPage.DEV) }
        }

        Text(
            "Drydock 原型（从 main tag 构建）· Ubuntu ${RootfsManifest.UBUNTU_VERSION}\n" +
                "⚠ 卸载或清除应用数据会连同 Linux 环境一起删除——删除前先用「备份与导出」备份。",
            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** ROOT 行摘要：读 ~/.drydock/mirrors 当前生效值 → 短名（GUI、手编、agent 改三者同源）。 */
private fun mirrorSummaryOf(context: Context): String {
    val txt = runCatching {
        File(RootfsManager.rootfsDir(context), "root/.drydock/mirrors").takeIf { it.exists() }?.readText()
    }.getOrNull().orEmpty()
    val apt = Regex("DRYDOCK_APT_MIRROR=(\\S+)").find(txt)?.groupValues?.get(1)?.let(::mirrorHostOf)
    val npm = Regex("DRYDOCK_NPM_REGISTRY=(\\S+)").find(txt)?.groupValues?.get(1)?.let(::mirrorHostOf)
    if (apt == null && npm == null) return "默认（国内镜像 + 官方回退）"
    return listOfNotNull(apt?.let { "APT $it" }, npm?.let { "npm $it" }).joinToString(" · ")
}

private fun mirrorHostOf(url: String): String = when {
    url.contains("tuna") -> "清华 TUNA"
    url.contains("ustc") -> "中科大"
    url.contains("nju") -> "南大"
    url.contains("ports.ubuntu.com") -> "官方源"
    url.contains("npmmirror") -> "npmmirror"
    url.contains("registry.npmjs.org") -> "npm 官方"
    else -> "自定义"
}

/** Coding 端点二级页（D30）：列表可见可删 + 折叠式追加表单。 */
@Composable
private fun EndpointSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }

    SettingsSubPage("Coding 端点", onBack) {
        Text(
            "自定义端点列表（写进 opencode/pi 的配置文件）。内置目录厂商不需要在这——" +
                "往 ~/.drydock/env.sh 放标准变量名即自动识别（GLM Coding Plan 用 ZHIPU_API_KEY）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val endpoints = remember(tick) { EndpointStore.all(context) }
        if (endpoints.isEmpty()) {
            Text("（暂无自定义端点）", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        endpoints.forEach { e ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${e.providerId} · ${e.model}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${e.protocol.label} · ${e.baseUrl} · key=\${e.envVar}",
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
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
            var fOutput by remember { mutableStateOf("") }
            var fEnvVar by remember { mutableStateOf("") }
            var fProvider by remember { mutableStateOf("") }
            val fOk = fBaseUrl.startsWith("http://") || fBaseUrl.startsWith("https://")
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "协议（agent 与服务对话用的报文格式，选错会连不上）",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                )
                EndpointStore.Protocol.entries.forEach { pr ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { fProtocol = pr },
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = fProtocol == pr, onClick = { fProtocol = pr })
                        Column {
                            Text(pr.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                pr.hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                OutlinedTextField(value = fBaseUrl, onValueChange = { fBaseUrl = it },
                    label = { Text("Base URL") }, singleLine = true, isError = fBaseUrl.isNotBlank() && !fOk,
                    supportingText = { Text("服务的接口根地址，从服务商文档获取；一般以 /v1、/v4 之类结尾，不含 /chat/completions") },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = fModel, onValueChange = { fModel = it },
                    label = { Text("模型 ID") }, singleLine = true,
                    supportingText = { Text("服务实际提供的模型名，照文档填（如 glm-5.3-flash、deepseek-chat）") },
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = fContext, onValueChange = { fContext = it.filter { c -> c.isDigit() } },
                        label = { Text("上下文窗口") }, singleLine = true,
                        supportingText = { Text("可选 · token 数") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = fOutput, onValueChange = { fOutput = it.filter { c -> c.isDigit() } },
                        label = { Text("最大输出长度") }, singleLine = true,
                        supportingText = { Text("可选 · token 数") }, modifier = Modifier.weight(1f))
                }
                Text(
                    "两个长度照服务商文档填：上下文窗口 = 模型一次能读进多少内容；最大输出长度 = 单次最多生成多少（留空用工具默认值）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(value = fEnvVar, onValueChange = { fEnvVar = it.filter { c -> c.isLetterOrDigit() || c == '_' }.uppercase() },
                    label = { Text("Key 变量名") }, singleLine = true,
                    supportingText = { Text("留空默认 DRYDOCK_API_KEY") },
                    modifier = Modifier.fillMaxWidth())
                var fKeyValue by remember { mutableStateOf("") }
                OutlinedTextField(value = fKeyValue, onValueChange = { fKeyValue = it },
                    label = { Text("API key 值") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text("保存后以「export 变量名=key值」写进 ~/.drydock/env.sh；留空则只写配置，key 稍后自己补") },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = fProvider, onValueChange = { fProvider = it.filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' } },
                    label = { Text("Provider 名") }, singleLine = true,
                    supportingText = { Text("这条端点在配置文件里的标识名：agent 里模型会显示为「provider名/模型名」；不影响连接，留空按域名自动生成，重复添加同名会覆盖更新") },
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = fOk, onClick = {
                        EndpointStore.add(context, fProtocol, fBaseUrl, fModel,
                            fContext.trim().takeIf { it.isNotBlank() }?.toLongOrNull(),
                            fOutput.trim().takeIf { it.isNotBlank() }?.toLongOrNull(),
                            fEnvVar, fProvider)
                        showForm = false
                        scope.launch(Dispatchers.IO) {
                            if (fKeyValue.isNotBlank()) {
                                val resolvedName = fEnvVar.trim().ifBlank { "DRYDOCK_API_KEY" }
                                runCatching { RootfsManager.upsertEnvExports(context.applicationContext, listOf(resolvedName to fKeyValue.trim())) }
                            }
                            runCatching { RecipeManager.applyEndpointConfig(context.applicationContext) }
                            tick++
                        }
                    }) { Text("保存并写入配置") }
                    OutlinedButton(onClick = { showForm = false }) { Text("取消") }
                }
                Text(
                    "保存后新会话生效；别忘往 ~/.drydock/env.sh 放上 key（变量名用上面填的名字）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(onClick = { context.startActivity(Intent(context, WizardActivity::class.java)) }) {
            Text(if (EndpointStore.wizardDone(context)) "重新运行初始设置向导" else "运行初始设置向导（保活 / 连接大模型 / 安装 agent）")
        }
    }
}

/** 环境变量二级页（D29）：env.sh 直接编辑。 */
@Composable
private fun EnvShSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    SettingsSubPage("环境变量（~/.drydock/env.sh）", onBack) {
        Text(
            "每个新会话生效。key 写成 export DRYDOCK_API_KEY=…（opencode/pi 的配置已引用它），" +
                "其他工具要的变量（代理、各家 key）也放这里；复杂改动也可以直接让 agent 帮你改。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (RootfsManager.isDeployed(context)) {
            // 进入二级页时读一次（不挂轮询：会话注册表 5s 轮询会把编辑中的内容重置回文件）
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
                        }
                    },
                ) { Text(if (envSaving) "保存中…" else "保存") }
                if (envMsg.isNotBlank()) {
                    Text(envMsg, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Text(
                "保存后新会话生效；已开着的终端输入 . ~/.drydock/env.sh 立即生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "（部署 Linux 环境后可编辑）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 备份与导出二级页（D28-1 口径：工作区与配置，非全环境）。 */
@Composable
private fun BackupSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var exportMsg by remember { mutableStateOf("") }

    SettingsSubPage("备份与导出", onBack) {
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
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 目录直通绑定二级页（D28-3 最小版）。「已关闭/已开启（…）」与「已获…」文案是
 *  night-b t10 的开关定位锚点，改动须同步剧本。 */
@Composable
private fun BindSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }

    SettingsSubPage("目录直通绑定（实验）", onBack) {
        Text(
            "把手机 Download 目录绑进环境 ${BindStore.ENV_DIR}（proot -b，双向直通）。" +
                "需要系统「所有文件访问」权限；绑定目录读写都经 proot 翻译，比环境内慢。默认关闭。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val bindOn = remember(tick) { BindStore.enabled(context) }
        val permOk = remember(tick) { android.os.Environment.isExternalStorageManager() }
        Text(
            if (permOk) "✓ 已获「所有文件访问」授权" else "未授权（开启前需在系统设置里本人授予）",
            fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            color = if (permOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(
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
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** 镜像源二级页（= ~/.drydock/mirrors 的 GUI 编辑器）。 */
@Composable
private fun MirrorSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }

    data class MirrorOpt(val id: String, val label: String, val aptUrl: String?, val npmUrl: String?)

    SettingsSubPage("镜像源", onBack) {
        Text(
            "仅影响安装下载速度；也可手编 ~/.drydock/mirrors 或让 agent 改，三者等价。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 单选回填当前生效值（读 ~/.drydock/mirrors——GUI、手编、agent 改三者同源）；
        // 列表外的手编源动态补一条，如实显示而非回落默认
        val mirrorTxt = remember(tick) {
            runCatching {
                File(RootfsManager.rootfsDir(context), "root/.drydock/mirrors")
                    .takeIf { it.exists() }?.readText()
            }.getOrNull().orEmpty()
        }
        val curApt = Regex("DRYDOCK_APT_MIRROR=(\\S+)").find(mirrorTxt)?.groupValues?.get(1)
        val curNpm = Regex("DRYDOCK_NPM_REGISTRY=(\\S+)").find(mirrorTxt)?.groupValues?.get(1)
        val aptOpts = remember(mirrorTxt) {
            buildList {
                add(MirrorOpt("default", "默认（国内镜像 + 官方自动回退）", null, null))
                add(MirrorOpt("tuna", "清华 TUNA", "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports", null))
                add(MirrorOpt("ustc", "中科大 USTC", "http://mirrors.ustc.edu.cn/ubuntu-ports", null))
                add(MirrorOpt("nju", "南京大学 NJU", "http://mirror.nju.edu.cn/ubuntu-ports", null))
                add(MirrorOpt("official", "官方源（海外网络）", "http://ports.ubuntu.com/ubuntu-ports", null))
                if (curApt != null && none { it.aptUrl == curApt }) {
                    add(MirrorOpt("custom-apt", "当前手编：$curApt", curApt, null))
                }
            }
        }
        val npmOpts = remember(mirrorTxt) {
            buildList {
                add(MirrorOpt("npmmirror", "npmmirror（国内，默认）", null, "https://registry.npmmirror.com"))
                add(MirrorOpt("npmjs", "npm 官方源（海外）", null, "https://registry.npmjs.org"))
                if (curNpm != null && none { it.npmUrl == curNpm }) {
                    add(MirrorOpt("custom-npm", "当前手编：$curNpm", null, curNpm))
                }
            }
        }
        var aptChoice by remember(mirrorTxt) {
            mutableStateOf(aptOpts.firstOrNull { it.aptUrl == curApt } ?: aptOpts.first())
        }
        var npmChoice by remember(mirrorTxt) {
            mutableStateOf(npmOpts.firstOrNull { it.npmUrl == curNpm } ?: npmOpts.first())
        }
        var mirrorApplying by remember { mutableStateOf(false) }
        var mirrorMsg by remember { mutableStateOf("") }
        Text("APT 源（系统包安装）", style = MaterialTheme.typography.titleSmall)
        aptOpts.forEach { o ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { aptChoice = o },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                RadioButton(selected = aptChoice == o, onClick = { aptChoice = o })
                Text(o.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text("npm 源（agent 运行时安装）", style = MaterialTheme.typography.titleSmall)
        npmOpts.forEach { o ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { npmChoice = o },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                RadioButton(selected = npmChoice == o, onClick = { npmChoice = o })
                Text(o.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        // 进度与结果独立成行：不挤进按钮文字（旧实现按钮被「应用中…/✓…」撑变形）
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Button(
                enabled = !mirrorApplying && RootfsManager.isDeployed(context),
                onClick = {
                    mirrorApplying = true; mirrorMsg = ""
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            RecipeManager.applyMirrors(context.applicationContext, aptChoice.id, npmChoice.npmUrl)
                        }
                        mirrorApplying = false
                        mirrorMsg = if (r.output.contains("MIRROR_RC=0")) "✓ 已生效（新安装走新源）" else "✗ ${r.output.takeLast(200)}"
                        tick++
                    }
                },
            ) { Text(if (mirrorApplying) "应用中…" else "应用镜像设置") }
            if (mirrorMsg.isNotBlank()) {
                Text(mirrorMsg, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 外观二级页：单选行（当前项加粗高亮语义沿用旧版）。 */
@Composable
private fun AppearanceSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current

    SettingsSubPage("外观", onBack) {
        Text(
            "深浅主题即时生效（会话 / 文件 / 设置三栏）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        listOf(
            ThemeStore.Mode.SYSTEM to "跟随系统",
            ThemeStore.Mode.LIGHT to "浅色",
            ThemeStore.Mode.DARK to "深色",
        ).forEach { (m, label) ->
            // 写 ThemeStore.mode 即时重组（DrydockTheme 观察该状态），不 recreate——
            // 旧实现重建 Activity 重置底栏 tab，点外观直接跳回会话页
            val active = ThemeStore.mode.value == m
            Row(
                modifier = Modifier.fillMaxWidth().clickable { ThemeStore.save(context, m) },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                RadioButton(selected = active, onClick = { ThemeStore.save(context, m) })
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (active) FontWeight.Bold else null,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** 终端二级页：字号/回滚行数落 TermPrefs；TerminalActivity onResume 经
 *  window.__dk.applyCfg 推给页面 xterm（含正在开的终端），ttyd 侧不感知。 */
@Composable
private fun TerminalSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current

    SettingsSubPage("终端", onBack) {
        var termFont by remember { mutableStateOf(TermPrefs.fontSize(context)) }
        var termRows by remember { mutableStateOf(TermPrefs.scrollback(context)) }
        Text("字号 ${termFont}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = termFont.toFloat(),
            onValueChange = { termFont = it.toInt().coerceIn(10, 24) },
            valueRange = 10f..24f,
            steps = 13,
            onValueChangeFinished = { TermPrefs.set(context, termFont, termRows) },
        )
        Text("回滚行数 ${termRows}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = termRows.toFloat(),
            onValueChange = { termRows = (it.toInt() / 100) * 100 },
            valueRange = 200f..10_000f,
            onValueChangeFinished = { TermPrefs.set(context, termFont, termRows) },
        )
        Text(
            "改动即保存，回到终端页生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 开发者选项二级页：验收通道说明、时间线导出（D21 口径：当前份 + .old 合并）、版本详情。 */
@Composable
private fun DevSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tlBusy by remember { mutableStateOf(false) }
    var tlMsg by remember { mutableStateOf("") }

    SettingsSubPage("开发者选项", onBack) {
        Text("验收通道", style = MaterialTheme.typography.titleMedium)
        Text(
            "debug 验收通道随本 Activity（am start --es：drydock_endpoint/recipe/exec64/export/provider_test/rescue）。" +
                "救援通道见桌面入口；原型验收仪器（部署/AV1/AV2/AV3 手动页）随 MainActivity 一并移除。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("诊断", style = MaterialTheme.typography.titleMedium)
        Button(
            enabled = !tlBusy && Timeline.file(context).exists(),
            onClick = {
                tlBusy = true; tlMsg = ""
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            val f = File(context.cacheDir, "drydock-timeline.jsonl")
                            f.writeText(Timeline.readAll(context).joinToString("\n") + "\n")
                            "${"%.0f".format(f.length() / 1000.0)} KB → ${Landing.toDownloads(context, f)}"
                        }
                    }
                    tlBusy = false
                    tlMsg = r.fold({ "✓ $it" }, { "✗ 导出失败：${it.message}" })
                }
            },
        ) { Text(if (tlBusy) "导出中…" else "导出时间线（timeline.jsonl → Downloads/Drydock）") }
        if (tlMsg.isNotBlank()) Text(tlMsg, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text("版本", style = MaterialTheme.typography.titleMedium)
        Text(
            "Drydock 原型 · 从 main tag 构建（git 纪律）\n环境 Ubuntu ${RootfsManifest.UBUNTU_VERSION} · 配方 ${RecipeManager.installedIds(context).joinToString("、").ifBlank { "未安装" }}",
            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
