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
import androidx.compose.ui.res.stringResource
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

    SettingsSubPage(stringResource(R.string.endpoint_title), onBack) {
        Text(
            stringResource(R.string.endpoint_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val endpoints = remember(tick) { EndpointStore.all(context) }
        if (endpoints.isEmpty()) {
            Text(stringResource(R.string.endpoint_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        "${stringResource(e.protocol.labelRes)} · ${e.baseUrl} · key=\${e.envVar}",
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
                }) { Text(stringResource(R.string.endpoint_delete)) }
            }
        }
        // 追加表单（折叠式）；保存中（IO 写 env.sh/配置）走 BusyBar 反馈（R11）
        var showForm by remember { mutableStateOf(false) }
        var saving by remember { mutableStateOf(false) }
        if (!showForm) {
            OutlinedButton(enabled = !saving, onClick = { showForm = true }) { Text(stringResource(R.string.endpoint_add)) }
            if (saving) BusyBar(stringResource(R.string.ep_saving))
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
                    stringResource(R.string.endpoint_form_protocol_title),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                )
                EndpointStore.Protocol.entries.forEach { pr ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { fProtocol = pr },
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = fProtocol == pr, onClick = { fProtocol = pr })
                        Column {
                            Text(stringResource(pr.labelRes), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(pr.hintRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                OutlinedTextField(value = fBaseUrl, onValueChange = { fBaseUrl = it },
                    label = { Text(stringResource(R.string.wizard_base_url_label)) }, singleLine = true, isError = fBaseUrl.isNotBlank() && !fOk,
                    supportingText = { Text(stringResource(R.string.wizard_base_url_support)) },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = fModel, onValueChange = { fModel = it },
                    label = { Text(stringResource(R.string.wizard_model_label)) }, singleLine = true,
                    supportingText = { Text(stringResource(R.string.wizard_model_support)) },
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = fContext, onValueChange = { fContext = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.wizard_context_label)) }, singleLine = true,
                        supportingText = { Text(stringResource(R.string.wizard_form_optional_tokens)) }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = fOutput, onValueChange = { fOutput = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.wizard_output_label)) }, singleLine = true,
                        supportingText = { Text(stringResource(R.string.wizard_form_optional_tokens)) }, modifier = Modifier.weight(1f))
                }
                Text(
                    stringResource(R.string.endpoint_lengths_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(value = fEnvVar, onValueChange = { fEnvVar = it.filter { c -> c.isLetterOrDigit() || c == '_' }.uppercase() },
                    label = { Text(stringResource(R.string.wizard_envvar_label)) }, singleLine = true,
                    supportingText = { Text(stringResource(R.string.wizard_envvar_support)) },
                    modifier = Modifier.fillMaxWidth())
                var fKeyValue by remember { mutableStateOf("") }
                OutlinedTextField(value = fKeyValue, onValueChange = { fKeyValue = it },
                    label = { Text(stringResource(R.string.wizard_keyvalue_label)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text(stringResource(R.string.wizard_keyvalue_support)) },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = fProvider, onValueChange = { fProvider = it.filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' } },
                    label = { Text(stringResource(R.string.wizard_provider_label)) }, singleLine = true,
                    supportingText = { Text(stringResource(R.string.wizard_provider_support)) },
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
                    }) { Text(stringResource(R.string.endpoint_save)) }
                    OutlinedButton(onClick = { showForm = false }) { Text(stringResource(R.string.common_cancel)) }
                }
                Text(
                    stringResource(R.string.endpoint_saved_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(onClick = { context.startActivity(Intent(context, WizardActivity::class.java)) }) {
            Text(if (EndpointStore.wizardDone(context)) stringResource(R.string.endpoint_wizard_rerun)
            else stringResource(R.string.endpoint_wizard_run))
        }
    }
}
