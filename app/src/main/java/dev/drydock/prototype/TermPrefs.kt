package dev.drydock.prototype

import android.content.Context

/**
 * 终端显示偏好（字号 / 回滚行数）。值经 TerminalActivity 注入页面
 * （window.__dk.applyCfg，overlay 落地到 xterm options）；ttyd 侧不感知。
 */
object TermPrefs {

    const val DEFAULT_FONT = 14
    const val DEFAULT_SCROLLBACK = 2000

    private fun p(context: Context) = context.getSharedPreferences("drydock", Context.MODE_PRIVATE)

    fun fontSize(context: Context): Int = p(context).getInt("term_font", DEFAULT_FONT).coerceIn(8, 32)

    fun scrollback(context: Context): Int =
        p(context).getInt("term_scrollback", DEFAULT_SCROLLBACK).coerceIn(100, 50_000)

    fun set(context: Context, font: Int, scrollback: Int) {
        p(context).edit()
            .putInt("term_font", font.coerceIn(8, 32))
            .putInt("term_scrollback", scrollback.coerceIn(100, 50_000))
            .apply()
    }
}
