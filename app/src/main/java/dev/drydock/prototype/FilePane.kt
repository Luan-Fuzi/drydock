package dev.drydock.prototype

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.ui.Modifier

/** 文件页：Linux 目录树浏览（有界，D7：管理甩给系统）。文件经 WorkspaceProvider 打开。
 *  R3（D7 边界修订 2026-10-08）：小文本文件（纯文本且 ≤1MB）点击进内置编辑页；
 *  R2：行长按弹菜单——用其他应用打开 / 导出到 Downloads / 重命名 / 删除。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FilePane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = remember { File(RootfsManager.rootfsDir(context), "root") }
    var relPath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    // 重命名/删除/导出后手动 +1 触发重列（LaunchedEffect 键含 tick）
    var tick by remember { mutableStateOf(0) }
    var menuTarget by remember { mutableStateOf<File?>(null) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }
    var exportBusy by remember { mutableStateOf("") }
    val dir = File(root, relPath)

    androidx.compose.runtime.LaunchedEffect(relPath, tick) {
        entries = withContext(Dispatchers.IO) {
            dir.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }) ?: emptyList()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.file_page_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.file_cwd_line, if (relPath.isBlank()) "/" else relPath),
            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.file_intro),
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (relPath.isNotBlank()) {
            TextButton(onClick = { relPath = relPath.substringBeforeLast('/') }) { Text(stringResource(R.string.file_up)) }
        }
        if (exportBusy.isNotBlank()) {
            Text(exportBusy, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        if (!RootfsManager.isDeployed(context)) {
            Text(stringResource(R.string.file_not_deployed), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn {
                items(entries) { f ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (f.isDirectory) relPath = "$relPath/${f.name}"
                                    else if (isEditableText(f)) {
                                        context.startActivity(
                                            Intent(context, TextEditActivity::class.java)
                                                .putExtra(TextEditActivity.EXTRA_PATH, f.absolutePath))
                                    } else openExternal(context, root, f)
                                },
                                onLongClick = { if (!f.isDirectory) menuTarget = f },
                            )
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    ) {
                        Text(if (f.isDirectory) "📁" else "📄", modifier = Modifier.padding(end = 10.dp))
                        Column {
                            Text(f.name, fontSize = 14.sp)
                            if (!f.isDirectory) {
                                Text("${f.length() / 1024} KB", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    // R2 长按菜单（纵向四项；目录不支持长按）
    menuTarget?.let { f ->
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { menuTarget = null },
            title = { Text(f.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    TextButton(onClick = {
                        menuTarget = null
                        openExternalChooser(context, root, f)
                    }, modifier = Modifier.testTag("file_open_with")) { Text(stringResource(R.string.file_open_with)) }
                    TextButton(onClick = {
                        menuTarget = null
                        if (exportBusy.isNotBlank()) return@TextButton
                        exportBusy = context.getString(R.string.file_export_busy, f.name)
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching { Landing.toDownloads(context.applicationContext, f).toString() }
                            }
                            exportBusy = ""
                            r.fold(
                                { Toast.makeText(context, context.getString(R.string.file_export_ok), Toast.LENGTH_SHORT).show() },
                                { Toast.makeText(context, context.getString(R.string.file_export_fail, it.message ?: ""), Toast.LENGTH_SHORT).show() },
                            )
                        }
                    }, modifier = Modifier.testTag("file_export")) { Text(stringResource(R.string.file_export)) }
                    TextButton(onClick = { menuTarget = null; renameTarget = f }, modifier = Modifier.testTag("file_rename")) { Text(stringResource(R.string.file_rename)) }
                    TextButton(onClick = { menuTarget = null; deleteTarget = f }, modifier = Modifier.testTag("file_delete")) { Text(stringResource(R.string.file_delete)) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { menuTarget = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    renameTarget?.let { f ->
        var nameInput by remember(f) { mutableStateOf(f.name) }
        val legal = nameInput.isNotBlank() && !nameInput.contains('/') && File(dir, nameInput).let { !it.exists() || it == f }
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.file_rename)) },
            text = {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text(stringResource(R.string.file_dlg_name_label)) },
                        singleLine = true,
                        isError = !legal,
                        modifier = Modifier.testTag("dlg_rename_name"),
                    )
                    if (!legal && nameInput.isNotBlank()) {
                        Text(stringResource(R.string.file_dlg_name_invalid), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = legal, onClick = {
                    if (f.renameTo(File(dir, nameInput))) {
                        renameTarget = null
                        tick++
                    } else {
                        Toast.makeText(context, context.getString(R.string.file_rename_fail), Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.testTag("dlg_rename_ok")) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    deleteTarget?.let { f ->
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.file_dlg_delete_title, f.name)) },
            text = { Text(stringResource(R.string.file_dlg_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
                    deleteTarget = null
                    if (ok) tick++ else Toast.makeText(context, context.getString(R.string.file_delete_fail), Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.testTag("dlg_delete_ok")) { Text(stringResource(R.string.file_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** R3 编辑判定：≤1MB 且首 8KB 无 NUL 字节（二进制探测比扩展名表可靠——环境内
 *  大量无扩展名配置文件如 mirrors/env.sh）。0 字节视为可编辑空文件。 */
private const val EDIT_MAX_BYTES = 1L shl 20

internal fun isEditableText(f: File): Boolean {
    if (f.length() > EDIT_MAX_BYTES) return false
    return try {
        f.inputStream().use { ins ->
            val buf = ByteArray(8192)
            val n = ins.read(buf)
            var i = 0
            while (i < n) {
                if (buf[i] == 0.toByte()) return false
                i++
            }
            true
        }
    } catch (_: Exception) {
        false
    }
}

/** 编辑页兜底：无 root 上下文时自建 root（TextEditActivity 退回外部应用用）。 */
internal fun openExternalFallback(context: Context, f: File) {
    openExternal(context, File(RootfsManager.rootfsDir(context), "root"), f)
}

/** 经 DocumentsProvider 的 content URI 甩系统应用打开（D7：编辑/查看交给外部 App）。 */
private fun openExternal(context: Context, root: File, f: File) {
    try {
        context.startActivity(viewIntent(context, root, f))
    } catch (_: Exception) {
        Toast.makeText(context, context.getString(R.string.file_no_app, f.name), Toast.LENGTH_SHORT).show()
    }
}

/** R2 菜单项：chooser 显式列出候选应用（满足「调用其他编辑器」的可选择性）。 */
private fun openExternalChooser(context: Context, root: File, f: File) {
    try {
        context.startActivity(Intent.createChooser(viewIntent(context, root, f), context.getString(R.string.file_chooser_title, f.name)))
    } catch (_: Exception) {
        Toast.makeText(context, context.getString(R.string.file_no_app, f.name), Toast.LENGTH_SHORT).show()
    }
}

private fun viewIntent(context: android.content.Context, root: File, f: File): Intent {
    val docId = f.absolutePath.removePrefix(root.absolutePath).ifBlank { "/" }
    val uri = android.provider.DocumentsContract.buildDocumentUri(WorkspaceProvider.authority(context), docId)
    val mime = android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
    return Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
