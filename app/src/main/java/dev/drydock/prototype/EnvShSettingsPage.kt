package dev.drydock.prototype

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 环境变量二级页（D29）：env.sh 直接编辑。 */
@Composable
internal fun EnvShSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    SettingsSubPage("环境变量（~/.drydock/env.sh）", onBack) {
        Text(
            "每个新会话生效。key 写成 export DRYDOCK_API_KEY=…（opencode/pi 的配置已引用它），" +
                "其他工具要的变量（代理、各家 key）也放这里；复杂改动也可以直接让 agent 帮你改。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (RootfsManager.isDeployed(context)) {
            // 进入二级页时读一次（不挂轮询：会话注册表 5s 轮询会把编辑中的内容重置回文件）
            var envText by remember {
                mutableStateOf(
                    runCatching { RootfsManager.envShFile(context).readText() }.getOrElse { "" },
                )
            }
            var envSaving by remember { mutableStateOf(false) }
            var envMsg by remember { mutableStateOf("") }
            OutlinedTextField(
                value = envText,
                onValueChange = { envText = it },
                label = { Text("env.sh（bash 语法，逐行 export）") },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Button(
                    enabled = !envSaving,
                    onClick = {
                        envSaving = true; envMsg = ""
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching { RootfsManager.envShFile(context).writeText(envText) }
                            }
                            envSaving = false
                            envMsg = r.fold({ "✓ 已保存" }, { "✗ 保存失败：${it.message}" })
                        }
                    },
                ) { Text(if (envSaving) "保存中…" else "保存") }
                if (envMsg.isNotBlank()) {
                    Text(envMsg, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Text(
                "保存后新会话生效；已开着的终端输入 . ~/.drydock/env.sh 立即生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "（部署 Linux 环境后可编辑）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
