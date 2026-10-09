package dev.drydock.prototype

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight

/** 语言二级页（i18n 后续）：三选一——跟随系统 / 中文 / English。写入平台 per-app
 *  locale（LocaleManager，API 33+），与系统设置的「应用语言」同存储互相同步，
 *  系统广播配置变化、界面即时重建；选「跟随系统」= 清空 per-app 回落系统语言。
 *  minSdk 29 < 33 的设备不支持本 API：点选时如实提示走系统设置。 */
@Composable
internal fun LanguageSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val lm = if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(LocaleManager::class.java)
    } else null
    // 选中态本地即时反馈：选语言与系统语言相同时平台不触发配置变化（无 recreate），
    // 只读平台状态会表现为"点了没反应"（真机实锤）——点击先更新本地，再写平台存储
    val current = remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT < 33 || lm == null) "system"
            else when (val t = lm.applicationLocales.toLanguageTags()) {
                "" -> "system"
                else -> if (t.startsWith("zh")) "zh" else "en"
            },
        )
    }

    fun pick(tag: String) {
        if (lm == null) {
            Toast.makeText(context, context.getString(R.string.lang_need_33), Toast.LENGTH_LONG).show()
            return
        }
        // 先本地（单选立即可见），再平台（语言不同的场合随后 recreate 换资源）
        current.value = when (tag) {
            "system" -> "system"
            "zh-CN" -> "zh"
            else -> "en"
        }
        lm.applicationLocales = if (tag == "system") LocaleList.getEmptyLocaleList()
        else LocaleList.forLanguageTags(tag)
    }

    SettingsSubPage(stringResource(R.string.settings_language_title), onBack) {
        listOf(
            Triple("system", R.string.lang_follow_system, R.string.lang_follow_system_sum),
            Triple("zh-CN", R.string.lang_chinese, 0),
            Triple("en-US", R.string.lang_english, 0),
        ).forEach { (tag, labelRes, sumRes) ->
            val key = when (tag) {
                "system" -> "system"
                "zh-CN" -> "zh"
                else -> "en"
            }
            val active = current.value == key
            Row(
                modifier = Modifier.fillMaxWidth().testTag("lang_${key}").clickable { pick(tag) },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                RadioButton(selected = active, onClick = { pick(tag) }, modifier = Modifier.testTag("lang_rb_$key"))
                Text(
                    stringResource(labelRes),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (active) FontWeight.Bold else null,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                if (sumRes != 0) {
                    Text(
                        " · " + stringResource(sumRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
