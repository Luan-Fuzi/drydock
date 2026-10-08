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

    SettingsSubPage("镜像源", onBack) {
        Text(
            "仅影响安装下载速度；也可手编 ~/.drydock/mirrors 或让 agent 改，三者等价。",
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
        val aptOpts = remember(mirrorTxt) {
            buildList {
                add(MirrorOpt("default", "默认（国内镜像 + 官方自动回退）", null, null))
                add(MirrorOpt("tuna", "清华 TUNA", "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports", null))
                add(MirrorOpt("ustc", "中科大 USTC", "http://mirrors.ustc.edu.cn/ubuntu-ports", null))
                add(MirrorOpt("nju", "南京大学 NJU", "http://mirror.nju.edu.cn/ubuntu-ports", null))
                add(MirrorOpt("aliyun", "阿里云", "http://mirrors.aliyun.com/ubuntu-ports", null))
                add(MirrorOpt("official", "官方源（海外网络）", "http://ports.ubuntu.com/ubuntu-ports", null))
                if (curApt != null && none { it.aptUrl == curApt }) {
                    add(MirrorOpt("custom-apt", "当前手编：$curApt", curApt, null))
                }
            }
        }
        val npmOpts = remember(mirrorTxt) {
            buildList {
                add(MirrorOpt("npmmirror", "npmmirror（国内，默认）", null, "https://registry.npmmirror.com"))
                add(MirrorOpt("npmjs", "npm 官方源（海外）", null, "https://registry.npmjs.org"))
                if (curNpm != null && none { it.npmUrl == curNpm }) {
                    add(MirrorOpt("custom-npm", "当前手编：$curNpm", null, curNpm))
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
        Text("APT 源（系统包安装）", style = MaterialTheme.typography.titleSmall)
        aptOpts.forEach { o ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { aptChoice = o },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                RadioButton(selected = aptChoice == o, onClick = { aptChoice = o })
                Text(o.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text("npm 源（agent 运行时安装）", style = MaterialTheme.typography.titleSmall)
        npmOpts.forEach { o ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { npmChoice = o },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                RadioButton(selected = npmChoice == o, onClick = { npmChoice = o })
                Text(o.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        // 进度与结果独立成行：不挤进按钮文字（旧实现按钮被「应用中…/✓…」撑变形）
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
                        mirrorMsg = if (r.output.contains("MIRROR_RC=0")) "✓ 已生效（新安装走新源）" else "✗ ${r.output.takeLast(200)}"
                        tick++
                    }
                },
            ) { Text(if (mirrorApplying) "应用中…" else "应用镜像设置") }
            if (mirrorMsg.isNotBlank()) {
                Text(mirrorMsg, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            }
        }
    }
}
