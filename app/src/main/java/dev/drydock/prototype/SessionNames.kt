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

    // 旧版把中文默认名直接落盘（「主终端」「会话 N」）；读取时按默认名处理，随语言显示
    private val LEGACY_N = Regex("^会话 (\\d+)$")

    /** 默认名不落盘，按当前语言现取：main →「主终端/Main terminal」，sN →「会话/Session N+1」。 */
    fun defaultName(context: Context, name: String): String =
        if (name == TerminalManager.MAIN) context.getString(R.string.session_default_main)
        else name.removePrefix("s").toIntOrNull()
            ?.let { context.getString(R.string.session_default_n, it + 1) } ?: name

    /** 由落盘值解析显示名：缺省或旧版中文默认名 → 当前语言的默认名，其余为用户自定义名。 */
    fun resolve(context: Context, name: String, stored: String?): String {
        if (stored == null || (name == TerminalManager.MAIN && stored == "主终端")) {
            return defaultName(context, name)
        }
        LEGACY_N.matchEntire(stored)?.let { m ->
            return context.getString(R.string.session_default_n, m.groupValues[1].toInt())
        }
        return stored
    }

    fun get(context: Context, name: String): String = resolve(context, name, load(context)[name])

    /** 写显示名；回落默认名（空串/与 technical 同名/等于默认名）即删条目，默认名不落盘。 */
    fun set(context: Context, name: String, display: String) {
        val m = load(context)
        val t = display.trim()
        if (t.isBlank() || t == name || t == defaultName(context, name) || resolve(context, name, t) != t) {
            m.remove(name)
        } else {
            m[name] = t
        }
        save(context, m)
    }
}
