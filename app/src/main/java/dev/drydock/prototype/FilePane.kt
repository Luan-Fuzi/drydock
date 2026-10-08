package dev.drydock.prototype

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.Modifier

/** 文件页：Linux 目录树浏览（有界，D7：管理甩给系统）。文件经 WorkspaceProvider 打开。 */
@Composable
internal fun FilePane() {
    val context = LocalContext.current
    val root = remember { File(RootfsManager.rootfsDir(context), "root") }
    var relPath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    val dir = File(root, relPath)

    androidx.compose.runtime.LaunchedEffect(relPath) {
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
            "这些文件同时出现在手机自带文件管理器（Drydock workspace）；点文件用系统应用打开。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (relPath.isNotBlank()) {
            TextButton(onClick = { relPath = relPath.substringBeforeLast('/') }) { Text("← 上一级") }
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
                            .clickable {
                                if (f.isDirectory) relPath = "$relPath/${f.name}"
                                else openExternal(context, root, f)
                            }
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
}

/** 经 DocumentsProvider 的 content URI 甩系统应用打开（D7：编辑/查看交给外部 App）。 */
private fun openExternal(context: Context, root: File, f: File) {
    val docId = f.absolutePath.removePrefix(root.absolutePath).ifBlank { "/" }
    val uri = android.provider.DocumentsContract.buildDocumentUri(WorkspaceProvider.AUTHORITY, docId)
    val mime = android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (_: Exception) {
        android.widget.Toast.makeText(context, "没有应用能打开 ${f.name}（可先在系统文件管理器里试）", android.widget.Toast.LENGTH_SHORT).show()
    }
}
