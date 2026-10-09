package dev.drydock.prototype

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier

/** 外观二级页：单选行（当前项加粗高亮语义沿用旧版）。 */
@Composable
internal fun AppearanceSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current

    SettingsSubPage("外观", onBack) {
        Text(
            "深浅主题即时生效（会话 / 文件 / 设置三栏）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        listOf(
            ThemeStore.Mode.SYSTEM to "跟随系统",
            ThemeStore.Mode.LIGHT to "浅色",
            ThemeStore.Mode.DARK to "深色",
        ).forEach { (m, label) ->
            // 写 ThemeStore.mode 即时重组（DrydockTheme 观察该状态），不 recreate——
            // 旧实现重建 Activity 重置底栏 tab，点外观直接跳回会话页
            val active = ThemeStore.mode.value == m
            Row(
                // tag=theme_system/theme_light/theme_dark（i18n 批 1 测试锚点）
                modifier = Modifier.fillMaxWidth().testTag("theme_" + m.name.lowercase())
                    .clickable { ThemeStore.save(context, m) },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                RadioButton(selected = active, onClick = { ThemeStore.save(context, m) })
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (active) FontWeight.Bold else null,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
