package dev.drydock.prototype

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首启向导（D25 三步封顶 + 2026-10-07 两轮用户定调）：
 * 0) 欢迎页：应用是干什么的（本轮新增，进向导先讲定位再进配置）
 * 1) 保活设置（省电白名单强引导，D22：锁屏任务存活硬前提，唯一不可跳步）
 * 2) 连接大模型（本轮从纯引导改为真实可配：常见服务选厂商填 key → 写入 env.sh；
 *    自定义服务地址 → 表单写进 agent 配置；仍可跳过）
 * 3) 选择 Coding Agent（多选、可不选＝暂不安装；不提 Claude Code）
 * 版式：跟随主题（DrydockTheme）、全居中、步骤圆点、左上角「‹」返回（不依赖系统导航）。
 * 首次真机路径：环境部署原本只在「新建会话」路径做——步骤③安装/进入终端前统一补齐。
 */
class WizardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DrydockTheme { WizardScreen() }
        }
    }
}

/** 常见服务 → 环境变量名（D31 口径：GLM 双变量——opencode 认 ZHIPU_API_KEY，
 *  pi 按名精确匹配 ZAI_CODING_CN_API_KEY；其余为 models.dev/业界标准名）。 */
private enum class Vendor(val label: String, val envVars: List<String>) {
    GLM("智谱 GLM", listOf("ZHIPU_API_KEY", "ZAI_CODING_CN_API_KEY")),
    DEEPSEEK("DeepSeek", listOf("DEEPSEEK_API_KEY")),
    MOONSHOT("Moonshot", listOf("MOONSHOT_API_KEY")),
    OPENAI("OpenAI", listOf("OPENAI_API_KEY")),
    ANTHROPIC("Anthropic", listOf("ANTHROPIC_API_KEY")),
}

/** 常见服务 key 落 env.sh（GLM 双变量），复用通用 upsert。 */
private fun saveVendorKey(context: android.content.Context, vendor: Vendor, key: String) {
    RootfsManager.upsertEnvExports(context, vendor.envVars.map { it to key })
}

/** 自定义端点：进 EndpointStore（prefs，未部署也安全）；key 值非空时以
 *  「export 变量名=值」写进 env.sh（变量名留空默认 DRYDOCK_API_KEY）；环境已
 *  部署时立即写 agent 配置，未部署则由步骤③安装后统一补写（onRun 里的
 *  applyEndpointConfig）。 */
private fun saveCustomEndpoint(
    context: android.content.Context,
    protocol: EndpointStore.Protocol,
    baseUrl: String,
    model: String,
    contextWindow: String,
    outputTokens: String,
    envVar: String,
    provider: String,
    keyValue: String,
) {
    EndpointStore.add(context, protocol, baseUrl, model,
        contextWindow.takeIf { it.isNotBlank() }?.toLongOrNull(),
        outputTokens.takeIf { it.isNotBlank() }?.toLongOrNull(),
        envVar, provider)
    if (keyValue.isNotBlank()) {
        val resolvedName = envVar.trim().ifBlank { "DRYDOCK_API_KEY" }
        RootfsManager.upsertEnvExports(context, listOf(resolvedName to keyValue.trim()))
    }
    if (RootfsManager.isDeployed(context)) {
        val cfg = RecipeManager.applyEndpointConfig(context)
        // 标记缺失不视为失败：步骤③还会再写一次；此处只记日志
        if (!cfg.output.contains("CFG_RC") && !cfg.output.contains("MERGED") && !cfg.output.contains("CFG_SKIPPED")) {
            android.util.Log.w("DrydockWizard", "applyEndpointConfig: ${cfg.output.takeLast(200)}")
        }
    }
}

