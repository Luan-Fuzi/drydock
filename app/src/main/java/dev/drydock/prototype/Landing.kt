package dev.drydock.prototype

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * 成果落袋（I4/D7）：环境内产物由宿主单向复制到 MediaStore Downloads/Drydock，
 * 不做通用文件管理器，不对该目录内容执行或回读解析。API 29+ 自有插入免权限。
 */
object Landing {

    fun toDownloads(context: Context, src: File): Uri {
        val mime = when (src.extension.lowercase()) {
            "md", "txt", "jsonl" -> "text/markdown"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, src.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Drydock")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert 返回 null")
        // 流式拷贝：环境导出 tar 可达几十 MB，readBytes 全量进内存会顶低端机上限
        resolver.openOutputStream(uri)!!.use { out ->
            src.inputStream().use { it.copyTo(out, 1 shl 16) }
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
