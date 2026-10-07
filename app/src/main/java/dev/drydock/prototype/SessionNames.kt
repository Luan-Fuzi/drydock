package dev.drydock.prototype

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * 会话显示名（technical 名 → 用户命名），存 terminal-session-names.json。
 * 与注册表（terminal-sessions.json）分离：dtach socket 名是进程身份不可改，
 * 显示名独立成层——main 关闭重建后名字保留；孤儿条目无害，展示以注册表为准。
 */
object SessionNames {

    private fun file(context: Context) = File(context.filesDir, "terminal-session-names.json")

    fun load(context: Context): MutableMap<String, String> = try {
        val txt = file(context).takeIf { it.exists() }?.readText()
        if (txt.isNullOrBlank()) {
            mutableMapOf()
        } else {
            val o = JSONObject(txt)
            mutableMapOf<String, String>().apply { o.keys().forEach { k -> put(k, o.getString(k)) } }
        }
    } catch (_: Exception) {
        mutableMapOf()
    }

    private fun save(context: Context, map: Map<String, String>) {
        runCatching { file(context).writeText(JSONObject(map).toString()) }
    }

    /** 显示名：main 缺省「主终端」，其余缺省 technical 名。 */
    fun get(context: Context, name: String): String =
        load(context)[name] ?: if (name == TerminalManager.MAIN) "主终端" else name

    /** 写显示名；回落默认名（空串/与 technical 同名/main 的「主终端」）即删条目。 */
    fun set(context: Context, name: String, display: String) {
        val m = load(context)
        val t = display.trim()
        if (t.isBlank() || t == name || (name == TerminalManager.MAIN && t == "主终端")) {
            m.remove(name)
        } else {
            m[name] = t
        }
        save(context, m)
    }
}
