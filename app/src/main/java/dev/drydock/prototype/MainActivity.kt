package dev.drydock.prototype

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Timeline.log(applicationContext, "ui_start")
        // L1 通知需要运行时授权（API 33+）；拒绝不阻塞原型功能，AVD 验收也可 pm grant
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4705)
        }
        // 验收自动化注入口：仅 debuggable 构建存在（release 无此路径），key 直达
        // Keystore 不落盘；无视觉环境下经 am start --es 注入后走 UI 断言。
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            intent?.getStringExtra("drydock_api_key")?.takeIf { it.isNotBlank() }?.let {
                SecretStore.save(this, AgentManager.KEY_NAME, it)
                android.util.Log.i(
                    "DrydockMain",
                    "debug 注入 API key：${SecretStore.mask(this, AgentManager.KEY_NAME)}",
                )
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PrototypeScreen()
                }
            }
        }
    }
}

@Composable
fun PrototypeScreen() {
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ProotSelfCheck.Result?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var deployState by remember { mutableStateOf<RootfsManager.DeployState>(RootfsManager.DeployState.Idle) }
    var deployed by remember { mutableStateOf(RootfsManager.isDeployed(context)) }
    var deployElapsedMs by remember { mutableStateOf(0L) }
    var av1ElapsedMs by remember { mutableStateOf(0L) }
    var av1Result by remember { mutableStateOf<RootfsManager.ExecResult?>(null) }
    var av1Running by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // edge-to-edge 下必须避让导航栏，否则列表末尾的按钮被手势条压住点不到
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Drydock 原型", style = MaterialTheme.typography.titleLarge)

        // ---------- 步骤 1：引擎自检 ----------
        Text("步骤 1 · proot 引擎自检", style = MaterialTheme.typography.titleMedium)
        Button(
            enabled = !running,
            onClick = {
                running = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        ProotSelfCheck.run(context.applicationContext)
                    }
                    result = r
                    running = false
                }
            },
        ) { Text(if (running) "运行中…" else "运行 proot 自检") }

        result?.let { r ->
            val pass = ProotSelfCheck.passed(r)
            Text(
                if (pass) "✓ 通过（exit=${r.exitCode}）" else "✗ 未通过（exit=${r.exitCode}）",
                color = if (pass) Color(0xFF4ADE80) else MaterialTheme.colorScheme.error,
            )
            MonoBox(r.output.ifBlank { "(无输出)" })
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))

        // ---------- 步骤 2：rootfs 部署与 AV1 ----------
        Text("步骤 2 · Ubuntu ${RootfsManifest.UBUNTU_VERSION} 环境部署（AV1）", style = MaterialTheme.typography.titleMedium)

        val stateText = when (val s = deployState) {
            is RootfsManager.DeployState.Idle -> if (deployed) "已就绪" else "未部署"
            is RootfsManager.DeployState.Downloading ->
                "下载中 ${s.percent}%（${s.mirror.substringAfter("//").substringBefore("/")}）"
            is RootfsManager.DeployState.Verifying -> "sha256 校验中…"
            is RootfsManager.DeployState.Extracting -> "解压完成"
            is RootfsManager.DeployState.Configuring -> "配置 apt 国内源与 DNS…"
            is RootfsManager.DeployState.Ready -> "✓ 部署完成（${deployElapsedMs / 1000} 秒）"
            is RootfsManager.DeployState.Failed -> "✗ ${s.reason}"
        }
        Text(stateText, fontFamily = FontFamily.Monospace, fontSize = 13.sp)

        val deploying = deployState.let { it is RootfsManager.DeployState.Downloading || it is RootfsManager.DeployState.Verifying || it is RootfsManager.DeployState.Extracting || it is RootfsManager.DeployState.Configuring }
        Button(
            enabled = !deploying && !deployed,
            onClick = {
                val t0 = System.nanoTime()
                deployState = RootfsManager.DeployState.Downloading("", 0)
                scope.launch {
                    withContext(Dispatchers.IO) {
                        RootfsManager.deploy(context.applicationContext) { st -> deployState = st }
                    }
                    deployElapsedMs = (System.nanoTime() - t0) / 1_000_000
                    deployed = RootfsManager.isDeployed(context)
                }
            },
        ) { Text(if (deployed) "已部署" else "下载并部署 rootfs（~29 MB）") }

        if (deployed) {
            Button(
                enabled = !av1Running,
                onClick = {
                    av1Running = true
                    av1Result = null
                    val t0 = System.nanoTime()
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            RootfsManager.runInEnv(
                                context.applicationContext,
                                AV1_COMMAND,
                            )
                        }
                        av1ElapsedMs = (System.nanoTime() - t0) / 1_000_000
                        av1Result = r
                        av1Running = false
                    }
                },
            ) { Text(if (av1Running) "AV1 运行中…" else "进入环境自检（AV1）") }

            av1Result?.let { r ->
                val pass = r.exitCode == 0 &&
                    r.output.contains("AV1_PROMPT_OK") &&
                    r.output.contains("APT_RC=0")
                Text(
                    if (pass) "✓ AV1 通过（${av1ElapsedMs / 1000} 秒）" else "✗ AV1 未通过（exit=${r.exitCode}，${av1ElapsedMs / 1000} 秒）",
                    color = if (pass) Color(0xFF4ADE80) else MaterialTheme.colorScheme.error,
                )
                MonoBox(r.output.ifBlank { "(无输出)" })
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))

        // ---------- 步骤 3：终端链路（AV2） ----------
        Text("步骤 3 · 终端链路（AV2）", style = MaterialTheme.typography.titleMedium)

        var layerState by remember { mutableStateOf("") }
        var layerReady by remember { mutableStateOf(false) }
        var layerRunning by remember { mutableStateOf(false) }

        Text(
            layerState.ifBlank { "终端层（ttyd + dtach）未安装" },
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
        Button(
            enabled = !layerRunning && deployed,
            onClick = {
                layerRunning = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        TerminalManager.ensureTerminalLayer(context.applicationContext)
                    }
                    layerReady = r.exitCode == 0 && r.output.contains("LAYER_RC=0")
                    layerState = if (layerReady) {
                        "✓ 终端层就绪"
                    } else {
                        "✗ exit=${r.exitCode}：${r.output.takeLast(300)}"
                    }
                    layerRunning = false
                }
            },
        ) { Text(if (layerRunning) "安装中（apt install ttyd dtach）…" else "安装终端层（ttyd + dtach）") }

        if (layerReady) {
            Button(onClick = {
                scope.launch {
                    // 会话由 :env 前台服务承载（主进程死不连累），UI 经注册表发现
                    context.startForegroundService(
                        android.content.Intent(context, EnvService::class.java),
                    )
                    var found = false
                    repeat(25) {
                        if (it > 0) kotlinx.coroutines.delay(1000)
                        if (TerminalManager.readSessions(context).any { s -> s.name == TerminalManager.MAIN }) {
                            found = true
                            return@repeat
                        }
                    }
                    if (!found) {
                        layerState = "✗ 会话启动失败（看 logcat DrydockEnv/DrydockTerminal）"
                    } else {
                        try {
                            android.util.Log.i("DrydockUI", "startActivity TerminalActivity…")
                            context.startActivity(
                                android.content.Intent(context, TerminalActivity::class.java),
                            )
                            android.util.Log.i("DrydockUI", "startActivity 已调用")
                        } catch (e: Exception) {
                            android.util.Log.e("DrydockUI", "startActivity 异常", e)
                        }
                    }
                }
            }) { Text("打开终端") }

            var newSessRunning by remember { mutableStateOf(false) }
            Button(
                enabled = !newSessRunning,
                onClick = {
                    newSessRunning = true
                    val name = TerminalManager.newSessionName(context)
                    scope.launch {
                        context.startForegroundService(
                            android.content.Intent(context, EnvService::class.java).apply {
                                putExtra("new_session", name)
                            },
                        )
                        var found = false
                        repeat(25) {
                            if (it > 0) kotlinx.coroutines.delay(1000)
                            if (TerminalManager.readSessions(context).any { s -> s.name == name }) {
                                found = true
                                return@repeat
                            }
                        }
                        if (found) {
                            context.startActivity(
                                android.content.Intent(context, TerminalActivity::class.java).apply {
                                    putExtra("session", name)
                                },
                            )
                        } else {
                            layerState = "✗ 新会话 $name 启动失败"
                        }
                        newSessRunning = false
                    }
                },
            ) { Text(if (newSessRunning) "创建中…" else "新建会话") }
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))

        // ---------- 步骤 4：agent 链路（AV3） ----------
        Text("步骤 4 · agent 链路（AV3）", style = MaterialTheme.typography.titleMedium)

        var apiKeyInput by remember { mutableStateOf("") }
        var keySavedAt by remember { mutableStateOf(0L) }
        val keyMask = remember(keySavedAt) { SecretStore.mask(context, AgentManager.KEY_NAME) }
        var keyMsg by remember { mutableStateOf("") }

        Text(
            "端点预设 GLM（Anthropic 兼容）\n${AgentManager.GLM_BASE_URL}",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        Text(
            if (keyMask != null) "API key：已保管（$keyMask，Keystore 加密）" else "API key：未设置",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it },
            label = { Text("GLM API key") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = apiKeyInput.isNotBlank(),
            onClick = {
                SecretStore.save(context, AgentManager.KEY_NAME, apiKeyInput)
                keySavedAt = System.currentTimeMillis()
                apiKeyInput = ""
                keyMsg = "已入 Keystore；重开终端会话后终端内 claude 生效"
            },
        ) { Text("保存密钥（只进 Keystore）") }
        if (keyMsg.isNotBlank()) {
            Text(keyMsg, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }

        var agentState by remember { mutableStateOf("") }
        var agentInstalling by remember { mutableStateOf(false) }
        Button(
            enabled = deployed && !agentInstalling,
            onClick = {
                agentInstalling = true
                agentState = "安装中…"
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        AgentManager.ensureAgentLayer(context.applicationContext) { agentState = it }
                    }
                    agentState = if (r.output.contains("AGENT_RC=0")) {
                        "✓ agent 层就绪（Node ${AgentManager.NODE_VERSION} + Claude Code ${AgentManager.CLAUDE_CODE_VERSION}）"
                    } else {
                        "✗ exit=${r.exitCode}：${r.output.takeLast(400)}"
                    }
                    agentInstalling = false
                }
            },
        ) { Text(if (agentInstalling) "安装 agent 层中…" else "安装 agent 层（Node + Claude Code）") }
        if (agentState.isNotBlank()) {
            Text(agentState, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }

        var av3Running by remember { mutableStateOf(false) }
        var av3Result by remember { mutableStateOf<AgentManager.Av3Result?>(null) }
        Button(
            enabled = deployed && !av3Running && agentState.startsWith("✓") && keyMask != null,
            onClick = {
                av3Running = true
                av3Result = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        AgentManager.runAv3(context.applicationContext)
                    }
                    av3Result = r
                    av3Running = false
                }
            },
        ) { Text(if (av3Running) "AV3 运行中（真实对话，最长 5 分钟）…" else "运行 AV3：真实对话 + 产物落袋") }

        av3Result?.let { r ->
            val pass = AgentManager.av3Passed(r)
            Text(
                if (pass) "✓ AV3 通过（产物已落 Downloads/Drydock）" else "✗ AV3 未通过（exit=${r.exitCode}）",
                color = if (pass) Color(0xFF4ADE80) else MaterialTheme.colorScheme.error,
            )
            r.resultText?.let { MonoBox("claude result：\n$it") }
            r.producedFile?.let { MonoBox("环境内产物：$it\n落袋：${r.landedUri}") }
            MonoBox(r.output.takeLast(600))
        }

        var i1Running by remember { mutableStateOf(false) }
        var i1Out by remember { mutableStateOf("") }
        Button(
            enabled = deployed && !i1Running && keyMask != null,
            onClick = {
                i1Running = true
                scope.launch {
                    val out = withContext(Dispatchers.IO) {
                        AgentManager.i1Sweep(context.applicationContext)
                    }
                    i1Out = out
                    i1Running = false
                }
            },
        ) { Text(if (i1Running) "扫描中…" else "I1 自检：密钥未落环境文件") }
        if (i1Out.isNotBlank()) {
            val clean = i1Out.contains("I1_SWEEP_DONE") &&
                Regex(":([0-9]+)").findAll(i1Out).all { it.groupValues[1] == "0" } &&
                !i1Out.contains("hits: [1-9]")
            Text(
                if (clean) "✓ I1 通过（注入后环境内文件零命中）" else "✗ I1 异常",
                color = if (clean) Color(0xFF4ADE80) else MaterialTheme.colorScheme.error,
            )
            MonoBox(i1Out.takeLast(500))
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))

        // ---------- 步骤 5：仪器（存活遥测 + 基准电池） ----------
        Text("步骤 5 · 仪器（存活遥测 + 基准电池）", style = MaterialTheme.typography.titleMedium)

        var timelineState by remember { mutableStateOf("") }
        var refreshTick by remember { mutableStateOf(0) }
        val probeState = remember(refreshTick) {
            val n = TerminalManager.readSessions(context).size
            "${n} 会话 · 时间线 ${Timeline.sizeBytes(context) / 1024}KB · ${Timeline.readAll(context).count { l -> l.contains("\"session_heartbeat\"") }} 心跳"
        }
        Text(probeState, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Button(onClick = { refreshTick++ }) { Text("刷新仪器状态") }

        Button(
            enabled = timelineState.isBlank() || !timelineState.startsWith("导出中"),
            onClick = {
                timelineState = "导出中…"
                scope.launch {
                    val uri = withContext(Dispatchers.IO) {
                        try {
                            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(java.util.Date())
                            val out = File(context.cacheDir, "timeline-$stamp.jsonl")
                            out.writeText(Timeline.readAll(context).joinToString("\n", postfix = "\n"))
                            Landing.toDownloads(context, out).toString()
                        } catch (e: Exception) {
                            "导出失败: $e"
                        }
                    }
                    timelineState = if (uri.startsWith("content://")) "✓ 已落 Downloads/Drydock" else uri
                }
            },
        ) { Text("导出时间线报告") }
        if (timelineState.isNotBlank()) {
            Text(timelineState, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }

        var benchRunning by remember { mutableStateOf(false) }
        var benchMsg by remember { mutableStateOf("") }
        Button(
            enabled = deployed && !benchRunning,
            onClick = {
                benchRunning = true
                benchMsg = "准备中…"
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        Bench.run(context.applicationContext, onLog = { benchMsg = it })
                    }
                    benchMsg = (if (r.ok) "✓ " else "✗ ") + (r.landedUri ?: r.log)
                    benchRunning = false
                }
            },
        ) { Text(if (benchRunning) "基准电池运行中…" else "运行基准电池（hyperfine）") }
        if (benchMsg.isNotBlank()) {
            Text(benchMsg.take(300), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))

        // ---------- 真机周新增：省电状态卡（D1 实测：默认省电策略=熄屏即冻结） ----------
        Text("步骤 6 · 省电状态（真机周）", style = MaterialTheme.typography.titleMedium)
        val powerMgr = remember { context.getSystemService(android.os.PowerManager::class.java) }
        var powerTick by remember { mutableStateOf(0) }
        val batteryExempt = remember(powerTick) {
            powerMgr?.isIgnoringBatteryOptimizations("dev.drydock.prototype") ?: false
        }
        val powerSave = remember(powerTick) { powerMgr?.isPowerSaveMode ?: false }
        Text(
            if (batteryExempt) "✓ 电池优化已豁免（锁屏任务可存活）"
            else "✗ 未豁免电池优化——锁屏后任务可能被冻结（真机 D1 实测）",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = if (batteryExempt) Color(0xFF4ADE80) else MaterialTheme.colorScheme.error,
        )
        Text(
            if (powerSave) "省电模式：开（可能加剧后台限制）" else "省电模式：关",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
        Button(onClick = { powerTick++ }) { Text("刷新省电状态") }
        Button(
            onClick = {
                // 标准豁免申请弹窗；HyperOS 上若无效则回落应用详情页（用户手动改省电策略）
                val direct = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:dev.drydock.prototype"),
                )
                try {
                    context.startActivity(direct)
                } catch (_: Exception) {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:dev.drydock.prototype"),
                        ),
                    )
                }
            },
        ) { Text("申请电池优化豁免 / 打开应用设置") }
        Text(
            "HyperOS 提示：应用详情 → 省电策略选「无限制」最彻底（D1 A/B 实测生效）",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** AV1：提示符可达 + 系统信息 + apt 国内源真实可用（update 拉到索引）。 */
private const val AV1_COMMAND =
    "echo AV1_PROMPT_OK; grep PRETTY_NAME /etc/os-release; uname -a; " +
        "apt-get update -o Acquire::Retries=2 2>&1 | tail -2; echo APT_RC=\${PIPESTATUS[0]}"

@Composable
private fun MonoBox(text: String) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    )
}
