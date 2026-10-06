package dev.drydock.prototype

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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

/**
 * 首启向导（D25）：三步封顶，逐步可跳（保活除外）。
 * ①保活设置（省电白名单强引导，D22：锁屏任务存活硬前提）
 * ②端点与密钥（零预置：先选协议再配 Base URL/API Key/模型；key 只进 Keystore）
 * ③agent 配方（OpenCode、pi；可暂不安装）
 */
class WizardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { WizardScreen() }
            }
        }
    }
}

@Composable
private fun WizardScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(1) }
    var busy by remember { mutableStateOf("") }
    var doneMsg by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("初始设置（$step/3）", style = MaterialTheme.typography.titleLarge)

        when (step) {
            1 -> PowerStep(onNext = { step = 2 })
            2 -> EndpointStep(
                onNext = { step = 3 },
                onSkip = { step = 3 },
            )
            3 -> RecipeStep(
                busy = busy,
                onRun = { choice ->
                    busy = "准备中…"
                    scope.launch {
                        val appCtx = context.applicationContext
                        val log = { s: String -> busy = s }
                        try {
                            var report = ""
                            if (choice != null) {
                                withContext(Dispatchers.IO) {
                                    val r = RecipeManager.ensure(appCtx, choice, log)
                                    if (!r.output.contains("RECIPE_RC=0")) {
                                        report += "✗ ${choice.title} 安装失败：${r.output.takeLast(200)}\n"
                                        return@withContext
                                    }
                                    report += "✓ ${choice.title} ${choice.version} 已安装\n"
                                    RecipeManager.applyEndpointConfig(appCtx).also { cfg ->
                                        when {
                                            cfg.output.contains("CFG_SKIPPED") -> report += "端点未配置，已跳过（进终端后可随时在初始设置里补）\n"
                                            cfg.output.contains("OPENCODE_CFG_MERGED") ||
                                                cfg.output.contains("PI_CFG_MERGED") -> report += "✓ 端点配置已合并写入\n"
                                            else -> report += "✗ 端点配置失败：${cfg.output.takeLast(200)}\n"
                                        }
                                    }
                                    if (EndpointStore.all(appCtx).isNotEmpty()) {
                                        log("验证中：让 agent 出第一句话…")
                                        val s = RecipeManager.smoke(appCtx, choice)
                                        report += if (s.output.contains("SMOKE_RC=0")) {
                                            "✓ agent 已出第一句话\n"
                                        } else {
                                            "✗ 冒烟未过（可稍后在终端里重试）：${s.output.takeLast(200)}\n"
                                        }
                                    }
                                }
                            } else {
                                withContext(Dispatchers.IO) { RecipeManager.applyEndpointConfig(appCtx) }
                                report += "跳过安装；端点配置已写入\n"
                            }
                            EndpointStore.setWizardDone(appCtx)
                            busy = ""
                            doneMsg = report
                        } catch (e: Exception) {
                            busy = ""
                            doneMsg = "异常：$e"
                        }
                    }
                },
                doneMsg = doneMsg,
                onFinish = {
                    busy = "启动终端会话…（端点变量在会话建立时注入）"
                    scope.launch {
                        val appCtx = context.applicationContext
                        appCtx.startForegroundService(android.content.Intent(appCtx, EnvService::class.java))
                        var found = false
                        repeat(25) {
                            if (it > 0) kotlinx.coroutines.delay(1000)
                            if (TerminalManager.readSessions(appCtx).any { s -> s.name == TerminalManager.MAIN }) {
                                found = true
                                return@repeat
                            }
                        }
                        busy = ""
                        if (found) {
                            context.startActivity(android.content.Intent(context, TerminalActivity::class.java))
                        } else {
                            doneMsg += "✗ 会话启动失败（看 logcat DrydockEnv/DrydockTerminal）\n"
                        }
                    }
                },
            )
        }
    }
}

