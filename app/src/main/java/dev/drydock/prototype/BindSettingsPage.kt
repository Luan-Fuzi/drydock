package dev.drydock.prototype

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 目录直通绑定二级页（D28-3 最小版）。开关定位锚点是 bind_switch（i18n 批 1
 *  resource-id 化），状态/授权文案不再是测试锚点。 */
@Composable
internal fun BindSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }

    SettingsSubPage(stringResource(R.string.bind_title), onBack) {
        Text(
            stringResource(R.string.bind_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val bindOn = remember(tick) { BindStore.enabled(context) }
        val permOk = remember(tick) { android.os.Environment.isExternalStorageManager() }
        Text(
            if (permOk) stringResource(R.string.bind_perm_ok) else stringResource(R.string.bind_perm_missing),
            fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            color = if (permOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(
                checked = bindOn,
                onCheckedChange = { on ->
                    if (on && !permOk) {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                    } else {
                        BindStore.setEnabled(context, on); tick++
                    }
                },
                modifier = Modifier.testTag("bind_switch"),
            )
            Text(
                if (bindOn) stringResource(R.string.bind_state_on) else stringResource(R.string.bind_state_off),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
