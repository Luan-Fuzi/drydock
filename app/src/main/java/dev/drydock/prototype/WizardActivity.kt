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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
private enum class Vendor(val labelRes: Int, val envVars: List<String>) {
    GLM(R.string.vendor_glm, listOf("ZHIPU_API_KEY", "ZAI_CODING_CN_API_KEY")),
    DEEPSEEK(R.string.vendor_deepseek, listOf("DEEPSEEK_API_KEY")),
    MOONSHOT(R.string.vendor_moonshot, listOf("MOONSHOT_API_KEY")),
    OPENAI(R.string.vendor_openai, listOf("OPENAI_API_KEY")),
    ANTHROPIC(R.string.vendor_anthropic, listOf("ANTHROPIC_API_KEY")),
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
                modifier = Modifier.align(Alignment.CenterStart).testTag("wizard_back"),
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
                    busy = context.getString(R.string.wizard_busy_prepare)
                    scope.launch {
                        val appCtx = context.applicationContext
                        val log = { s: String -> busy = s }
                        try {
                            var report = ""
                            if (!ensureEnvReady(appCtx)) {
                                busy = ""
                                doneMsg = context.getString(R.string.wizard_r_env_fail)
                                return@launch
                            }
                            val installed = mutableListOf<RecipeManager.Recipe>()
                            withContext(Dispatchers.IO) {
                                picks.forEach { r ->
                                    val res = RecipeManager.ensure(appCtx, r, log)
                                    if (res.output.contains("RECIPE_RC=0")) {
                                        report += context.getString(R.string.wizard_r_install_ok, r.title)
                                        installed += r
                                    } else {
                                        report += context.getString(R.string.wizard_r_install_fail, r.title, res.output.takeLast(200))
                                    }
                                }
                                if (picks.isNotEmpty()) {
                                    RecipeManager.applyEndpointConfig(appCtx).also { cfg ->
                                        when {
                                            cfg.output.contains("CFG_SKIPPED") -> report += context.getString(R.string.wizard_r_no_custom)
                                            cfg.output.contains("OPENCODE_CFG_MERGED") ||
                                                cfg.output.contains("PI_CFG_MERGED") -> report += context.getString(R.string.wizard_r_cfg_merged)
                                            else -> report += context.getString(R.string.wizard_r_cfg_fail, cfg.output.takeLast(200))
                                        }
                                    }
                                    if (installed.isNotEmpty() && EndpointStore.all(appCtx).isNotEmpty()) {
                                        log(context.getString(R.string.wizard_r_verify, installed.first().title))
                                        val s = RecipeManager.smoke(appCtx, installed.first())
                                        report += if (s.output.contains("SMOKE_RC=0")) {
                                            context.getString(R.string.wizard_r_smoke_ok)
                                        } else {
                                            context.getString(R.string.wizard_r_smoke_fail, s.output.takeLast(200))
                                        }
                                    }
                                } else {
                                    withContext(Dispatchers.IO) { RecipeManager.applyEndpointConfig(appCtx) }
                                    report += context.getString(R.string.wizard_r_skipped)
                                }
                            }
                            EndpointStore.setWizardDone(appCtx)
                            busy = ""
                            doneMsg = report
                        } catch (e: Exception) {
                            busy = ""
                            doneMsg = context.getString(R.string.wizard_r_error, e.toString())
                        }
                    }
                },
                doneMsg = doneMsg,
                onFinish = {
                    busy = context.getString(R.string.wizard_busy_terminal)
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
                            doneMsg += context.getString(R.string.wizard_r_start_fail)
                        }
                    }
                },
            )
        }
    }
}

/** 步骤头部：图标 + 居中大标题。tag = 步骤锚点（wizard_step_*，i18n 批 1：
 *  剧本按 resource-id 等步骤页，不依赖标题文案）。 */
