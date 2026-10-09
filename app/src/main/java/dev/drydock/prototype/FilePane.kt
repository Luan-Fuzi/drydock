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
        Text("文件", style = MaterialTheme.typography.titleLarge)
        Text(
            "环境内路径 ${if (relPath.isBlank()) "/" else relPath}",
            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "这些文件同时出现在手机自带文件管理器（Drydock workspace）；点文本文件就地编辑，" +
                "点其他文件用系统应用打开；长按文件有更多操作。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (relPath.isNotBlank()) {
            TextButton(onClick = { relPath = relPath.substringBeforeLast('/') }) { Text("← 上一级") }
        }
        if (exportBusy.isNotBlank()) {
            Text(exportBusy, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        if (!RootfsManager.isDeployed(context)) {
            Text("环境未部署——先在「设置 → 初始设置」完成配置。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    }, modifier = Modifier.testTag("file_open_with")) { Text("用其他应用打开") }
                    TextButton(onClick = {
                        menuTarget = null
                        if (exportBusy.isNotBlank()) return@TextButton
                        exportBusy = "导出 ${f.name} 到 Downloads/Drydock…"
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching { Landing.toDownloads(context.applicationContext, f).toString() }
                            }
                            exportBusy = ""
                            r.fold(
                                { Toast.makeText(context, "✓ 已导出到 Downloads/Drydock", Toast.LENGTH_SHORT).show() },
                                { Toast.makeText(context, "✗ 导出失败：${it.message}", Toast.LENGTH_SHORT).show() },
                            )
                        }
                    }, modifier = Modifier.testTag("file_export")) { Text("导出到 Downloads") }
                    TextButton(onClick = { menuTarget = null; renameTarget = f }, modifier = Modifier.testTag("file_rename")) { Text("重命名") }
                    TextButton(onClick = { menuTarget = null; deleteTarget = f }, modifier = Modifier.testTag("file_delete")) { Text("删除") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { menuTarget = null }) { Text("取消") } },
        )
    }

    renameTarget?.let { f ->
        var nameInput by remember(f) { mutableStateOf(f.name) }
        val legal = nameInput.isNotBlank() && !nameInput.contains('/') && File(dir, nameInput).let { !it.exists() || it == f }
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("文件名") },
                        singleLine = true,
                        isError = !legal,
                        modifier = Modifier.testTag("dlg_rename_name"),
                    )
                    if (!legal && nameInput.isNotBlank()) {
                        Text("名字为空、含 / 或与现有文件重名", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = legal, onClick = {
                    if (f.renameTo(File(dir, nameInput))) {
                        renameTarget = null
                        tick++
                    } else {
                        Toast.makeText(context, "重命名失败", Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.testTag("dlg_rename_ok")) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    deleteTarget?.let { f ->
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除「${f.name}」？") },
            text = { Text("删除后不可恢复（环境内与系统文件管理器同步消失）。") },
            confirmButton = {
                TextButton(onClick = {
                    val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
                    deleteTarget = null
                    if (ok) tick++ else Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.testTag("dlg_delete_ok")) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
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
        context.startActivity(viewIntent(root, f))
    } catch (_: Exception) {
        Toast.makeText(context, "没有应用能打开 ${f.name}（可先在系统文件管理器里试）", Toast.LENGTH_SHORT).show()
    }
}

/** R2 菜单项：chooser 显式列出候选应用（满足「调用其他编辑器」的可选择性）。 */
private fun openExternalChooser(context: Context, root: File, f: File) {
    try {
        context.startActivity(Intent.createChooser(viewIntent(root, f), "用哪个应用打开 ${f.name}？"))
    } catch (_: Exception) {
        Toast.makeText(context, "没有应用能打开 ${f.name}（可先在系统文件管理器里试）", Toast.LENGTH_SHORT).show()
    }
}

private fun viewIntent(root: File, f: File): Intent {
    val docId = f.absolutePath.removePrefix(root.absolutePath).ifBlank { "/" }
    val uri = android.provider.DocumentsContract.buildDocumentUri(WorkspaceProvider.AUTHORITY, docId)
    val mime = android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
    return Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
