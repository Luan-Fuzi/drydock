package dev.drydock.prototype

import android.content.Context
import android.os.Build
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/** 设置主页面：摘要当前值只在本组合读（when 分支切换销毁重建，返回时自然重读）。 */
@Composable
internal fun SettingsRoot(onOpen: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val endpoints = remember { EndpointStore.all(context) }
    val wizardDone = remember { EndpointStore.wizardDone(context) }
    // HomeActivity 自行处理语言变化（不 recreate）：含文案的缓存随配置失效
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val mirrorSummary = remember(config) { mirrorSummaryOf(context) }
    val bindOn = remember { BindStore.enabled(context) }
    val themeLabel = when (ThemeStore.mode.value) {
        ThemeStore.Mode.SYSTEM -> stringResource(R.string.settings_theme_system)
        ThemeStore.Mode.LIGHT -> stringResource(R.string.settings_theme_light)
        ThemeStore.Mode.DARK -> stringResource(R.string.settings_theme_dark)
    }
    val termSummary = stringResource(R.string.settings_term_summary, TermPrefs.fontSize(context), TermPrefs.scrollback(context))
    // per-app locale 读取在 remember 计算块外取 tag，标签映射用 stringResource（remember 内不可调）
    val langTags = remember(config) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales?.toLanguageTags().orEmpty()
        } else ""
    }
    val languageLabel = when {
        langTags.isBlank() -> stringResource(R.string.lang_follow_system)
        langTags.startsWith("zh") -> stringResource(R.string.lang_chinese)
        else -> stringResource(R.string.lang_english)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_page_title), style = MaterialTheme.typography.titleLarge)

        SettingsGroup(stringResource(R.string.settings_group_model)) {
            SettingsRow(
                stringResource(R.string.settings_row_endpoints),
                if (endpoints.isEmpty()) stringResource(R.string.settings_row_endpoints_empty)
                else stringResource(R.string.settings_row_endpoints_count, endpoints.size),
            ) { onOpen(SettingsPage.ENDPOINTS) }
            SettingsRow(
                stringResource(R.string.settings_row_wizard),
                if (wizardDone) stringResource(R.string.settings_row_wizard_done)
                else stringResource(R.string.settings_row_wizard_todo),
                divider = false,
                tag = "settings_wizard",
            ) { context.startActivity(Intent(context, WizardActivity::class.java)) }
        }

        SettingsGroup(stringResource(R.string.settings_group_env)) {
            SettingsRow(stringResource(R.string.settings_row_env_sh), stringResource(R.string.settings_row_env_sh_sum)) { onOpen(SettingsPage.ENV_SH) }
            SettingsRow(stringResource(R.string.settings_row_mirrors), mirrorSummary) { onOpen(SettingsPage.MIRRORS) }
            SettingsRow(stringResource(R.string.settings_row_backup), stringResource(R.string.settings_row_backup_sum)) { onOpen(SettingsPage.BACKUP) }
            SettingsRow(
                stringResource(R.string.settings_row_update),
                stringResource(R.string.settings_row_update_sum,
                    RootfsManager.deployedVersion(context).ifBlank { RootfsManifest.UBUNTU_VERSION }),
            ) { onOpen(SettingsPage.UPDATE) }
            SettingsRow(
                stringResource(R.string.settings_row_bind),
                stringResource(if (bindOn) R.string.settings_row_bind_sum_on else R.string.settings_row_bind_sum_off),
                divider = false,
                tag = "settings_bind",
            ) { onOpen(SettingsPage.BIND) }
        }

        SettingsGroup(stringResource(R.string.settings_group_app)) {
            SettingsRow(stringResource(R.string.settings_row_appearance), themeLabel, tag = "settings_appearance") { onOpen(SettingsPage.APPEARANCE) }
            SettingsRow(
                stringResource(R.string.settings_row_language), languageLabel,
                tag = "settings_language",
            ) { onOpen(SettingsPage.LANGUAGE) }
            SettingsRow(stringResource(R.string.settings_row_terminal), termSummary, divider = false) { onOpen(SettingsPage.TERMINAL) }
        }

        SettingsGroup(stringResource(R.string.settings_group_more)) {
            SettingsRow(stringResource(R.string.settings_row_dev), stringResource(R.string.settings_row_dev_sum), divider = false) { onOpen(SettingsPage.DEV) }
        }

        Text(
            stringResource(R.string.settings_footer,
                RootfsManager.deployedVersion(context).ifBlank { RootfsManifest.UBUNTU_VERSION }),
            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** ROOT 行摘要：读 ~/.drydock/mirrors 当前生效值 → 短名（GUI、手编、agent 改三者同源）。 */
private fun mirrorSummaryOf(context: Context): String {
    val txt = runCatching {
        File(RootfsManager.rootfsDir(context), "root/.drydock/mirrors").takeIf { it.exists() }?.readText()
    }.getOrNull().orEmpty()
    val apt = Regex("DRYDOCK_APT_MIRROR=(\\S+)").find(txt)?.groupValues?.get(1)?.let { mirrorHostOf(context, it) }
    val npm = Regex("DRYDOCK_NPM_REGISTRY=(\\S+)").find(txt)?.groupValues?.get(1)?.let { mirrorHostOf(context, it) }
    if (apt == null && npm == null) return context.getString(R.string.settings_mirror_default)
    return listOfNotNull(apt?.let { "APT $it" }, npm?.let { "npm $it" }).joinToString(" · ")
}

private fun mirrorHostOf(context: Context, url: String): String = when {
    url.contains("tuna") -> context.getString(R.string.settings_mirror_tuna)
    url.contains("ustc") -> context.getString(R.string.settings_mirror_ustc)
    url.contains("nju") -> context.getString(R.string.settings_mirror_nju)
    url.contains("aliyun") -> context.getString(R.string.settings_mirror_aliyun)
    url.contains("ports.ubuntu.com") -> context.getString(R.string.settings_mirror_ubuntu_official)
    url.contains("npmmirror") -> "npmmirror"
    url.contains("registry.npmjs.org") -> context.getString(R.string.settings_mirror_npm_official)
    else -> context.getString(R.string.settings_mirror_custom)
}
