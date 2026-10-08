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
import java.io.File

/** 开发者选项二级页：验收通道说明、时间线导出（D21 口径：当前份 + .old 合并）、版本详情。 */
@Composable
internal fun DevSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tlBusy by remember { mutableStateOf(false) }
    var tlMsg by remember { mutableStateOf("") }

    SettingsSubPage("开发者选项", onBack) {
        Text("验收通道", style = MaterialTheme.typography.titleMedium)
        Text(
            "debug 验收通道随本 Activity（am start --es：drydock_endpoint/recipe/exec64/export/provider_test/rescue）。" +
                "救援通道见桌面入口；原型验收仪器（部署/AV1/AV2/AV3 手动页）随 MainActivity 一并移除。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("诊断", style = MaterialTheme.typography.titleMedium)
        Button(
            enabled = !tlBusy && Timeline.file(context).exists(),
            onClick = {
                tlBusy = true; tlMsg = ""
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            val f = File(context.cacheDir, "drydock-timeline.jsonl")
                            f.writeText(Timeline.readAll(context).joinToString("\n") + "\n")
                            "${"%.0f".format(f.length() / 1000.0)} KB → ${Landing.toDownloads(context, f)}"
                        }
                    }
                    tlBusy = false
                    tlMsg = r.fold({ "✓ $it" }, { "✗ 导出失败：${it.message}" })
                }
            },
        ) { Text(if (tlBusy) "导出中…" else "导出时间线（timeline.jsonl → Downloads/Drydock）") }
        if (tlMsg.isNotBlank()) Text(tlMsg, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text("版本", style = MaterialTheme.typography.titleMedium)
        Text(
            "Drydock 原型 · 从 main tag 构建（git 纪律）\n环境 Ubuntu ${RootfsManifest.UBUNTU_VERSION} · 配方 ${RecipeManager.installedIds(context).joinToString("、").ifBlank { "未安装" }}",
            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
