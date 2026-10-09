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
import androidx.compose.ui.res.stringResource
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
        busy = context.getString(R.string.update_busy_check_pkg)
        msg = ""
        report = null
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    RootfsManager.upgrade(context.applicationContext, release) { s ->
                        busy = when (s) {
                            is RootfsManager.UpgradeState.Downloading -> context.getString(R.string.update_busy_download, s.percent)
                            RootfsManager.UpgradeState.Verifying -> context.getString(R.string.update_busy_verify)
                            RootfsManager.UpgradeState.Extracting -> context.getString(R.string.update_busy_extract)
                            is RootfsManager.UpgradeState.Migrating -> context.getString(R.string.update_busy_migrate, s.done, s.total)
                            RootfsManager.UpgradeState.Switching -> context.getString(R.string.update_busy_switch)
                            RootfsManager.UpgradeState.RestoringNode -> context.getString(R.string.update_busy_node)
                            RootfsManager.UpgradeState.Restarting -> context.getString(R.string.update_busy_restart)
                        }
                    }
                }
                report = r
                msg = context.getString(R.string.update_ok, r.fromVersion, r.toVersion)
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
        busy = context.getString(R.string.update_busy_rollback)
        msg = ""
        report = null
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    RootfsManager.rollback(context.applicationContext) { s ->
                        busy = when (s) {
                            is RootfsManager.UpgradeState.Migrating -> context.getString(R.string.update_busy_migrate_back, s.done, s.total)
                            RootfsManager.UpgradeState.Switching -> context.getString(R.string.update_busy_switch)
                            RootfsManager.UpgradeState.Restarting -> context.getString(R.string.update_busy_restart)
                            else -> busy
                        }
                    }
                }
                report = r
                msg = context.getString(R.string.update_rollback_ok, r.fromVersion, r.toVersion)
                refreshVersions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                msg = "✗ ${e.message}"
            }
            busy = ""
        }
    }

    SettingsSubPage(stringResource(R.string.settings_row_update), onBack) {
        Text(
            stringResource(R.string.update_current_line, deployed.ifBlank { stringResource(R.string.update_not_deployed) }) +
                (prevVersion?.let { stringResource(R.string.update_rollback_suffix, it) } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
        )

        Button(
            enabled = busy.isBlank() && deployed.isNotBlank(),
            onClick = {
                busy = context.getString(R.string.update_busy_checking)
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
                                msg = context.getString(R.string.update_found, it.release.version, "%.0f".format(it.release.sizeBytes / 1_000_000.0))
                            }
                            is RootfsManager.UpdateCheck.UpToDate ->
                                msg = context.getString(R.string.update_uptodate, it.currentVersion, it.reason)
                            is RootfsManager.UpdateCheck.Failed -> msg = context.getString(R.string.update_check_fail, it.reason)
                        }
                    }, { msg = context.getString(R.string.update_check_fail, it.message ?: "") })
                }
            },
        ) { Text(stringResource(R.string.update_check_btn)) }

        available?.let { rel ->
            Button(
                enabled = busy.isBlank(),
                onClick = { confirmUpgrade = true },
            ) { Text(stringResource(R.string.update_upgrade_btn, rel.version)) }
        }

        if (prevVersion != null && deployed.isNotBlank()) {
            Button(
                enabled = busy.isBlank(),
                onClick = { confirmRollback = true },
            ) { Text(stringResource(R.string.update_rollback_btn, prevVersion ?: "")) }
        }

        if (busy.isNotBlank()) BusyBar(busy)
        if (msg.isNotBlank()) Text(msg, fontSize = 12.sp, fontFamily = FontFamily.Monospace)

        report?.let { r ->
            Text(stringResource(R.string.update_report_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.update_report_body, r.stats.migrated, r.stats.excluded, r.stats.userWins,
                    r.aptPackages.joinToString(" ").ifBlank { stringResource(R.string.update_report_none) },
                    r.npmPackages.joinToString(" ").ifBlank { stringResource(R.string.update_report_none) }),
                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )
            if (r.aptReinstallCmd != null || r.npmReinstallCmd != null) {
                Text(stringResource(R.string.update_reinstall_title), fontSize = 12.sp)
                if (r.aptReinstallCmd != null) {
                    Text(r.aptReinstallCmd, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                if (r.npmReinstallCmd != null) {
                    Text(r.npmReinstallCmd, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Text(
            stringResource(R.string.update_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (confirmUpgrade) {
            AlertDialog(
                onDismissRequest = { confirmUpgrade = false },
                title = { Text(stringResource(R.string.update_confirm_title)) },
                text = {
                    Text(
                        stringResource(R.string.update_confirm_text, available?.version ?: ""),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmUpgrade = false
                        available?.let { startUpgrade(it) }
                    }) { Text(stringResource(R.string.update_confirm_btn)) }
                },
                dismissButton = { TextButton(onClick = { confirmUpgrade = false }) { Text(stringResource(R.string.common_cancel)) } },
            )
        }

        if (confirmRollback) {
            AlertDialog(
                onDismissRequest = { confirmRollback = false },
                title = { Text(stringResource(R.string.update_rollback_title)) },
                text = {
                    Text(
                        stringResource(R.string.update_rollback_text, prevVersion ?: ""),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmRollback = false
                        startRollback()
                    }) { Text(stringResource(R.string.update_rollback_btn2)) }
                },
                dismissButton = { TextButton(onClick = { confirmRollback = false }) { Text(stringResource(R.string.common_cancel)) } },
            )
        }
    }
}
