package dev.drydock.prototype

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * D25 文件互通第二档（安卓 → 环境）：分享目标与 SAF 选择器把文件复制进
 * workspace 的 Inbox 目录（环境内路径 /root/Inbox）。
 */
object FileBridge {

    fun inboxDir(context: Context): File =
        File(RootfsManager.rootfsDir(context), "root/Inbox").apply { mkdirs() }

    fun displayName(context: Context, uri: Uri): String {
        var name: String? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) name = c.getString(0) }
        return name?.replace('/', '_')
            ?: uri.lastPathSegment?.substringAfterLast('/')?.replace('/', '_')
            ?: "shared-file"
    }

    /** 复制进 Inbox；返回宿主侧 File，失败为 null。EXTRA_TEXT 分享（无流）写为 txt。 */
    fun importUri(context: Context, uri: Uri): File? {
        return try {
            val name = displayName(context, uri)
            val dest = File(inboxDir(context), name)
            var ok = false
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
                ok = true
            }
            if (ok) dest else null
        } catch (_: Exception) {
            null
        }
    }

    fun importText(context: Context, text: String): File {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(java.util.Date())
        val dest = File(inboxDir(context), "shared-$stamp.txt")
        dest.writeText(text)
        return dest
    }
}
