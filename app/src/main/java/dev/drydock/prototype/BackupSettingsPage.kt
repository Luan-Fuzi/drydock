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
import androidx.compose.ui.res.stringResource
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
        importBusy = context.getString(R.string.backup_busy_read)
        importMsg = ""
        applyAll = null
        scope.launch {
            try {
                val appCtx = context.applicationContext
                if (!RootfsManager.isDeployed(appCtx)) {
                    importBusy = context.getString(R.string.session_busy_deploy)
                    withContext(Dispatchers.IO) { RootfsManager.deploy(appCtx) { } }
                    if (!RootfsManager.isDeployed(appCtx)) {
                        importMsg = context.getString(R.string.backup_deploy_fail)
                        importBusy = ""
                        return@launch
                    }
                }
                val r = RootfsManager.importEnvTar(
                    appCtx, uri,
                    onState = { s ->
                        importBusy = when (s) {
                            is RootfsManager.ImportState.Copying -> context.getString(R.string.backup_busy_read)
                            is RootfsManager.ImportState.Scanning -> context.getString(R.string.backup_import_scanning, s.entries)
                            is RootfsManager.ImportState.Merging -> context.getString(R.string.backup_import_merging, s.done, s.total)
                        }
                    },
                    onConflict = { path, remaining ->
                        applyAll ?: suspendCancellableCoroutine { cont ->
                            ask = ConflictAsk(path, remaining, cont)
                        }
                    },
                )
                importMsg = context.getString(R.string.backup_import_ok, r.imported, r.skipped, r.failed) +
                    if (r.failed > 0) context.getString(R.string.backup_import_ok_sample, r.failedSample.firstOrNull() ?: "") else ""
            } catch (e: RootfsManager.ImportReject) {
                importMsg = context.getString(R.string.backup_import_reject, e.message ?: "")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                importMsg = context.getString(R.string.backup_import_fail, e.message ?: "")
            }
            importBusy = ""
        }
    }

    SettingsSubPage(stringResource(R.string.settings_row_backup), onBack) {
        Button(
            enabled = !exporting && importBusy.isBlank() && RootfsManager.isDeployed(context),
            onClick = {
                exporting = true; exportMsg = ""
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching { RootfsManager.exportEnvTar(context.applicationContext) }
                    }
                    exporting = false
                    exportMsg = r.fold({ context.getString(R.string.backup_export_ok, it) }, { context.getString(R.string.backup_export_fail, it.message ?: "") })
                }
            },
        ) { Text(stringResource(R.string.backup_export_btn)) }
        // busy 反馈走 BusyBar（R11）：按钮文字保持固定，不塞进度文字
        if (exporting) BusyBar(stringResource(R.string.backup_exporting))
        if (exportMsg.isNotBlank()) Text(exportMsg, fontSize = 12.sp, fontFamily = FontFamily.Monospace)

        // ---- 从 tar.gz 恢复（R4）----
        Button(
            enabled = importBusy.isBlank() && !exporting,
            onClick = { picker.launch(arrayOf("*/*")) },
        ) { Text(stringResource(R.string.backup_restore_btn)) }
        if (importBusy.isNotBlank()) BusyBar(importBusy)
        if (importMsg.isNotBlank()) Text(importMsg, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(
            stringResource(R.string.backup_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 确认①：说明 + 密钥随 env.sh 进入环境
        pendingUri?.let { uri ->
            val name = remember(uri) { FileBridge.displayName(context, uri) }
            val size = remember(uri) { docSize(context, uri) }
            AlertDialog(
                onDismissRequest = { pendingUri = null },
                title = { Text(stringResource(R.string.backup_dlg1_title)) },
                text = {
                    Text(
                        stringResource(R.string.backup_dlg1_text, name, size),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirmDanger = true }) { Text(stringResource(R.string.backup_dlg1_continue)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingUri = null }) { Text(stringResource(R.string.common_cancel)) }
                },
            )
        }

        // 确认②：危险操作二次确认
        if (confirmDanger) {
            AlertDialog(
                onDismissRequest = { confirmDanger = false; pendingUri = null },
                title = { Text(stringResource(R.string.backup_dlg2_title)) },
                text = {
                    Text(
                        stringResource(R.string.backup_dlg2_text),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val uri = pendingUri
                        confirmDanger = false
                        pendingUri = null
                        if (uri != null) startImport(uri)
                    }) { Text(stringResource(R.string.backup_dlg2_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDanger = false; pendingUri = null }) { Text(stringResource(R.string.common_cancel)) }
                },
            )
        }

        // 同名询问：覆盖/跳过 + 一键全部
        ask?.let { a ->
            AlertDialog(
                onDismissRequest = { answer(RootfsManager.ImportDecision.SKIP) },
                title = { Text(stringResource(R.string.backup_conflict_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.backup_conflict_desc))
                        Text(a.path, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            stringResource(R.string.backup_conflict_more, a.remaining),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                applyAll = RootfsManager.ImportDecision.OVERWRITE
                                answer(RootfsManager.ImportDecision.OVERWRITE)
                            }) { Text(stringResource(R.string.backup_conflict_all_overwrite)) }
                            TextButton(onClick = {
                                applyAll = RootfsManager.ImportDecision.SKIP
                                answer(RootfsManager.ImportDecision.SKIP)
                            }) { Text(stringResource(R.string.backup_conflict_all_skip)) }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { answer(RootfsManager.ImportDecision.OVERWRITE) }) { Text(stringResource(R.string.backup_conflict_overwrite)) }
                },
                dismissButton = {
                    TextButton(onClick = { answer(RootfsManager.ImportDecision.SKIP) }) { Text(stringResource(R.string.backup_conflict_skip)) }
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
    return size?.let { "${"%.1f".format(it / 1_000_000.0)} MB" } ?: context.getString(R.string.backup_size_unknown)
}
