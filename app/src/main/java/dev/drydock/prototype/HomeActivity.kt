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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
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
            intent?.getStringExtra("drydock_api_key")?.takeIf { it.isNotBlank() }?.let {
                SecretStore.save(this, AgentManager.KEY_NAME, it)
                SecretStore.save(this, EndpointStore.KEY_NAME, it)
            }
            intent?.getStringExtra("drydock_endpoint")?.takeIf { it.contains("|") }?.let { spec ->
                val p = spec.split("|")
                runCatching { EndpointStore.Protocol.valueOf(p[0]) }.getOrNull()?.let { proto ->
                    EndpointStore.save(this, proto, p[1], p.getOrElse(2) { "" }, p.getOrElse(3) { "" })
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
        }
        setContent {
            DrydockTheme { HomeScreen() }
        }
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
@Composable
private fun SessionPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf("") }
    val sessions = remember(tick) { TerminalManager.readSessions(context) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("终端会话", style = MaterialTheme.typography.titleLarge)

        if (sessions.isEmpty()) {
            Text(
                "Drydock 让 coding agent 在手机上常驻干活。\n\n" +
                    "第一次使用：先到「设置 → 初始设置」完成三步配置（保活、端点与密钥、安装 agent），" +
                    "然后点下面的按钮打开终端——OpenCode 或 pi 会直接可用。\n\n" +
                    "锁屏挂机不中断、密钥不落盘、产物在手机文件管理器可见。",
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
                        appCtx.startForegroundService(Intent(appCtx, EnvService::class.java))
                        var found = false
                        repeat(25) {
                            if (it > 0) delay(1000)
                            if (TerminalManager.readSessions(appCtx).any { it.name == TerminalManager.MAIN }) {
                                found = true; return@repeat
                            }
                        }
                        busy = ""
                        if (found) context.startActivity(Intent(context, TerminalActivity::class.java))
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
                    TextButton(onClick = {
                        context.startActivity(Intent(context, TerminalActivity::class.java).putExtra("session", s.name))
                    }) { Text("打开") }
                }
            }
        }

        if (sessions.isNotEmpty()) {
            OutlinedButton(enabled = busy.isBlank(), onClick = {
                val name = TerminalManager.newSessionName(context)
                scope.launch {
                    busy = "新建会话 $name…"
                    context.startForegroundService(Intent(context, EnvService::class.java).putExtra("new_session", name))
                    var found = false
                    repeat(25) {
                        if (it > 0) delay(1000)
                        if (TerminalManager.readSessions(context).any { it.name == name }) { found = true; return@repeat }
                    }
                    busy = ""
                    tick++
                    if (found) context.startActivity(Intent(context, TerminalActivity::class.java).putExtra("session", name))
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

        Text("端点与密钥", style = MaterialTheme.typography.titleMedium)
        Text(
            "${EndpointStore.summary(context)} · 密钥${if (EndpointStore.keyReady(context)) "已保管（Keystore）" else "未设置"}",
            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        )
        Button(onClick = { context.startActivity(Intent(context, WizardActivity::class.java)) }) {
            Text(if (EndpointStore.wizardDone(context)) "重新运行初始设置" else "初始设置（保活 / 端点 / agent）")
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
                TextButton(onClick = { ThemeStore.save(context, m); tick++ }) { Text(label) }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("开发者工具", style = MaterialTheme.typography.titleMedium)
        Text("原型验收仪器（部署、AV1/AV2/AV3、救援通道、缓存清理等）。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { context.startActivity(Intent(context, MainActivity::class.java)) }) { Text("打开开发者工具") }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text(
            "Drydock 原型 · 从 main tag 构建（git 纪律）\n环境 Ubuntu ${RootfsManifest.UBUNTU_VERSION} · 配方 ${RecipeManager.installedIds(context).joinToString("、").ifBlank { "未安装" }}",
            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
