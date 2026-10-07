package dev.drydock.prototype

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首启向导（D25 三步封顶，逐步可跳、保活除外；2026-10-07 版式与文案重做，用户定调）：
 * ①保活设置（省电白名单强引导，D22：锁屏任务存活硬前提）
 * ②连接大模型（纯引导，D30；文案面向不懂端点/key 概念的新手）
 * ③选择 Coding Agent（多选、可不选＝暂不安装；不提 Claude Code）
 * 版式：跟随主题（DrydockTheme，默认跟随系统）、全部居中、步骤圆点指示。
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
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StepDots(current = step)

        when (step) {
            1 -> PowerStep(onNext = { step = 2 })
            2 -> EndpointStep(
                onNext = { step = 3 },
                onSkip = { step = 3 },
            )
            3 -> RecipeStep(
                busy = busy,
                onRun = { picks ->
                    busy = "准备中…"
                    scope.launch {
                        val appCtx = context.applicationContext
                        val log = { s: String -> busy = s }
                        try {
                            var report = ""
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
                                            cfg.output.contains("CFG_SKIPPED") -> report += "端点未配置，已跳过（进终端后可随时补配）\n"
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

/** 步骤圆点指示（当前步高亮放大）。 */
@Composable
private fun StepDots(current: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        repeat(3) { i ->
            val active = i + 1 == current
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

/** 引导卡片（步骤②的两种路径说明）。 */
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

/** 步骤②连接大模型（D30 纯引导，可跳过）：面向新手的两条路径说明。 */
@Composable
private fun EndpointStep(onNext: () -> Unit, onSkip: () -> Unit) {
    StepHeader("🔑", "连接大模型")
    StepBody(
        "Agent 干活需要调用大模型。最简单的方式：先把下一步装完，进终端后把你的 API key 直接发给 agent，" +
            "它会帮你配好一切。",
    )
    GuideCard(
        title = "已有常见服务的 key？",
        body = "智谱 GLM、DeepSeek、Moonshot 等：把 key 按标准变量名写进 设置 → 环境变量" +
            "（如 ZHIPU_API_KEY、DEEPSEEK_API_KEY），agent 会自动识别，无需其他配置。",
    )
    GuideCard(
        title = "用自定义服务地址？",
        body = "服务比较小众时，在 设置 → Coding 端点 填一张小表（服务地址 / 模型名 / key），" +
            "配置会写进 agent 自己的配置文件。",
    )
    StepBody("拿不准？跳过这步——进终端后随时可配，agent 也能帮你配。")
    CenterButtons {
        Button(onClick = onNext) { Text("下一步") }
    }
    CenterButtons {
        OutlinedButton(onClick = onSkip) { Text("跳过此步") }
    }
}

/** 步骤③选择 agent（2026-10-07 用户定调）：多选、可不选＝暂不安装；不提 Claude Code。 */
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
                    busy.isNotBlank() -> busy
                    picks.isEmpty() -> "暂不安装，继续"
                    else -> "安装所选（${picks.size} 个）"
                },
            )
        }
    }
    if (doneMsg.isNotBlank()) {
        Text(
            doneMsg,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        StepBody("配置完成。进终端即可开始使用；之后的个性化配置（启动项、shell 环境）直接让 agent 帮你改。")
        CenterButtons {
            Button(onClick = onFinish) { Text("进入终端") }
        }
    }
}
