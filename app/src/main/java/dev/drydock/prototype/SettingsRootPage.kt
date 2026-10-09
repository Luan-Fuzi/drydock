package dev.drydock.prototype

import android.content.Context
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

/** 设置主页面：摘要当前值只在本组合读（when 分支切换销毁重建，返回时自然重读）。 */
@Composable
internal fun SettingsRoot(onOpen: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val endpoints = remember { EndpointStore.all(context) }
    val wizardDone = remember { EndpointStore.wizardDone(context) }
    val mirrorSummary = remember { mirrorSummaryOf(context) }
    val bindOn = remember { BindStore.enabled(context) }
    val themeLabel = when (ThemeStore.mode.value) {
        ThemeStore.Mode.SYSTEM -> "跟随系统"
        ThemeStore.Mode.LIGHT -> "浅色"
        ThemeStore.Mode.DARK -> "深色"
    }
    val termSummary = "字号 ${TermPrefs.fontSize(context)} · 回滚 ${TermPrefs.scrollback(context)} 行"

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge)

        SettingsGroup("模型与端点") {
            SettingsRow(
                "Coding 端点",
                if (endpoints.isEmpty()) "未添加（内置厂商自动识别）" else "${endpoints.size} 个自定义端点",
            ) { onOpen(SettingsPage.ENDPOINTS) }
            SettingsRow(
                "初始设置向导",
                if (wizardDone) "已完成 · 可重新运行" else "保活 / 连接大模型 / 安装 agent",
                divider = false,
                tag = "settings_wizard",
            ) { context.startActivity(Intent(context, WizardActivity::class.java)) }
        }

        SettingsGroup("环境") {
            SettingsRow("环境变量", "~/.drydock/env.sh（新会话生效）") { onOpen(SettingsPage.ENV_SH) }
            SettingsRow("镜像源", mirrorSummary) { onOpen(SettingsPage.MIRRORS) }
            SettingsRow("备份与导出", "导出 → Downloads/Drydock · 从 tar.gz 恢复") { onOpen(SettingsPage.BACKUP) }
            SettingsRow(
                "系统更新",
                "Ubuntu ${RootfsManager.deployedVersion(context).ifBlank { RootfsManifest.UBUNTU_VERSION }} · 检查更新与回滚",
            ) { onOpen(SettingsPage.UPDATE) }
            SettingsRow(
                "目录直通绑定",
                "实验 · " + if (bindOn) "已开启" else "已关闭",
                divider = false,
                tag = "settings_bind",
            ) { onOpen(SettingsPage.BIND) }
        }

        SettingsGroup("应用") {
            SettingsRow("外观", themeLabel, tag = "settings_appearance") { onOpen(SettingsPage.APPEARANCE) }
            SettingsRow("终端显示", termSummary, divider = false) { onOpen(SettingsPage.TERMINAL) }
        }

        SettingsGroup("更多") {
            SettingsRow("开发者选项", "验收通道、时间线导出与版本详情", divider = false) { onOpen(SettingsPage.DEV) }
        }

        Text(
            "Drydock 原型（从 main tag 构建）· Ubuntu " +
                "${RootfsManager.deployedVersion(context).ifBlank { RootfsManifest.UBUNTU_VERSION }}\n" +
                "⚠ 卸载或清除应用数据会连同 Linux 环境一起删除——删除前先用「备份与导出」备份。",
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
    val apt = Regex("DRYDOCK_APT_MIRROR=(\\S+)").find(txt)?.groupValues?.get(1)?.let(::mirrorHostOf)
    val npm = Regex("DRYDOCK_NPM_REGISTRY=(\\S+)").find(txt)?.groupValues?.get(1)?.let(::mirrorHostOf)
    if (apt == null && npm == null) return "默认（国内镜像 + 官方回退）"
    return listOfNotNull(apt?.let { "APT $it" }, npm?.let { "npm $it" }).joinToString(" · ")
}

private fun mirrorHostOf(url: String): String = when {
    url.contains("tuna") -> "清华 TUNA"
    url.contains("ustc") -> "中科大"
    url.contains("nju") -> "南大"
    url.contains("aliyun") -> "阿里云"
    url.contains("ports.ubuntu.com") -> "官方源"
    url.contains("npmmirror") -> "npmmirror"
    url.contains("registry.npmjs.org") -> "npm 官方"
    else -> "自定义"
}
