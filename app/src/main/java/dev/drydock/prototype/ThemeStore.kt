package dev.drydock.prototype

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/** 外观偏好（D27）：跟随系统 / 浅色 / 深色。组合状态驱动——切换写 mode 即时
 *  重组全树（原 recreate() 实现会重建 Activity，底栏 tab 随 remember 丢失，
 *  点外观跳回会话页）；落盘只为下次冷启恢复。 */
object ThemeStore {
    enum class Mode { SYSTEM, LIGHT, DARK }
    val mode = mutableStateOf(Mode.SYSTEM)

    fun init(context: Context) {
        mode.value = context.getSharedPreferences("drydock", Context.MODE_PRIVATE)
            .getString("theme_mode", null)?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.SYSTEM
    }

    fun save(context: Context, m: Mode) {
        context.getSharedPreferences("drydock", Context.MODE_PRIVATE)
            .edit().putString("theme_mode", m.name).apply()
        mode.value = m
    }
}

/** 全 app 主题入口：按 ThemeStore 切换（会话/文件/设置三栏与向导页生效，向导
 *  2026-10-07 起跟随主题不再锁深色；救援页维持深色）。 */
@Composable
fun DrydockTheme(content: @Composable () -> Unit) {
    val dark = when (ThemeStore.mode.value) {
        ThemeStore.Mode.DARK -> true
        ThemeStore.Mode.LIGHT -> false
        ThemeStore.Mode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()) {
        // Modifier.testTag 映射进 uiautomator 的 resource-id（i18n 批 1：测试定位
        // 语言无关；属性挂在根 Surface 对全树生效。TerminalActivity 是 View 体系，
        // 走 res/values/ids.xml 的 setId 同目）
        Surface(modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) { content() }
    }
}
