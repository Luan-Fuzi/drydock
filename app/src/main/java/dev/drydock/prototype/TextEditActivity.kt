package dev.drydock.prototype

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 轻量文本编辑页（R3，D7 边界修订 2026-10-08）：小文本（纯文本且 ≤1MB）的
 * 查看+编辑+保存，单文件、等宽、无语法高亮——工程级编辑仍甩外部 App。
 * 入口：FilePane 点击文本文件（判定见 FilePane.isEditableText）。
 * 保存前 mtime 比对：文件在进入后被 agent/外部应用改过时先提示覆盖风险。
 */
class TextEditActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PATH = "path"
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val path = intent?.getStringExtra(EXTRA_PATH)
        val file = path?.let { File(it) }
        if (file == null || !file.isFile) {
            Toast.makeText(this, getString(R.string.editor_missing), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val loaded = readTextStrict(file)
        if (loaded == null) {
            // FilePane 预检放行后的漏网（并发修改等）：按二进制处理，退回外部应用
            Toast.makeText(this, getString(R.string.editor_not_text), Toast.LENGTH_SHORT).show()
            openExternalFallback(this, file)
            finish()
            return
        }
        setContent {
            DrydockTheme { EditorScreen(file, loaded) }
        }
    }

    /** UTF-8 严格解码：非法序列返回 null（按二进制处理），不做无损替换写回。 */
    private fun readTextStrict(f: File): String? = try {
        val bytes = f.readBytes()
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun EditorScreen(file: File, initialText: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initialText) }
    val mtimeAtEntry = remember { file.lastModified() }
    var overwriteAsk by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun doSave() {
        if (busy) return
        scope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) {
                runCatching { file.writeText(text) }
            }
            busy = false
            r.fold(
                {
                    Toast.makeText(context, context.getString(R.string.editor_saved), Toast.LENGTH_SHORT).show()
                    (context as? ComponentActivity)?.finish()
                },
                { Toast.makeText(context, context.getString(R.string.editor_save_fail, it.message ?: ""), Toast.LENGTH_SHORT).show() },
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            TextButton(onClick = { (context as? ComponentActivity)?.finish() }) { Text(stringResource(R.string.editor_cancel)) }
            Text(
                file.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
            )
            Button(enabled = !busy, onClick = {
                // 进入后文件被 agent/外部应用改过：先问，避免静默覆盖他人写入
                if (file.lastModified() != mtimeAtEntry) overwriteAsk = true else doSave()
            }, modifier = Modifier.testTag("editor_save")) { Text(if (busy) stringResource(R.string.ep_saving) else stringResource(R.string.session_dlg_save)) }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxSize(),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp),
        )
    }

    if (overwriteAsk) {
        AlertDialog(
            onDismissRequest = { overwriteAsk = false },
            title = { Text(stringResource(R.string.editor_overwrite_title)) },
            text = {
                Text(
                    stringResource(R.string.editor_overwrite_text, file.name),
                )
            },
            confirmButton = {
                TextButton(onClick = { overwriteAsk = false; doSave() }) { Text(stringResource(R.string.editor_overwrite_save)) }
            },
            dismissButton = {
                TextButton(onClick = { overwriteAsk = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}
