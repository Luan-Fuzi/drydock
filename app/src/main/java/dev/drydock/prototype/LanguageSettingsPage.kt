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
    // 当前选中：per-app 为空 = 跟随系统；否则取首个 tag（zh* → 中文，en* → English）
    val currentTags = if (Build.VERSION.SDK_INT >= 33) lm?.applicationLocales?.toLanguageTags().orEmpty() else ""
    val current = when {
        currentTags.isBlank() -> "system"
        currentTags.startsWith("zh") -> "zh"
        else -> "en"
    }

    fun pick(tag: String) {
        if (lm == null) {
            Toast.makeText(context, context.getString(R.string.lang_need_33), Toast.LENGTH_LONG).show()
            return
        }
        lm.applicationLocales = if (tag == "system") LocaleList.getEmptyLocaleList()
        else LocaleList.forLanguageTags(tag)
        // 平台随即广播配置变化，Activity 重建、资源切换——无需手动 recreate
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
            val active = current == key
            Row(
                modifier = Modifier.fillMaxWidth().testTag("lang_${key}").clickable { pick(tag) },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                RadioButton(selected = active, onClick = { pick(tag) })
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
