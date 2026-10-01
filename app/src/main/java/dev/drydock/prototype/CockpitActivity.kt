package dev.drydock.prototype

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 驾驶舱最小版（D23 范围）：输入发起任务 → 对话流（含工具事件与批准卡片）→ 产物落袋。
 * 不做：终端、多会话、diff 预览、后台通知。
 */
class CockpitActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Timeline.log(applicationContext, "cockpit_start")
        // 验收自动化注入口：仅 debug 构建（同 drydock_api_key 模式），--es 直发一轮
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            intent?.getStringExtra("cockpit_msg")?.takeIf { it.isNotBlank() }?.let {
                Thread {
                    Thread.sleep(1_500) // 等 init LaunchedEffect 起跑
                    CockpitManager.send(this, it)
                }.start()
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CockpitScreen()
                }
            }
        }
    }
}

@Composable
private fun CockpitScreen() {
    val context = LocalContext.current
    val msgs by CockpitManager.msgs.collectAsState()
    val busy by CockpitManager.busy.collectAsState()
    var input by remember { mutableStateOf("") }
    var initDone by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { CockpitManager.init(context) }
        initDone = true
    }
    // 轮次进行中轮询审批/事件；结束后收尾一次
    LaunchedEffect(busy) {
        if (busy) {
            while (busy) {
                withContext(Dispatchers.IO) { CockpitManager.pollApprovals(context) }
                delay(400)
            }
        }
        withContext(Dispatchers.IO) { CockpitManager.pollApprovals(context) }
    }
    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("驾驶舱（最小版）", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    !initDone -> "初始化…"
                    busy -> "任务运行中·等待批准/完成"
                    else -> "空闲"
                },
                fontSize = 12.sp,
                color = if (busy) Color(0xFFFBBF24) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(modifier = Modifier.weight(1f), state = listState) {
            items(msgs) { m ->
                when (m) {
                    is CockpitManager.Msg.User -> Bubble("你", m.text, MaterialTheme.colorScheme.onSurface)
                    is CockpitManager.Msg.Assistant ->
                        Bubble("claude", m.text, if (m.ok) Color(0xFF4ADE80) else MaterialTheme.colorScheme.error)
                    is CockpitManager.Msg.System -> Bubble("系统", m.text, Color(0xFFFBBF24))
                    is CockpitManager.Msg.Tool -> ToolCard(context, m)
                }
            }
        }

        WorkspaceSection(context, refreshKey = msgs.size)

        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("让 claude 做什么？", fontSize = 13.sp) },
                modifier = Modifier.weight(1f),
                enabled = !busy,
                maxLines = 4,
            )
            Button(
                enabled = !busy && input.isNotBlank() && initDone,
                onClick = {
                    CockpitManager.send(context, input)
                    input = ""
                },
            ) { Text("发送") }
        }
    }
}

@Composable
private fun Bubble(who: String, text: String, color: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(who, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, fontSize = 14.sp, color = color)
    }
}

@Composable
private fun ToolCard(context: android.content.Context, m: CockpitManager.Msg.Tool) {
    val (stateLabel, stateColor) = when (m.state) {
        CockpitManager.WAITING -> "等待批准" to Color(0xFFFBBF24)
        CockpitManager.APPROVED -> "已批准" to Color(0xFF4ADE80)
        CockpitManager.DENIED -> "已拒绝" to MaterialTheme.colorScheme.error
        CockpitManager.TIMEOUT_DENIED -> "超时拒绝" to MaterialTheme.colorScheme.error
        else -> "自动放行" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("工具 · ${m.tool}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stateLabel, fontSize = 11.sp, color = stateColor)
        }
        Text(m.brief, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        if (m.state == CockpitManager.WAITING) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                    onClick = { CockpitManager.decide(context, m.approvalId, true) },
                ) { Text("批准执行") }
                OutlinedButton(onClick = { CockpitManager.decide(context, m.approvalId, false) }) { Text("拒绝") }
            }
        }
    }
}

@Composable
private fun WorkspaceSection(context: Context, refreshKey: Int) {
    val files = remember(refreshKey) { CockpitManager.workspaceFiles(context) }
    if (files.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        Text("产物（workspace，新→旧）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        files.take(3).forEach { f ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(f.name, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                OutlinedButton(onClick = {
                    Thread {
                        val uri: Uri = Landing.toDownloads(context, f)
                        runCatching {
                            Toast.makeText(context, "已落袋 $uri", Toast.LENGTH_SHORT).show()
                        }
                    }.start()
                }) { Text("落袋", fontSize = 11.sp) }
            }
        }
    }
}
