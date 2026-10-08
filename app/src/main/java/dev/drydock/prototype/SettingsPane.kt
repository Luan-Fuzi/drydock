package dev.drydock.prototype

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 设置页（2026-10-07 重构）：主页面 = 分组卡片 + 一行一入口 + 当前值摘要，各区块的
 *  表单/编辑器/单选收进二级页（页内导航，系统返回键回主页面）；验收通道与诊断收进
 *  「开发者选项」。动机：旧版八区块平铺一页（摊大饼）、说明 11sp 正文 13sp 主次不清。 */
internal enum class SettingsPage {
    ROOT, ENDPOINTS, ENV_SH, MIRRORS, BACKUP, UPDATE, BIND, APPEARANCE, TERMINAL, DEV
}

@Composable
internal fun SettingsPane() {
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(SettingsPage.ROOT) }
    BackHandler(enabled = page != SettingsPage.ROOT) { page = SettingsPage.ROOT }
    when (page) {
        SettingsPage.ROOT -> SettingsRoot { page = it }
        SettingsPage.ENDPOINTS -> EndpointSettingsPage { page = SettingsPage.ROOT }
        SettingsPage.ENV_SH -> EnvShSettingsPage { page = SettingsPage.ROOT }
        SettingsPage.MIRRORS -> MirrorSettingsPage { page = SettingsPage.ROOT }
        SettingsPage.BACKUP -> BackupSettingsPage { page = SettingsPage.ROOT }
        SettingsPage.UPDATE -> UpdateSettingsPage { page = SettingsPage.ROOT }
        SettingsPage.BIND -> BindSettingsPage { page = SettingsPage.ROOT }
        SettingsPage.APPEARANCE -> AppearanceSettingsPage { page = SettingsPage.ROOT }
        SettingsPage.TERMINAL -> TerminalSettingsPage { page = SettingsPage.ROOT }
        SettingsPage.DEV -> DevSettingsPage { page = SettingsPage.ROOT }
    }
}
