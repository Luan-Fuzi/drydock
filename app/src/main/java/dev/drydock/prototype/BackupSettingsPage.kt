package dev.drydock.prototype

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 备份与导出二级页（D28-1 口径：工作区与配置，非全环境）。 */
@Composable
internal fun BackupSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var exportMsg by remember { mutableStateOf("") }

    SettingsSubPage("备份与导出", onBack) {
        Button(
            enabled = !exporting && RootfsManager.isDeployed(context),
            onClick = {
                exporting = true; exportMsg = ""
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching { RootfsManager.exportEnvTar(context.applicationContext) }
                    }
                    exporting = false
                    exportMsg = r.fold({ "✓ 已导出到 Downloads/Drydock（$it）" }, { "✗ 导出失败：${it.message}" })
                }
            },
        ) { Text(if (exporting) "导出中…（约 1 分钟）" else "导出工作区与配置（tar.gz）") }
        if (exportMsg.isNotBlank()) Text(exportMsg, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(
            "导出 /root 工作区与 drydock 配置（系统层按配方版本可重放，不进导出）；" +
                "含 ~/.drydock/env.sh——你写入的环境变量（含自行存放的 key）会进导出包。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
