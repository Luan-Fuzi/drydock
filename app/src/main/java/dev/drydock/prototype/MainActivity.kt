package dev.drydock.prototype

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                    // 会话由 :env 前台服务承载（主进程死不连累），UI 经状态文件发现
                    context.startForegroundService(
                        android.content.Intent(context, EnvService::class.java),
                    )
                    var found = false
                    repeat(25) {
                        if (it > 0) kotlinx.coroutines.delay(1000)
                        if (EnvService.readSession(context) != null) {
                            found = true
                            return@repeat
                        }
                    }
                    if (!found) {
                        layerState = "✗ 会话启动失败（看 logcat DrydockEnv/DrydockTerminal）"
                    } else {
                        context.startActivity(
                            android.content.Intent(context, TerminalActivity::class.java),
                        )
                    }
                }
            }) { Text("打开终端") }
        }
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
