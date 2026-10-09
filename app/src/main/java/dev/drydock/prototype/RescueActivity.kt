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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 救援通道 MVP（Q8 / 阶段 4）：ttyd 或环境内 shell 不可用时的兜底——
 * 宿主直接以 proot 执行单条命令（runInEnv 不依赖 ttyd/dtach），行式 REPL。
 * 产品期按 Q8 演进：rootfs 完整性校验、快照回滚、自动引导修复。
 */
class RescueActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 验收自动化注入口（仅 debug extra；产品路径不受影响）
        val initialCmd = intent?.getStringExtra("drydock_cmd")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { RescueScreen(initialCmd) }
            }
        }
    }
}

@Composable
private fun RescueScreen(initialCmd: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cmd by remember { mutableStateOf(initialCmd ?: "ls /; echo ---; df -h / | tail -1") }
    var out by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.rescue_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.rescue_desc),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = cmd,
            onValueChange = { cmd = it },
            label = { Text(stringResource(R.string.rescue_cmd_label)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !busy,
            onClick = {
                busy = true
                out += "\n\$ ${cmd.take(200)}\n"
                val c = cmd
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        RootfsManager.runInEnv(context.applicationContext, c)
                    }
                    out += r.output.takeLast(4000) + "\n[exit=${r.exitCode}]\n"
                    busy = false
                }
            },
        ) { Text(if (busy) stringResource(R.string.rescue_running) else stringResource(R.string.rescue_run)) }
        Text(out, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}