@Composable
private fun StepHeader(icon: String, title: String, tag: String? = null) {
    Text(icon, fontSize = 44.sp)
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier),
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
    StepHeader("⚓", stringResource(R.string.wizard_welcome_title), "wizard_step_welcome")
    StepBody(
        stringResource(R.string.wizard_welcome_body),
    )
    GuideCard(stringResource(R.string.wizard_card_keepalive_title), stringResource(R.string.wizard_card_keepalive_body))
    GuideCard(stringResource(R.string.wizard_card_agents_title), stringResource(R.string.wizard_card_agents_body))
    GuideCard(stringResource(R.string.wizard_card_files_title), stringResource(R.string.wizard_card_files_body))
    CenterButtons {
        Button(onClick = onStart, modifier = Modifier.testTag("wizard_start")) { Text(stringResource(R.string.wizard_start)) }
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
    StepHeader("🔋", stringResource(R.string.wizard_power_title), "wizard_step_power")
    if (exempt) {
        StepBody(
            stringResource(R.string.wizard_power_ok),
            color = Color(0xFF4ADE80),
        )
    } else {
        StepBody(
            stringResource(R.string.wizard_power_body),
        )
        StepBody(
            stringResource(R.string.wizard_power_hyperos),
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
            }) { Text(stringResource(R.string.wizard_power_allow)) }
        }
        CenterButtons {
            OutlinedButton(onClick = { tick++ }) { Text(stringResource(R.string.wizard_power_refresh)) }
        }
    }
    CenterButtons {
        Button(enabled = exempt, onClick = onNext, modifier = Modifier.testTag("wizard_next")) { Text(stringResource(R.string.wizard_next)) }
    }
}