/** 步骤①保活：未豁免时不可下一步（唯一不可跳步，判据见 product-roadmap 阶段 1）。 */
@Composable
private fun PowerStep(onNext: () -> Unit) {
    val context = LocalContext.current
    val powerMgr = remember { context.getSystemService(android.os.PowerManager::class.java) }
    var tick by remember { mutableStateOf(0) }
    val exempt = remember(tick) {
        powerMgr?.isIgnoringBatteryOptimizations("dev.drydock.prototype") ?: false
    }
    Text("保活设置", style = MaterialTheme.typography.titleMedium)
    Text(
        if (exempt) "✓ 电池优化已豁免——锁屏后任务可以存活"
        else "未豁免电池优化：锁屏后任务会被系统冻结（真机实测结论 D22）。此步不可跳过。",
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        color = if (exempt) Color(0xFF4ADE80) else MaterialTheme.colorScheme.error,
    )
    if (!exempt) {
        Text(
            "HyperOS 提示：标准弹窗若无效，去应用详情 → 省电策略选「无限制」后回来点刷新。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = {
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
        }) { Text("申请电池优化豁免") }
        OutlinedButton(onClick = { tick++ }) { Text("我已设置，刷新状态") }
    }
    Button(enabled = exempt, onClick = onNext) { Text("下一步") }
}

/** 步骤②端点（D30 终版）：纯引导，可跳过。标准 key + 自定义端点指向设置页表单。 */
@Composable
private fun EndpointStep(onNext: () -> Unit, onSkip: () -> Unit) {
    Text("API key 与模型", style = MaterialTheme.typography.titleMedium)
    Text(
        "key 不在应用里存储，写在环境变量文件 ~/.drydock/env.sh（设置 → 环境变量，" +
            "或装好 agent 后发给它代写）。两种情况：\n\n" +
            "① 用内置目录厂商（DeepSeek / OpenAI / Moonshot / 智谱 GLM 等）：往 env.sh 放一行" +
            "标准变量名（如 export DEEPSEEK_API_KEY=你的key、GLM Coding Plan 用 ZHIPU_API_KEY），" +
            "agent 的模型列表自动出现，多模态/上下文元数据由工具官方目录维护，零配置；\n\n" +
            "② 用真正的自定义端点（目录外服务）：在 设置 → Coding 端点 " +
            "填一张小表（协议 / Base URL / 模型 ID / key 变量名），会写进两个 agent 各自的配置文件。",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "不确定选哪种？先跳过，进终端后 motd 有同样的说明，随时可配。",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onNext) { Text("下一步") }
    OutlinedButton(onClick = onSkip) { Text("跳过此步") }
}

/** 步骤③配方：OpenCode / pi / 暂不安装。 */
@Composable
private fun RecipeStep(
    busy: String,
    onRun: (RecipeManager.Recipe?) -> Unit,
    doneMsg: String,
    onFinish: () -> Unit,
) {
    var choice by remember { mutableStateOf<RecipeManager.Recipe?>(RecipeManager.OPENCODE) }

    Text("选择 coding agent（开源配方）", style = MaterialTheme.typography.titleMedium)
    Text(
        "安装脚本在终端环境内执行、过程可见；Claude Code 不在默认引导里，可稍后自行 npm 安装。",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    listOf(RecipeManager.OPENCODE, RecipeManager.PI, RecipeManager.DSH, null).forEach { r ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { choice = r },
        ) {
            RadioButton(selected = choice == r, onClick = { choice = r })
            Text(r?.title ?: "暂不安装", modifier = Modifier.padding(top = 12.dp))
        }
    }
    Button(
        enabled = busy.isBlank() && doneMsg.isBlank(),
        onClick = { onRun(choice) },
    ) { Text(if (busy.isBlank()) "开始配置" else busy) }
    if (doneMsg.isNotBlank()) {
        Text(doneMsg, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Text(
            "配置完成。进终端直接运行 opencode 或 pi；之后的个性化配置（启动项、shell 环境）直接让 agent 帮你改。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onFinish) { Text("打开终端") }
    }
}
