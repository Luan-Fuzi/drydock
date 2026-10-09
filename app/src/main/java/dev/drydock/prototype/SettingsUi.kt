package dev.drydock.prototype

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier

/** 设置行：标题 + 当前值摘要 + chevron；divider = 组内非末行画分隔线。
 *  tag = uiautomator 锚点（i18n 批 1：测试定位走 resource-id，不依赖显示文案）。 */
@Composable
internal fun SettingsRow(title: String, summary: String, divider: Boolean = true, tag: String? = null, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth()
                .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (summary.isNotBlank()) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (divider) HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** 分组卡片：组标签（primary 色）+ Card 容器。 */
@Composable
internal fun SettingsGroup(label: String, content: @Composable () -> Unit) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
        )
        Card(Modifier.fillMaxWidth()) { Column { content() } }
    }
}

/** 二级页骨架：「‹ 设置」返回 + 页标题 + 内容（整页可滚）。 */
@Composable
internal fun SettingsSubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            TextButton(
                onClick = onBack,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            ) { Text("‹ 设置") }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        content()
    }
}
