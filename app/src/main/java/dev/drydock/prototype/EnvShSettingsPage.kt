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
import androidx.compose.ui.res.stringResource
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

    SettingsSubPage(stringResource(R.string.envsh_title), onBack) {
        Text(
            stringResource(R.string.envsh_intro),
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
                label = { Text(stringResource(R.string.envsh_field_label)) },
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
                            envMsg = r.fold({ context.getString(R.string.envsh_saved) }, { context.getString(R.string.envsh_save_fail, it.message ?: "") })
                        }
                    },
                ) { Text(if (envSaving) stringResource(R.string.ep_saving) else stringResource(R.string.session_dlg_save)) }
                if (envMsg.isNotBlank()) {
                    Text(envMsg, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Text(
                stringResource(R.string.envsh_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                stringResource(R.string.envsh_not_deployed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
