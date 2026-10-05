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
                onSkip = {
                    EndpointStore.clear(context)
                    step = 3
                },
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
                                            cfg.output.contains("OPENCODE_CFG_WRITTEN") ||
                                                cfg.output.contains("PI_CFG_WRITTEN") -> report += "✓ 端点配置已写入\n"
                                            else -> report += "✗ 端点配置失败：${cfg.output.takeLast(200)}\n"
                                        }
                                    }
                                    if (EndpointStore.configured(appCtx)) {
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

private sealed interface EndpointMode {
    data object Builtin : EndpointMode
    data class Preset(val preset: EndpointStore.Preset) : EndpointMode
    data object Custom : EndpointMode
}

/** 步骤②端点（D30 三层）：内置厂商（引导 env.sh，不生成配置）/ 高频 plan 预设
 *  （四件套一次给对）/ 自定义端点（手填四件套）。key 一律走环境变量（D29）。 */
@Composable
private fun EndpointStep(onNext: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf<EndpointMode?>(null) }
    var protocol by remember { mutableStateOf(EndpointStore.Protocol.CHAT_COMPLETIONS) }
    var baseUrl by remember { mutableStateOf(EndpointStore.baseUrl(context) ?: "") }
    var model by remember { mutableStateOf(EndpointStore.model(context) ?: "") }
    var contextWindow by remember { mutableStateOf(EndpointStore.contextWindow(context)?.toString() ?: "") }
    var providerId by remember { mutableStateOf("") }
    var envVar by remember { mutableStateOf("") }
    val urlOk = baseUrl.startsWith("http://") || baseUrl.startsWith("https://")
    val formOk = when (mode) {
        is EndpointMode.Builtin -> true
        is EndpointMode.Preset -> true
        is EndpointMode.Custom -> urlOk && providerId.isNotBlank() && envVar.isNotBlank()
        null -> false
    }

    Text("端点与模型", style = MaterialTheme.typography.titleMedium)
    Text(
        "API key 不在应用里存储：选好下面的方式后，把 key 写进环境变量文件 ~/.drydock/env.sh" +
            "（设置 → 环境变量，或装好 agent 后发给它代写）。",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text("内置目录厂商（DeepSeek / OpenAI / Moonshot 等）", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    Row(modifier = Modifier.fillMaxWidth().clickable { mode = EndpointMode.Builtin }) {
        RadioButton(selected = mode is EndpointMode.Builtin, onClick = { mode = EndpointMode.Builtin })
        Column(Modifier.padding(top = 10.dp)) {
            Text("用环境变量直连，无需配置")
            Text(
                "往 ~/.drydock/env.sh 加一行 export DEEPSEEK_API_KEY=…（变量名按厂商文档），" +
                    "agent 的模型列表里自动出现，能力元数据（上下文/多模态）由工具内置目录提供。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Text("Coding Plan 预设", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    EndpointStore.presets.forEach { p ->
        Row(modifier = Modifier.fillMaxWidth().clickable { mode = EndpointMode.Preset(p) }) {
            RadioButton(selected = mode is EndpointMode.Preset && (mode as EndpointMode.Preset).preset.id == p.id,
                onClick = { mode = EndpointMode.Preset(p) })
            Column(Modifier.padding(top = 10.dp)) {
                Text(p.label)
                Text(
                    "${p.protocol.label} · ${p.model} · ${p.contextWindow / 1000}k 上下文（端点已配好，只需填 key）",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Row(modifier = Modifier.fillMaxWidth().clickable { mode = EndpointMode.Custom }) {
        RadioButton(selected = mode is EndpointMode.Custom, onClick = { mode = EndpointMode.Custom })
        Text("自定义端点（任意 OpenAI / Anthropic 兼容 API）", modifier = Modifier.padding(top = 10.dp))
    }

    if (mode is EndpointMode.Preset) {
        val p = (mode as EndpointMode.Preset).preset
        Text(
            "已选：${p.baseUrl}\n${p.note}",
            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "key 变量名：${p.envVar}（写入 ~/.drydock/env.sh：export ${p.envVar}=你的key）",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (mode is EndpointMode.Custom) {
        Text(
            "自定义端点的四件套要一次填对：协议、Base URL、模型 ID（端点实际服务的名字）、" +
                "key 变量名（之后按这个名字写进 env.sh）。provider 名用于配置段标识，" +
                "留空则按 Base URL 主机名自动生成。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EndpointStore.Protocol.entries.forEach { pr ->
            Row(modifier = Modifier.fillMaxWidth().clickable { protocol = pr }) {
                RadioButton(selected = protocol == pr, onClick = { protocol = pr })
                Text(pr.label, modifier = Modifier.padding(top = 12.dp))
            }
        }
        if (protocol == EndpointStore.Protocol.ANTHROPIC) {
            Text(
                "已知问题：OpenCode × Anthropic 组合存在适配器内部静默重试（裸端点本身正常）。" +
                    "选这个协议时建议搭配 pi，OpenCode 用户优先 Chat Completions。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL（如 https://example.com/v1）") },
            singleLine = true,
            isError = baseUrl.isNotBlank() && !urlOk,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("模型 ID（端点实际服务的模型名）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = providerId,
            onValueChange = { providerId = it.filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' } },
            label = { Text("Provider 名（配置段标识，可留空自动生成）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = envVar,
            onValueChange = { envVar = it.filter { c -> c.isLetterOrDigit() || c == '_' }.uppercase() },
            label = { Text("Key 的环境变量名（如 MY_LLM_KEY）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = contextWindow,
            onValueChange = { contextWindow = it.filter { c -> c.isDigit() } },
            label = { Text("上下文窗口 token 数（可选；不填走工具默认）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Button(
        enabled = formOk,
        onClick = {
            when (val m = mode) {
                is EndpointMode.Builtin -> {
                    // 不生成配置段：内置厂商走环境变量自动识别；只完成向导
                    EndpointStore.clear(context)
                    onNext()
                }
                is EndpointMode.Preset -> {
                    val p = m.preset
                    EndpointStore.save(context, p.protocol, p.baseUrl, p.model,
                        p.contextWindow.toString(), p.providerId, p.envVar)
                    onNext()
                }
                is EndpointMode.Custom -> {
                    EndpointStore.save(context, protocol, baseUrl, model, contextWindow, providerId, envVar)
                    onNext()
                }
                null -> {}
            }
        },
    ) { Text(if (mode is EndpointMode.Builtin) "用内置厂商（不写配置，下一步）" else "保存并下一步") }
    OutlinedButton(onClick = onSkip) { Text("跳过此步（稍后在终端里自己配）") }
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
    listOf(RecipeManager.OPENCODE, RecipeManager.PI, null).forEach { r ->
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