/** 模式选择卡（常见服务 / 自定义服务地址）。tag = 测试锚点（wizard_mode_*）。 */
@Composable
private fun ModeCard(title: String, desc: String, selected: Boolean, modifier: Modifier = Modifier, tag: String = "", onClick: () -> Unit) {
    Card(
        modifier = modifier.then(if (tag.isNotEmpty()) Modifier.testTag(tag) else Modifier).clickable(onClick = onClick),
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

/** 厂商选择 chip。tag = 测试锚点（wizard_chip_*）。 */
@Composable
private fun VendorChip(label: String, selected: Boolean, tag: String = "", onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .then(if (tag.isNotEmpty()) Modifier.testTag(tag) else Modifier)
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
                onFailure = { err = context.getString(R.string.wizard_save_failed, it.message ?: "") },
            )
        }
    }

    StepHeader("🔑", stringResource(R.string.wizard_endpoint_title), "wizard_step_endpoint")
    StepBody(stringResource(R.string.wizard_endpoint_body))

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ModeCard(stringResource(R.string.wizard_mode_common_title), stringResource(R.string.wizard_mode_common_desc), mode == 0, onClick = { mode = 0 }, modifier = Modifier.weight(1f), tag = "wizard_mode_common")
        ModeCard(stringResource(R.string.wizard_mode_custom_title), stringResource(R.string.wizard_mode_custom_desc), mode == 1, onClick = { mode = 1 }, modifier = Modifier.weight(1f), tag = "wizard_mode_custom")
    }

    if (mode == 0) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VendorChip(stringResource(Vendor.GLM.labelRes), vendor == Vendor.GLM, "wizard_chip_glm") { vendor = Vendor.GLM }
            VendorChip(stringResource(Vendor.DEEPSEEK.labelRes), vendor == Vendor.DEEPSEEK, "wizard_chip_deepseek") { vendor = Vendor.DEEPSEEK }
            VendorChip(stringResource(Vendor.MOONSHOT.labelRes), vendor == Vendor.MOONSHOT, "wizard_chip_moonshot") { vendor = Vendor.MOONSHOT }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VendorChip(stringResource(Vendor.OPENAI.labelRes), vendor == Vendor.OPENAI, "wizard_chip_openai") { vendor = Vendor.OPENAI }
            VendorChip(stringResource(Vendor.ANTHROPIC.labelRes), vendor == Vendor.ANTHROPIC, "wizard_chip_anthropic") { vendor = Vendor.ANTHROPIC }
        }
        OutlinedTextField(
            value = keyText,
            onValueChange = { keyText = it },
            label = { Text(stringResource(R.string.wizard_key_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { saveAndAdvance() }),
            modifier = Modifier.fillMaxWidth().testTag("wizard_api_key"),
        )
        StepBody(
            stringResource(R.string.wizard_key_hint),
        )
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.wizard_form_protocol_title),
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
            Text(stringResource(R.string.wizard_form_info_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(value = fBaseUrl, onValueChange = { fBaseUrl = it },
                label = { Text(stringResource(R.string.wizard_base_url_label)) }, singleLine = true, isError = fBaseUrl.isNotBlank() && !customOk,
                supportingText = { Text(stringResource(R.string.wizard_base_url_support)) },
                modifier = Modifier.fillMaxWidth().testTag("wizard_base_url"))
            OutlinedTextField(value = fModel, onValueChange = { fModel = it },
                label = { Text(stringResource(R.string.wizard_model_label)) }, singleLine = true,
                supportingText = { Text(stringResource(R.string.wizard_model_support)) },
                modifier = Modifier.fillMaxWidth().testTag("wizard_model"))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = fContext, onValueChange = { fContext = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.wizard_context_label)) }, singleLine = true,
                    supportingText = { Text(stringResource(R.string.wizard_form_optional_tokens)) }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = fOutput, onValueChange = { fOutput = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.wizard_output_label)) }, singleLine = true,
                    supportingText = { Text(stringResource(R.string.wizard_form_optional_tokens)) }, modifier = Modifier.weight(1f))
            }
            StepBody(
                stringResource(R.string.wizard_form_lengths_hint),
            )
            Text(stringResource(R.string.wizard_form_key_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(value = fEnvVar, onValueChange = { fEnvVar = it.filter { c -> c.isLetterOrDigit() || c == '_' }.uppercase() },
                label = { Text(stringResource(R.string.wizard_envvar_label)) }, singleLine = true,
                supportingText = { Text(stringResource(R.string.wizard_envvar_support)) },
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = fKeyValue, onValueChange = { fKeyValue = it },
                label = { Text(stringResource(R.string.wizard_keyvalue_label)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { Text(stringResource(R.string.wizard_keyvalue_support)) },
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = fProvider, onValueChange = { fProvider = it.filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' } },
                label = { Text(stringResource(R.string.wizard_provider_label)) }, singleLine = true,
                supportingText = { Text(stringResource(R.string.wizard_provider_support)) },
                modifier = Modifier.fillMaxWidth())
            StepBody(
                stringResource(R.string.wizard_form_tail_hint),
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
            // 与保活步主按钮复用 wizard_next（两步互斥不同屏）；t7 靠 wizard_step_* 分步
            modifier = Modifier.testTag("wizard_next"),
        ) {
            Text(when {
                saving -> stringResource(R.string.wizard_saving)
                hasInput -> stringResource(R.string.wizard_save_next)
                else -> stringResource(R.string.wizard_next)
            })
        }
    }
    CenterButtons {
        OutlinedButton(enabled = !saving, onClick = onSkip, modifier = Modifier.testTag("wizard_skip")) { Text(stringResource(R.string.wizard_skip)) }
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

    StepHeader("🤖", stringResource(R.string.wizard_recipe_title), "wizard_step_recipe")
    StepBody(stringResource(R.string.wizard_recipe_body))

    listOf(
        RecipeManager.OPENCODE to stringResource(R.string.wizard_recipe_opencode_desc),
        RecipeManager.PI to stringResource(R.string.wizard_recipe_pi_desc),
        RecipeManager.DSH to stringResource(R.string.wizard_recipe_dsh_desc),
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
            modifier = Modifier.testTag("wizard_install"),
        ) {
            Text(
                when {
                    picks.isEmpty() -> stringResource(R.string.wizard_install_none)
                    else -> stringResource(R.string.wizard_install_n, picks.size)
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
        StepBody(stringResource(R.string.wizard_done_hint))
        CenterButtons {
            Button(onClick = onFinish) { Text(stringResource(R.string.wizard_enter_terminal)) }
        }
    }
}
