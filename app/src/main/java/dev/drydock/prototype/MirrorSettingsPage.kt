package dev.drydock.prototype

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 镜像源二级页（= ~/.drydock/mirrors 的 GUI 编辑器）。 */
@Composable
internal fun MirrorSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }

    data class MirrorOpt(val id: String, val label: String, val aptUrl: String?, val npmUrl: String?)

    SettingsSubPage(stringResource(R.string.settings_row_mirrors), onBack) {
        Text(
            stringResource(R.string.mirror_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 单选回填当前生效值（读 ~/.drydock/mirrors——GUI、手编、agent 改三者同源）；
        // 列表外的手编源动态补一条，如实显示而非回落默认
        val mirrorTxt = remember(tick) {
            runCatching {
                File(RootfsManager.rootfsDir(context), "root/.drydock/mirrors")
                    .takeIf { it.exists() }?.readText()
            }.getOrNull().orEmpty()
        }
        val curApt = Regex("DRYDOCK_APT_MIRROR=(\\S+)").find(mirrorTxt)?.groupValues?.get(1)
        val curNpm = Regex("DRYDOCK_NPM_REGISTRY=(\\S+)").find(mirrorTxt)?.groupValues?.get(1)
        val optDefault = stringResource(R.string.mirror_opt_default)
        val optTuna = stringResource(R.string.settings_mirror_tuna)
        val optUstc = stringResource(R.string.mirror_opt_ustc)
        val optNju = stringResource(R.string.mirror_opt_nju)
        val optAliyun = stringResource(R.string.settings_mirror_aliyun)
        val optOfficial = stringResource(R.string.mirror_opt_official)
        val optCustom = stringResource(R.string.mirror_opt_custom)
        val optNpmmirror = stringResource(R.string.mirror_opt_npmmirror)
        val optNpmjs = stringResource(R.string.mirror_opt_npmjs)
        val aptOpts = remember(optDefault, optTuna, optUstc, optNju, optAliyun, optOfficial, optCustom, mirrorTxt) {
            buildList {
                add(MirrorOpt("default", optDefault, null, null))
                add(MirrorOpt("tuna", optTuna, "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports", null))
                add(MirrorOpt("ustc", optUstc, "http://mirrors.ustc.edu.cn/ubuntu-ports", null))
                add(MirrorOpt("nju", optNju, "http://mirror.nju.edu.cn/ubuntu-ports", null))
                add(MirrorOpt("aliyun", optAliyun, "http://mirrors.aliyun.com/ubuntu-ports", null))
                add(MirrorOpt("official", optOfficial, "http://ports.ubuntu.com/ubuntu-ports", null))
                if (curApt != null && none { it.aptUrl == curApt }) {
                    add(MirrorOpt("custom-apt", optCustom.format(curApt), curApt, null))
                }
            }
        }
        val npmOpts = remember(optNpmmirror, optNpmjs, optCustom, mirrorTxt) {
            buildList {
                add(MirrorOpt("npmmirror", optNpmmirror, null, "https://registry.npmmirror.com"))
                add(MirrorOpt("npmjs", optNpmjs, null, "https://registry.npmjs.org"))
                if (curNpm != null && none { it.npmUrl == curNpm }) {
                    add(MirrorOpt("custom-npm", optCustom.format(curNpm), null, curNpm))
                }
            }
        }
        var aptChoice by remember(mirrorTxt) {
            mutableStateOf(aptOpts.firstOrNull { it.aptUrl == curApt } ?: aptOpts.first())
        }
        var npmChoice by remember(mirrorTxt) {
            mutableStateOf(npmOpts.firstOrNull { it.npmUrl == curNpm } ?: npmOpts.first())
        }
        var mirrorApplying by remember { mutableStateOf(false) }
        var mirrorMsg by remember { mutableStateOf("") }
        Text(stringResource(R.string.mirror_apt_title), style = MaterialTheme.typography.titleSmall)
        aptOpts.forEach { o ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { aptChoice = o },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                RadioButton(selected = aptChoice == o, onClick = { aptChoice = o })
                Text(o.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(stringResource(R.string.mirror_npm_title), style = MaterialTheme.typography.titleSmall)
        npmOpts.forEach { o ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { npmChoice = o },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                RadioButton(selected = npmChoice == o, onClick = { npmChoice = o })
                Text(o.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        // 进度与结果独立成行：不挤进按钮文字（旧实现按钮被「应用中…/✓…」撑变形）；
        // busy 反馈走 BusyBar（R11），结果行照旧
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Button(
                enabled = !mirrorApplying && RootfsManager.isDeployed(context),
                onClick = {
                    mirrorApplying = true; mirrorMsg = ""
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            RecipeManager.applyMirrors(context.applicationContext, aptChoice.id, npmChoice.npmUrl)
                        }
                        mirrorApplying = false
                        mirrorMsg = if (r.output.contains("MIRROR_RC=0")) context.getString(R.string.mirror_applied) else "✗ ${r.output.takeLast(200)}"
                        tick++
                    }
                },
            ) { Text(stringResource(R.string.mirror_apply)) }
            if (mirrorMsg.isNotBlank()) {
                Text(mirrorMsg, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            }
        }
        if (mirrorApplying) BusyBar(stringResource(R.string.mirror_applying))
    }
}