@Composable
private fun WizardScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf("") }
    var doneMsg by remember { mutableStateOf("") }

    /** 步骤③所有动作前统一补环境（幂等：已部署秒过；新建会话路径才有部署逻辑，
     *  向导直达终端的全新设备此前会失败——本轮补上）。 */
    suspend fun ensureEnvReady(appCtx: android.content.Context): Boolean {
        if (RootfsManager.isDeployed(appCtx)) return true
        withContext(Dispatchers.IO) { RootfsManager.deploy(appCtx) { } }
        return RootfsManager.isDeployed(appCtx)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 左上角返回（不依赖系统导航）：欢迎页退出向导，其余回上一层级；圆点仅配置步显示
        Box(Modifier.fillMaxWidth()) {
            TextButton(
                onClick = {
                    if (step == 0) (context as? Activity)?.finish() else step -= 1
                },
                modifier = Modifier.align(Alignment.CenterStart),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) { Text("‹", fontSize = 26.sp) }
            if (step in 1..3) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    repeat(3) { i ->
                        val active = i + 1 == step
                        Box(
                            modifier = Modifier
                                .size(if (active) 10.dp else 7.dp)
                                .background(
                                    color = if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
            }
        }

        when (step) {
            0 -> WelcomeStep(onStart = { step = 1 })
            1 -> PowerStep(onNext = { step = 2 })
            2 -> EndpointStep(onNext = { step = 3 }, onSkip = { step = 3 })
            3 -> RecipeStep(
                busy = busy,
                onRun = { picks ->
                    busy = "准备中…"
                    scope.launch {
                        val appCtx = context.applicationContext
                        val log = { s: String -> busy = s }
                        try {
                            var report = ""
                            if (!ensureEnvReady(appCtx)) {
                                busy = ""
                                doneMsg = "✗ Linux 环境准备失败（网络或存储问题），可稍后重试\n"
                                return@launch
                            }
                            val installed = mutableListOf<RecipeManager.Recipe>()
                            withContext(Dispatchers.IO) {
                                picks.forEach { r ->
                                    val res = RecipeManager.ensure(appCtx, r, log)
                                    if (res.output.contains("RECIPE_RC=0")) {
                                        report += "✓ ${r.title} 安装完成\n"
                                        installed += r
                                    } else {
                                        report += "✗ ${r.title} 安装失败：${res.output.takeLast(200)}\n"
                                    }
                                }
                                if (picks.isNotEmpty()) {
                                    RecipeManager.applyEndpointConfig(appCtx).also { cfg ->
                                        when {
                                            cfg.output.contains("CFG_SKIPPED") -> report += "未添加自定义端点（常见服务走环境变量，无需此步）\n"
                                            cfg.output.contains("OPENCODE_CFG_MERGED") ||
                                                cfg.output.contains("PI_CFG_MERGED") -> report += "✓ 端点配置已合并写入\n"
                                            else -> report += "✗ 端点配置失败：${cfg.output.takeLast(200)}\n"
                                        }
                                    }
                                    if (installed.isNotEmpty() && EndpointStore.all(appCtx).isNotEmpty()) {
                                        log("验证中：让 ${installed.first().title} 出第一句话…")
                                        val s = RecipeManager.smoke(appCtx, installed.first())
                                        report += if (s.output.contains("SMOKE_RC=0")) {
                                            "✓ agent 已出第一句话\n"
                                        } else {
                                            "✗ 冒烟未过（可稍后在终端里重试）：${s.output.takeLast(200)}\n"
                                        }
                                    }
                                } else {
                                    withContext(Dispatchers.IO) { RecipeManager.applyEndpointConfig(appCtx) }
                                    report += "暂不安装。可随时在 设置 → 初始设置向导 重新安装，或进终端自行安装。\n"
                                }
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
                    busy = "正在准备终端…"
                    scope.launch {
                        val appCtx = context.applicationContext
                        try {
                            ensureEnvReady(appCtx)
                        } catch (_: Exception) {
                        }
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

/** 步骤头部：图标 + 居中大标题。 */
@Composable
private fun StepHeader(icon: String, title: String) {
    Text(icon, fontSize = 44.sp)
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
}

/** 居中正文段。 */
@Composable
private fun StepBody(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = color,
    )
}

/** 居中的按钮行。 */
@Composable
private fun CenterButtons(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** 引导/说明卡片。 */
@Composable
private fun GuideCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 欢迎页（step 0）：应用定位，三件事各一句话。 */
@Composable
private fun WelcomeStep(onStart: () -> Unit) {
    StepHeader("⚓", "欢迎使用 Drydock")
    StepBody(
        "Drydock 在你的手机上运行一个完整的 Linux 环境，" +
            "AI 编程助手（agent）住在这里，随时帮你干活。",
    )
    GuideCard("🔒 锁屏继续干活", "配置一次省电白名单，锁屏后任务不中断。")
    GuideCard("🤖 预制多种 Agent", "OpenCode、pi 等常用 agent，选好即可下载使用。")
    GuideCard("📁 产物直接可见", "agent 生成的文件在系统文件管理器里就能看到。")
    CenterButtons {
        Button(onClick = onStart) { Text("开始配置") }
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
    StepHeader("🔋", "保活设置")
    if (exempt) {
        StepBody(
            "✓ 已允许后台运行\n锁屏后任务可以继续干活",
            color = Color(0xFF4ADE80),
        )
    } else {
        StepBody(
            "Drydock 需要被允许在锁屏后继续运行——这是「挂机干活」的前提；" +
                "不放行的话，锁屏后任务会被系统暂停。这一步不能跳过。",
        )
        StepBody(
            "小米 HyperOS：若标准弹窗未生效，请到 系统设置 → 应用信息 → 省电策略 选「无限制」，回来点刷新。",
            color = MaterialTheme.colorScheme.outline,
        )
        CenterButtons {
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
            }) { Text("允许后台运行") }
        }
        CenterButtons {
            OutlinedButton(onClick = { tick++ }) { Text("我已设置，刷新状态") }
        }
    }
    CenterButtons {
        Button(enabled = exempt, onClick = onNext) { Text("下一步") }
    }
}

/** 模式选择卡（常见服务 / 自定义服务地址）。 */
@Composable
private fun ModeCard(title: String, desc: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 厂商选择 chip。 */
@Composable
private fun VendorChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            )
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 步骤②连接大模型（2026-10-07 第二轮定调：从纯引导改为真实可配）：
 * - 常见服务：厂商 chip + key 输入 → 写入 env.sh（opencode/pi 内置目录按标准变量名自动识别，D30/D31）
 * - 自定义服务地址：D30 表单（协议 / Base URL / 模型 / key 变量名）→ EndpointStore + agent 配置
 * - 主按钮在有未保存的合法输入时变「保存并下一步」，保存成功才前进；跳过不保存。
 */
@Composable
private fun EndpointStep(onNext: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(0) }
    var vendor by remember { mutableStateOf(Vendor.GLM) }
    var keyText by remember { mutableStateOf("") }
    var fProtocol by remember { mutableStateOf(EndpointStore.Protocol.CHAT_COMPLETIONS) }
    var fBaseUrl by remember { mutableStateOf("") }
    var fModel by remember { mutableStateOf("") }
    var fContext by remember { mutableStateOf("") }
    var fOutput by remember { mutableStateOf("") }
    var fEnvVar by remember { mutableStateOf("") }
    var fKeyValue by remember { mutableStateOf("") }
    var fProvider by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    val customOk = fBaseUrl.startsWith("http://") || fBaseUrl.startsWith("https://")
    val hasInput = if (mode == 0) keyText.isNotBlank() else customOk

    fun saveAndAdvance() {
        if (saving) return
        err = ""
        if (!hasInput) {
            onNext()
            return
        }
        saving = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (mode == 0) {
                        saveVendorKey(context.applicationContext, vendor, keyText.trim())
                    } else {
                        saveCustomEndpoint(context.applicationContext, fProtocol, fBaseUrl.trim(),
                            fModel.trim(), fContext.trim(), fOutput.trim(), fEnvVar.trim(),
                            fProvider.trim(), fKeyValue.trim())
                    }
                }
            }
            saving = false
            r.fold(
                onSuccess = { onNext() },
                onFailure = { err = "保存失败：${it.message}" },
            )
        }
    }

    StepHeader("🔑", "连接大模型")
    StepBody("把 agent 连上大模型：常见服务填一次 key 即可，或填写自定义服务地址；也可以先跳过。")

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ModeCard("常见服务", "选服务商，填 key", mode == 0, onClick = { mode = 0 }, modifier = Modifier.weight(1f))
        ModeCard("自定义服务地址", "协议 / 地址 / 模型", mode == 1, onClick = { mode = 1 }, modifier = Modifier.weight(1f))
    }

    if (mode == 0) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VendorChip(Vendor.GLM.label, vendor == Vendor.GLM) { vendor = Vendor.GLM }
            VendorChip(Vendor.DEEPSEEK.label, vendor == Vendor.DEEPSEEK) { vendor = Vendor.DEEPSEEK }
            VendorChip(Vendor.MOONSHOT.label, vendor == Vendor.MOONSHOT) { vendor = Vendor.MOONSHOT }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VendorChip(Vendor.OPENAI.label, vendor == Vendor.OPENAI) { vendor = Vendor.OPENAI }
            VendorChip(Vendor.ANTHROPIC.label, vendor == Vendor.ANTHROPIC) { vendor = Vendor.ANTHROPIC }
        }
        OutlinedTextField(
            value = keyText,
            onValueChange = { keyText = it },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { saveAndAdvance() }),
            modifier = Modifier.fillMaxWidth(),
        )
        StepBody(
            "key 在服务商控制台获取。保存后写入环境变量（~/.drydock/env.sh），" +
                "新会话生效，agent 自动识别，无需其他配置。",
        )
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "① 选协议（对话报文格式，选错连不上）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            EndpointStore.Protocol.entries.forEach { pr ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { fProtocol = pr },
                    verticalAlignment = Alignment.CenterVertically,
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
            Text("② 填服务信息", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(value = fBaseUrl, onValueChange = { fBaseUrl = it },
                label = { Text("Base URL") }, singleLine = true, isError = fBaseUrl.isNotBlank() && !customOk,
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
            StepBody(
                "两个长度照服务商文档填：上下文窗口 = 模型一次能读进多少内容；" +
                    "最大输出长度 = 单次最多生成多少（很多服务需要显式设置，留空用工具默认值）。",
            )
            Text("③ key 与标识", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(value = fEnvVar, onValueChange = { fEnvVar = it.filter { c -> c.isLetterOrDigit() || c == '_' }.uppercase() },
                label = { Text("Key 变量名") }, singleLine = true,
                supportingText = { Text("留空默认 DRYDOCK_API_KEY") },
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = fKeyValue, onValueChange = { fKeyValue = it },
                label = { Text("API key 值") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { Text("保存后以「export 变量名=key值」写进 ~/.drydock/env.sh；留空则只写配置，key 稍后自己补") },
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = fProvider, onValueChange = { fProvider = it.filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' } },
                label = { Text("Provider 名") }, singleLine = true,
                supportingText = { Text("这条端点在配置文件里的标识名：agent 里模型会显示为「provider名/模型名」；不影响连接，留空按域名自动生成，重复添加同名会覆盖更新") },
                modifier = Modifier.fillMaxWidth())
            StepBody(
                "之后换 key：改 ~/.drydock/env.sh 即可（设置 → 环境变量），配置文件不用动。",
            )
        }
    }

    if (err.isNotBlank()) {
        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
    CenterButtons {
        Button(
            enabled = !saving,
            onClick = { saveAndAdvance() },
        ) {
            Text(when {
                saving -> "保存中…"
                hasInput -> "保存并下一步"
                else -> "下一步"
            })
        }
    }
    CenterButtons {
        OutlinedButton(enabled = !saving, onClick = onSkip) { Text("跳过此步") }
    }
}

/** 步骤③选择 agent（多选、可不选＝暂不安装；不提 Claude Code）。 */
@Composable
private fun RecipeStep(
    busy: String,
    onRun: (List<RecipeManager.Recipe>) -> Unit,
    doneMsg: String,
    onFinish: () -> Unit,
) {
    var picks by remember { mutableStateOf(setOf(RecipeManager.OPENCODE)) }
    val locked = busy.isNotBlank() || doneMsg.isNotBlank()

    StepHeader("🤖", "选择 Coding Agent")
    StepBody("我们预制了几种可用的 Agent，供你下载安装。可以多选，也可以都不选（暂不安装）。")

    listOf(
        RecipeManager.OPENCODE to "终端里全功能运行的编程 agent",
        RecipeManager.PI to "轻量的终端编程 agent",
        RecipeManager.DSH to "编程 agent，带浏览器网页界面",
    ).forEach { (r, desc) ->
        val checked = r in picks
        Card(
            modifier = Modifier.fillMaxWidth().clickable(enabled = !locked) {
                picks = if (checked) picks - r else picks + r
            },
            shape = RoundedCornerShape(14.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = { if (!locked) picks = if (checked) picks - r else picks + r })
                Column(Modifier.weight(1f)) {
                    Text(r.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    CenterButtons {
        Button(
            enabled = !locked,
            onClick = { onRun(picks.toList()) },
        ) {
            Text(
                when {
                    picks.isEmpty() -> "暂不安装，继续"
                    else -> "安装所选（${picks.size} 个）"
                },
            )
        }
    }
    // busy 文字走页面内 BusyBar（R11）：主按钮文字固定，不被安装进度文案撑变形
    if (busy.isNotBlank()) {
        BusyBar(busy, Modifier.fillMaxWidth().padding(top = 4.dp))
    }
    if (doneMsg.isNotBlank()) {
        Text(
            doneMsg,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        StepBody("配置完成。进终端即可开始使用；之后的个性化配置（启动项、shell 环境）直接让 agent 帮你改。")
        CenterButtons {
            Button(onClick = onFinish) { Text("进入终端") }
        }
    }
}
