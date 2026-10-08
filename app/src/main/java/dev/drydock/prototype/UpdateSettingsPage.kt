package dev.drydock.prototype

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 系统更新二级页（R8，D26 旁路升级 + D33 迁移细则）：检查更新 → 升级（BusyBar
 * 全程阶段进度）→ 结果报告（迁移计数 + 自装包差集 + 一键重装命令）；上一版保留
 * 一份，一键回滚（反向迁移）。升级/回滚都会先停全部会话、切换后按注册表重建
 * （dtach 无 server，shell 内容不保留，与正常会话死亡重建同语义）。
 */
@Composable
internal fun UpdateSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<RootfsManager.UpgradeReport?>(null) }
    var available by remember { mutableStateOf<RootfsManifest.Release?>(null) }
    var confirmUpgrade by remember { mutableStateOf(false) }
    var confirmRollback by remember { mutableStateOf(false) }

    var deployed by remember { mutableStateOf(RootfsManager.deployedVersion(context)) }
    var prevVersion by remember {
        mutableStateOf(
            runCatching {
                Regex("version=(\\S+)").find(
                    File(RootfsManager.prevRootfsDir(context), ".drydock-manifest")
                        .takeIf { it.exists() }?.readText().orEmpty(),
                )?.groupValues?.get(1)
            }.getOrNull(),
        )
    }

    fun refreshVersions() {
        deployed = RootfsManager.deployedVersion(context)
        prevVersion = runCatching {
            Regex("version=(\\S+)").find(
                File(RootfsManager.prevRootfsDir(context), ".drydock-manifest")
                    .takeIf { it.exists() }?.readText().orEmpty(),
            )?.groupValues?.get(1)
        }.getOrNull()
    }

    fun startUpgrade(release: RootfsManifest.Release) {
        busy = "检查下载包…"
        msg = ""
        report = null
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    RootfsManager.upgrade(context.applicationContext, release) { s ->
                        busy = when (s) {
                            is RootfsManager.UpgradeState.Downloading -> "下载新版本…（${s.percent}%）"
                            RootfsManager.UpgradeState.Verifying -> "sha256 校验…"
                            RootfsManager.UpgradeState.Extracting -> "旁路部署新版本…（约 1-3 分钟）"
                            is RootfsManager.UpgradeState.Migrating -> "迁移用户文件…（${s.done}/${s.total}）"
                            RootfsManager.UpgradeState.Switching -> "原子切换…"
                            RootfsManager.UpgradeState.RestoringNode -> "恢复 Node 运行时…（约 1 分钟）"
                            RootfsManager.UpgradeState.Restarting -> "重建会话…"
                        }
                    }
                }
                report = r
                msg = "✓ 升级完成：${r.fromVersion} → ${r.toVersion}"
                available = null
                refreshVersions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                msg = "✗ ${e.message}"
            }
            busy = ""
        }
    }

    fun startRollback() {
        busy = "准备回滚…"
        msg = ""
        report = null
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    RootfsManager.rollback(context.applicationContext) { s ->
                        busy = when (s) {
                            is RootfsManager.UpgradeState.Migrating -> "反向迁移用户文件…（${s.done}/${s.total}）"
                            RootfsManager.UpgradeState.Switching -> "原子切换…"
                            RootfsManager.UpgradeState.Restarting -> "重建会话…"
                            else -> busy
                        }
                    }
                }
                report = r
                msg = "✓ 已回滚：${r.fromVersion} → ${r.toVersion}（升级后的新改动已随反向迁移带回）"
                refreshVersions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                msg = "✗ ${e.message}"
            }
            busy = ""
        }
    }

    SettingsSubPage("系统更新", onBack) {
        Text(
            "当前版本：Ubuntu ${deployed.ifBlank { "未部署" }}" +
                (prevVersion?.let { " · 可回滚 $it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
        )

        Button(
            enabled = busy.isBlank() && deployed.isNotBlank(),
            onClick = {
                busy = "检查更新…"
                msg = ""
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching { RootfsManager.checkForUpdate(context.applicationContext) }
                    }
                    busy = ""
                    r.fold({
                        when (it) {
                            is RootfsManager.UpdateCheck.Available -> {
                                available = it.release
                                msg = "发现新版本 ${it.release.version}（约 ${"%.0f".format(it.release.sizeBytes / 1_000_000.0)} MB）"
                            }
                            is RootfsManager.UpdateCheck.UpToDate ->
                                msg = "已是最新（${it.currentVersion}）：${it.reason}"
                            is RootfsManager.UpdateCheck.Failed -> msg = "✗ 检查失败：${it.reason}"
                        }
                    }, { msg = "✗ 检查失败：${it.message}" })
                }
            },
        ) { Text("检查更新") }

        available?.let { rel ->
            Button(
                enabled = busy.isBlank(),
                onClick = { confirmUpgrade = true },
            ) { Text("升级到 ${rel.version}") }
        }

        if (prevVersion != null && deployed.isNotBlank()) {
            Button(
                enabled = busy.isBlank(),
                onClick = { confirmRollback = true },
            ) { Text("回滚到上一版（$prevVersion）") }
        }

        if (busy.isNotBlank()) BusyBar(busy)
        if (msg.isNotBlank()) Text(msg, fontSize = 12.sp, fontFamily = FontFamily.Monospace)

        report?.let { r ->
            Text("升级报告", style = MaterialTheme.typography.titleSmall)
            Text(
                "用户文件迁移 ${r.stats.migrated} 项 · 排除 ${r.stats.excluded} 项（缓存/socket 等）" +
                    " · 同名保用户版 ${r.stats.userWins} 项\n" +
                    "自装 apt 包：${r.aptPackages.joinToString(" ").ifBlank { "无" }}\n" +
                    "自装 npm 包：${r.npmPackages.joinToString(" ").ifBlank { "无" }}",
                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )
            if (r.aptReinstallCmd != null || r.npmReinstallCmd != null) {
                Text("一键重装（升级不自动重装自装包，D33）：", fontSize = 12.sp)
                if (r.aptReinstallCmd != null) {
                    Text(r.aptReinstallCmd, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                if (r.npmReinstallCmd != null) {
                    Text(r.npmReinstallCmd, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Text(
            "升级走旁路部署：下载解压到旁路目录 → sha256 校验（不符拒绝切换）→ 目录重命名原子切换，" +
                "旧版保留一份供回滚（磁盘峰值约 2×rootfs）。\n" +
                "/root 用户文件按黑名单全量迁移（排除 .l2s*/.npm/.cache/AndroidDownload/*.sock），" +
                "与新版模板同名时一律保用户版；自装 apt/npm 包不自动重装，升级后在报告中给出重装命令。\n" +
                "升级与回滚会停止全部会话，完成后按注册表自动重建（正在运行的命令与 shell 内容不保留）。" +
                "更新源：内置版本 pin + 本地更新索引（files/rootfs-updates.json）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (confirmUpgrade) {
            AlertDialog(
                onDismissRequest = { confirmUpgrade = false },
                title = { Text("升级 rootfs？") },
                text = {
                    Text(
                        "将升级到 ${available?.version}。全部会话会被停止（完成后自动重建，运行中的命令不保留）；" +
                            "/root 用户文件与工作区会迁移到新版本；自装 apt/npm 包需要按报告手动重装。",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmUpgrade = false
                        available?.let { startUpgrade(it) }
                    }) { Text("开始升级") }
                },
                dismissButton = { TextButton(onClick = { confirmUpgrade = false }) { Text("取消") } },
            )
        }

        if (confirmRollback) {
            AlertDialog(
                onDismissRequest = { confirmRollback = false },
                title = { Text("回滚到上一版？") },
                text = {
                    Text(
                        "将回滚到 $prevVersion。全部会话会被停止（完成后自动重建）；" +
                            "升级后新产生/修改的 /root 文件会随反向迁移带回旧版，不会丢失。",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmRollback = false
                        startRollback()
                    }) { Text("开始回滚") }
                },
                dismissButton = { TextButton(onClick = { confirmRollback = false }) { Text("取消") } },
            )
        }
    }
}
