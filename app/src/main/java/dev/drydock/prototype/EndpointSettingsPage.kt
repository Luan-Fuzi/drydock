package dev.drydock.prototype

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier

/** Coding 端点二级页（D30）：列表可见可删 + 折叠式追加表单。 */
@Composable
internal fun EndpointSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }

    SettingsSubPage("Coding 端点", onBack) {
        Text(
            "自定义端点列表（写进 opencode/pi 的配置文件）。内置目录厂商不需要在这——" +
                "往 ~/.drydock/env.sh 放标准变量名即自动识别（GLM Coding Plan 用 ZHIPU_API_KEY）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val endpoints = remember(tick) { EndpointStore.all(context) }
        if (endpoints.isEmpty()) {
            Text("（暂无自定义端点）", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        endpoints.forEach { e ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${e.providerId} · ${e.model}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${e.protocol.label} · ${e.baseUrl} · key=\${e.envVar}",
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = {
                    EndpointStore.remove(context, e.providerId)
                    scope.launch(Dispatchers.IO) {
                        runCatching { RecipeManager.applyEndpointConfig(context.applicationContext) }
                        tick++
                    }
                }) { Text("删除") }
            }
        }
        // 追加表单（折叠式）；保存中（IO 写 env.sh/配置）走 BusyBar 反馈（R11）
        var showForm by remember { mutableStateOf(false) }
        var saving by remember { mutableStateOf(false) }
        if (!showForm) {
            OutlinedButton(enabled = !saving, onClick = { showForm = true }) { Text("添加自定义端点") }
            if (saving) BusyBar("保存中…")
        } else {
            var fProtocol by remember { mutableStateOf(EndpointStore.Protocol.CHAT_COMPLETIONS) }
            var fBaseUrl by remember { mutableStateOf("") }
            var fModel by remember { mutableStateOf("") }
            var fContext by remember { mutableStateOf("") }
            var fOutput by remember { mutableStateOf("") }
            var fEnvVar by remember { mutableStateOf("") }
            var fProvider by remember { mutableStateOf("") }
            val fOk = fBaseUrl.startsWith("http://") || fBaseUrl.startsWith("https://")
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "协议（agent 与服务对话用的报文格式，选错会连不上）",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                )
                EndpointStore.Protocol.entries.forEach { pr ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { fProtocol = pr },
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
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
                OutlinedTextField(value = fBaseUrl, onValueChange = { fBaseUrl = it },
                    label = { Text("Base URL") }, singleLine = true, isError = fBaseUrl.isNotBlank() && !fOk,
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
                Text(
                    "两个长度照服务商文档填：上下文窗口 = 模型一次能读进多少内容；最大输出长度 = 单次最多生成多少（留空用工具默认值）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(value = fEnvVar, onValueChange = { fEnvVar = it.filter { c -> c.isLetterOrDigit() || c == '_' }.uppercase() },
                    label = { Text("Key 变量名") }, singleLine = true,
                    supportingText = { Text("留空默认 DRYDOCK_API_KEY") },
                    modifier = Modifier.fillMaxWidth())
                var fKeyValue by remember { mutableStateOf("") }
                OutlinedTextField(value = fKeyValue, onValueChange = { fKeyValue = it },
                    label = { Text("API key 值") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text("保存后以「export 变量名=key值」写进 ~/.drydock/env.sh；留空则只写配置，key 稍后自己补") },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = fProvider, onValueChange = { fProvider = it.filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' } },
                    label = { Text("Provider 名") }, singleLine = true,
                    supportingText = { Text("这条端点在配置文件里的标识名：agent 里模型会显示为「provider名/模型名」；不影响连接，留空按域名自动生成，重复添加同名会覆盖更新") },
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = fOk && !saving, onClick = {
                        saving = true
                        EndpointStore.add(context, fProtocol, fBaseUrl, fModel,
                            fContext.trim().takeIf { it.isNotBlank() }?.toLongOrNull(),
                            fOutput.trim().takeIf { it.isNotBlank() }?.toLongOrNull(),
                            fEnvVar, fProvider)
                        showForm = false
                        scope.launch(Dispatchers.IO) {
                            if (fKeyValue.isNotBlank()) {
                                val resolvedName = fEnvVar.trim().ifBlank { "DRYDOCK_API_KEY" }
                                runCatching { RootfsManager.upsertEnvExports(context.applicationContext, listOf(resolvedName to fKeyValue.trim())) }
                            }
                            runCatching { RecipeManager.applyEndpointConfig(context.applicationContext) }
                            saving = false
                            tick++
                        }
                    }) { Text("保存并写入配置") }
                    OutlinedButton(onClick = { showForm = false }) { Text("取消") }
                }
                Text(
                    "保存后新会话生效；别忘往 ~/.drydock/env.sh 放上 key（变量名用上面填的名字）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(onClick = { context.startActivity(Intent(context, WizardActivity::class.java)) }) {
            Text(if (EndpointStore.wizardDone(context)) "重新运行初始设置向导" else "运行初始设置向导（保活 / 连接大模型 / 安装 agent）")
        }
    }
}
