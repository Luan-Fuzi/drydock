package dev.drydock.prototype

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** 终端二级页：字号/回滚行数落 TermPrefs；TerminalActivity onResume 经
 *  window.__dk.applyCfg 推给页面 xterm（含正在开的终端），ttyd 侧不感知。 */
@Composable
internal fun TerminalSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current

    SettingsSubPage("终端显示", onBack) {
        var termFont by remember { mutableStateOf(TermPrefs.fontSize(context)) }
        var termRows by remember { mutableStateOf(TermPrefs.scrollback(context)) }
        Text("字号 ${termFont}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = termFont.toFloat(),
            onValueChange = { termFont = it.toInt().coerceIn(10, 24) },
            valueRange = 10f..24f,
            steps = 13,
            onValueChangeFinished = { TermPrefs.set(context, termFont, termRows) },
        )
        Text("回滚行数 ${termRows}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "可往上翻看的历史行数上限",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = termRows.toFloat(),
            onValueChange = { termRows = (it.toInt() / 100) * 100 },
            valueRange = 200f..10_000f,
            onValueChangeFinished = { TermPrefs.set(context, termFont, termRows) },
        )
        Text(
            "改动即保存，回到终端页生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
