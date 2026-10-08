package dev.drydock.prototype

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** 同名询问在 UI 侧的挂起载体：对话框按钮 resume 它。 */
private class ConflictAsk(
    val path: String,
    val remaining: Int,
    val cont: CancellableContinuation<RootfsManager.ImportDecision>,
)

/** 备份与导出二级页（D28-1 口径：工作区与配置，非全环境；R4 起加从 tar.gz 恢复）。 */
@Composable
internal fun BackupSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var exportMsg by remember { mutableStateOf("") }

    // ---- R4 恢复状态机：SAF 选文件 → 确认①（说明+密钥提示）→ 确认②（危险操作）
    // → （未部署则先部署）导入；同名不同内容逐个问（可一键全部覆盖/全部跳过）
    var importBusy by remember { mutableStateOf("") }
    var importMsg by remember { mutableStateOf("") }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var confirmDanger by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<ConflictAsk?>(null) }
    var applyAll by remember { mutableStateOf<RootfsManager.ImportDecision?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingUri = uri
    }

    fun answer(d: RootfsManager.ImportDecision) {
        ask?.cont?.let { c -> if (c.isActive) c.resumeWith(Result.success(d)) }
        ask = null
    }

    fun startImport(uri: Uri) {
        importBusy = "读取所选文件…"
        importMsg = ""
        applyAll = null
        scope.launch {
            try {
                val appCtx = context.applicationContext
                if (!RootfsManager.isDeployed(appCtx)) {
                    importBusy = "部署 Linux 环境（首次约 1 分钟）…"
                    withContext(Dispatchers.IO) { RootfsManager.deploy(appCtx) { } }
                    if (!RootfsManager.isDeployed(appCtx)) {
                        importMsg = "✗ 部署失败，恢复中止（网络/存储问题，可重试）"
                        importBusy = ""
                        return@launch
                    }
                }
                val r = RootfsManager.importEnvTar(
                    appCtx, uri,
                    onState = { s ->
                        importBusy = when (s) {
                            is RootfsManager.ImportState.Copying -> "读取所选文件…"
                            is RootfsManager.ImportState.Scanning -> "校验与解包导入包…（已 ${s.entries} 项）"
                            is RootfsManager.ImportState.Merging -> "合并进环境…（${s.done}/${s.total}）"
                        }
                    },
                    onConflict = { path, remaining ->
                        applyAll ?: suspendCancellableCoroutine { cont ->
                            ask = ConflictAsk(path, remaining, cont)
                        }
                    },
                )
                importMsg = "✓ 恢复完成：导入 ${r.imported} · 跳过 ${r.skipped} · 失败 ${r.failed}" +
                    if (r.failed > 0) "（如 ${r.failedSample.firstOrNull()}）" else ""
            } catch (e: RootfsManager.ImportReject) {
                importMsg = "✗ 已整体拒绝，环境未改动：${e.message}"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                importMsg = "✗ 恢复失败：${e.message}"
            }
            importBusy = ""
        }
    }

    SettingsSubPage("备份与导出", onBack) {
        Button(
            enabled = !exporting && importBusy.isBlank() && RootfsManager.isDeployed(context),
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
        ) { Text("导出工作区与配置（tar.gz）") }
        // busy 反馈走 BusyBar（R11）：按钮文字保持固定，不塞进度文字
        if (exporting) BusyBar("导出中…（约 1 分钟）")
        if (exportMsg.isNotBlank()) Text(exportMsg, fontSize = 12.sp, fontFamily = FontFamily.Monospace)

        // ---- 从 tar.gz 恢复（R4）----
        Button(
            enabled = importBusy.isBlank() && !exporting,
            onClick = { picker.launch(arrayOf("*/*")) },
        ) { Text("从 tar.gz 恢复…") }
        if (importBusy.isNotBlank()) BusyBar(importBusy)
        if (importMsg.isNotBlank()) Text(importMsg, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(
            "导出 /root 工作区与 drydock 配置（系统层按配方版本可重放，不进导出）；" +
                "产物存 Downloads/Drydock/drydock-env-export.tar.gz；" +
                "含 ~/.drydock/env.sh——你写入的环境变量（含自行存放的 key）会进导出包。\n" +
                "恢复按导出口径合并进当前环境：新文件直接写入，同名且内容不同会逐个询问" +
                "（可全部覆盖/全部跳过）；环境未部署时会先自动部署。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 确认①：说明 + 密钥随 env.sh 进入环境
        pendingUri?.let { uri ->
            val name = remember(uri) { FileBridge.displayName(context, uri) }
            val size = remember(uri) { docSize(context, uri) }
            AlertDialog(
                onDismissRequest = { pendingUri = null },
                title = { Text("从 tar.gz 恢复") },
                text = {
                    Text(
                        "已选择 $name（$size）。\n\n" +
                            "恢复会把这个备份合并进当前环境，已存在的同名文件会询问覆盖或跳过。\n" +
                            "⚠ 备份里的 ~/.drydock/env.sh 会随之进入环境——存放在其中的密钥一并进入。",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirmDanger = true }) { Text("继续") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingUri = null }) { Text("取消") }
                },
            )
        }

        // 确认②：危险操作二次确认
        if (confirmDanger) {
            AlertDialog(
                onDismissRequest = { confirmDanger = false; pendingUri = null },
                title = { Text("确认开始恢复？") },
                text = {
                    Text(
                        "恢复会改写环境内文件且不可撤销；建议先关闭正在运行的会话再继续。" +
                            "Linux 环境未部署时会先自动部署（约 1 分钟）。",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val uri = pendingUri
                        confirmDanger = false
                        pendingUri = null
                        if (uri != null) startImport(uri)
                    }) { Text("我明白，开始恢复") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDanger = false; pendingUri = null }) { Text("取消") }
                },
            )
        }

        // 同名询问：覆盖/跳过 + 一键全部
        ask?.let { a ->
            AlertDialog(
                onDismissRequest = { answer(RootfsManager.ImportDecision.SKIP) },
                title = { Text("同名文件") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("导入包与当前环境都有这个文件，内容不同：")
                        Text(a.path, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            "后面还有 ${a.remaining} 个同名询问",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                applyAll = RootfsManager.ImportDecision.OVERWRITE
                                answer(RootfsManager.ImportDecision.OVERWRITE)
                            }) { Text("全部覆盖") }
                            TextButton(onClick = {
                                applyAll = RootfsManager.ImportDecision.SKIP
                                answer(RootfsManager.ImportDecision.SKIP)
                            }) { Text("全部跳过") }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { answer(RootfsManager.ImportDecision.OVERWRITE) }) { Text("覆盖") }
                },
                dismissButton = {
                    TextButton(onClick = { answer(RootfsManager.ImportDecision.SKIP) }) { Text("跳过") }
                },
            )
        }
    }
}

/** SAF 文档大小（查询失败给未知）。 */
private fun docSize(context: android.content.Context, uri: Uri): String {
    val size = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }.getOrNull()
    return size?.let { "${"%.1f".format(it / 1_000_000.0)} MB" } ?: "大小未知"
}
