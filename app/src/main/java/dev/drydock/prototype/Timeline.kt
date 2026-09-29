package dev.drydock.prototype

import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

/**
 * 存活遥测时间线（步骤 5，事件清单见 engineering-plan §宿主侧存活遥测）：
 * JSONL 追加写 filesDir/timeline.jsonl，本地 ring buffer——超限轮转保留一份 .old。
 * 多进程（ui / :env）同 uid 追加：单次 write 对 append 模式常规文件近似原子，
 * 轮转竞态最坏丢/重一行，原型期可接受。导出 = .old + 当前合并。
 */
object Timeline {

    private const val TAG = "DrydockTimeline"
    private const val MAX_BYTES = 512 * 1024
    private val lock = Any()

    fun file(context: Context) = File(context.filesDir, "timeline.jsonl")
    fun oldFile(context: Context) = File(context.filesDir, "timeline.jsonl.old")

    fun log(context: Context, type: String, data: Map<String, Any?> = emptyMap()) {
        try {
            val rec = JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("type", type)
                .put("pid", Process.myPid())
                .put(
                    "proc",
                    Application.getProcessName().substringAfterLast(':').ifEmpty { "ui" },
                )
            data.forEach { (k, v) -> rec.put(k, v ?: JSONObject.NULL) }
            val line = rec.toString() + "\n"
            synchronized(lock) {
                val f = file(context)
                if (f.length() > MAX_BYTES) {
                    oldFile(context).delete()
                    f.renameTo(oldFile(context))
                }
                FileOutputStream(f, true).use { it.write(line.toByteArray()) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "log 失败: $e")
        }
    }

    fun readAll(context: Context): List<String> {
        val out = mutableListOf<String>()
        oldFile(context).takeIf { it.exists() }?.forEachLine { out.add(it) }
        file(context).takeIf { it.exists() }?.forEachLine { out.add(it) }
        return out
    }

    fun sizeBytes(context: Context): Long =
        file(context).length() + (oldFile(context).takeIf { it.exists() }?.length() ?: 0L)
}
