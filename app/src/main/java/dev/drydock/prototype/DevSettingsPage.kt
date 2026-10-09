package dev.drydock.prototype

import android.content.Context
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 会话诊断行（R5）：注册表项 + /proc 探活（ttyd/holder pid）+ 端口 TCP 探活。 */
private data class SessionDiag(
    val name: String,
    val port: Int,
    val ttydPid: Int?,
    val holderPid: Int?,
    val portAlive: Boolean,
)

/** 开发者选项二级页：验收通道说明、缓存清理、BinDoctor、会话诊断、时间线
 *  （导出 D21 口径 + 页内最近 100 条，R5）、版本详情。RootfsManager/TerminalManager/
 *  Timeline 只读复用现有能力；BinDoctor 仅加手动入口 scanNow（扫描/修复逻辑未动）。 */
@Composable
internal fun DevSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ① apt 缓存清理（RootfsManager.cleanCaches，输出 APT_KB_BEFORE/AFTER）
    var cleanBusy by remember { mutableStateOf(false) }
    var cleanMsg by remember { mutableStateOf("") }

    // ② BinDoctor 手动扫描（scanNow，报告进页面 + Timeline 必记）
    var docBusy by remember { mutableStateOf(false) }
    var docMsg by remember { mutableStateOf("") }

    // ③ 会话诊断（进入页面拉一次，按钮手动刷新）
    var diag by remember { mutableStateOf<List<SessionDiag>?>(null) }
    var envLine by remember { mutableStateOf("") }
    fun loadDiag() {
        scope.launch {
            val d = withContext(Dispatchers.IO) { diagnoseSessions(context.applicationContext) }
            diag = d.first
            envLine = d.second
        }
    }
    LaunchedEffect(Unit) { loadDiag() }

    // ④ 时间线：导出（既有）+ 页内最近 100 条
    var tlBusy by remember { mutableStateOf(false) }
    var tlMsg by remember { mutableStateOf("") }
    var tlViewBusy by remember { mutableStateOf(false) }
    var tlLines by remember { mutableStateOf<Pair<Int, List<String>>?>(null) } // 共 X 条 → 显示 Y 条

    SettingsSubPage(stringResource(R.string.settings_row_dev), onBack) {
        Text(stringResource(R.string.dev_channels_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.dev_channels_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.dev_storage_title), style = MaterialTheme.typography.titleMedium)
        Button(
            enabled = !cleanBusy && RootfsManager.isDeployed(context),
            onClick = {
                cleanBusy = true; cleanMsg = ""
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            val out = RootfsManager.cleanCaches(context.applicationContext).output
                            val m = Regex("APT_KB_BEFORE=(\\d+) APT_KB_AFTER=(\\d+)").find(out)
                                ?: return@runCatching context.getString(R.string.dev_clean_parse_fail, out.takeLast(200))
                            context.getString(R.string.dev_clean_ok, m.groupValues[1], m.groupValues[2])
                        }
                    }
                    cleanBusy = false
                    cleanMsg = r.fold({ it }, { context.getString(R.string.dev_clean_fail, it.message ?: "") })
                }
            },
        ) { Text(stringResource(R.string.dev_clean_btn)) }
        if (cleanBusy) BusyBar(stringResource(R.string.dev_clean_busy))
        if (cleanMsg.isNotBlank()) Text(cleanMsg, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(
            stringResource(R.string.dev_clean_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.dev_diag_title), style = MaterialTheme.typography.titleMedium)
        Button(
            enabled = !docBusy && RootfsManager.isDeployed(context),
            onClick = {
                docBusy = true; docMsg = ""
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching { BinDoctor.scanNow(context.applicationContext) }
                    }
                    docBusy = false
                    docMsg = r.fold({ it }, { context.getString(R.string.dev_scan_fail, it.message ?: "") })
                }
            },
        ) { Text(stringResource(R.string.dev_scan_btn)) }
        if (docBusy) BusyBar(stringResource(R.string.dev_scan_busy))
        if (docMsg.isNotBlank()) Text(docMsg, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(stringResource(R.string.dev_sessions_title), style = MaterialTheme.typography.titleMedium)
        Button(onClick = { loadDiag() }) { Text(stringResource(R.string.dev_sessions_refresh)) }
        val rows = diag
        if (rows == null) {
            Text(stringResource(R.string.dev_reading), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        } else if (rows.isEmpty()) {
            Text(
                stringResource(R.string.dev_no_sessions),
                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                stringResource(R.string.dev_registry_count, rows.size),
                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )
            rows.forEach { d ->
                Text(
                    stringResource(R.string.dev_session_row, d.name, d.port,
                        if (d.portAlive) stringResource(R.string.dev_port_alive) else stringResource(R.string.dev_port_dead),
                        d.ttydPid?.toString() ?: stringResource(R.string.dev_not_running),
                        d.holderPid?.toString() ?: stringResource(R.string.dev_not_running)),
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }
        if (envLine.isNotBlank()) {
            Text(
                envLine, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(stringResource(R.string.dev_tl_title), style = MaterialTheme.typography.titleMedium)
        Button(
            enabled = !tlBusy && Timeline.file(context).exists(),
            onClick = {
                tlBusy = true; tlMsg = ""
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            val f = File(context.cacheDir, "drydock-timeline.jsonl")
                            f.writeText(Timeline.readAll(context).joinToString("\n") + "\n")
                            "${"%.0f".format(f.length() / 1000.0)} KB → ${Landing.toDownloads(context, f)}"
                        }
                    }
                    tlBusy = false
                    tlMsg = r.fold({ "✓ $it" }, { context.getString(R.string.dev_tl_export_fail, it.message ?: "") })
                }
            },
        ) { Text(stringResource(R.string.dev_tl_export_btn)) }
        if (tlBusy) BusyBar(stringResource(R.string.dev_tl_export_busy))
        if (tlMsg.isNotBlank()) Text(tlMsg, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Button(
            enabled = !tlViewBusy,
            onClick = {
                tlViewBusy = true
                scope.launch {
                    val all = withContext(Dispatchers.IO) { Timeline.readAll(context.applicationContext) }
                    tlLines = Pair(all.size, all.takeLast(100))
                    tlViewBusy = false
                }
            },
        ) { Text(stringResource(R.string.dev_tl_view_btn)) }
        if (tlViewBusy) BusyBar(stringResource(R.string.dev_tl_view_busy))
        tlLines?.let { (total, shown) ->
            Text(
                stringResource(R.string.dev_tl_count, shown.size, total),
                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )
            shown.forEach { line ->
                Text(
                    formatTimelineLine(line),
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(stringResource(R.string.dev_ver_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.dev_ver_body,
                RootfsManager.deployedVersion(context).ifBlank { stringResource(R.string.dev_ver_not_deployed) },
                RecipeManager.installedIds(context).joinToString("、").ifBlank { stringResource(R.string.dev_ver_no_recipes) }),
            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 诊断采集：注册表（UI 进程读 terminal-sessions.json）+ /proc 探 ttyd/holder +
 *  端口 TCP 探活；另附 WakeLock 与 :env 进程状态行。全部只读。 */
private fun diagnoseSessions(context: Context): Pair<List<SessionDiag>, String> {
    val (ttyds, holders) = probeProcs()
    val rows = TerminalManager.readSessions(context).map { s ->
        SessionDiag(
            name = s.name,
            port = s.port,
            ttydPid = ttyds[s.name],
            holderPid = holders[s.name],
            portAlive = portAlive(s.port),
        )
    }
    return Pair(rows, envAndWakelock(context))
}

/** /proc 扫 ttyd/holder（同 uid 可读，readRchar 同款口径）：cmdline 按 NUL 分段，
 *  ttyd 的 dtach 挂 "-a /root/<名>.sock"，holder 挂 "-n /root/<名>.sock"。 */
private fun probeProcs(): Pair<Map<String, Int>, Map<String, Int>> {
    val ttyds = HashMap<String, Int>()
    val holders = HashMap<String, Int>()
    val procs = File("/proc").listFiles { f -> f.name.all { it.isDigit() } } ?: return Pair(ttyds, holders)
    for (p in procs) {
        val args = try {
            File(p, "cmdline").readText().split('\u0000')
        } catch (_: Exception) {
            continue
        }
        val i = args.indexOfFirst { it == "-n" || it == "-a" }
        if (i < 0) continue
        val sock = args.getOrNull(i + 1) ?: continue
        if (!sock.startsWith("/root/") || !sock.endsWith(".sock")) continue
        val name = sock.removePrefix("/root/").removeSuffix(".sock")
        if (args[i] == "-a") ttyds[name] = p.name.toInt() else holders[name] = p.name.toInt()
    }
    return Pair(ttyds, holders)
}

private fun portAlive(port: Int): Boolean = try {
    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 400) }
    true
} catch (_: Exception) {
    false
}

/** WakeLock 持有状态自时间线推断（UI 进程读不到 :env 内存里的 WakeLock，只能取
 *  最近一次 acquire/release 事件）；:env 进程在否经 /proc cmdline 直查。 */
private fun envAndWakelock(context: Context): String {
    val envAlive = File("/proc").listFiles { f -> f.name.all { it.isDigit() } }
        ?.any { p ->
            runCatching { File(p, "cmdline").readText() }.getOrNull()
                ?.substringBefore('\u0000') == "${context.packageName}:env"
        } == true
    var wl = context.getString(R.string.dev_wl_none)
    for (line in Timeline.readAll(context).asReversed()) {
        val t = runCatching { JSONObject(line).optString("type") }.getOrNull() ?: continue
        if (t == "wakelock_acquire") {
            wl = context.getString(R.string.dev_wl_held)
            break
        }
        if (t == "wakelock_release") {
            wl = context.getString(R.string.dev_wl_not_held)
            break
        }
    }
    return context.getString(if (envAlive) R.string.dev_env_alive else R.string.dev_env_dead, wl)
}

/** 时间线行紧凑化：ts → 时间、type、余下 data 键值（ts/type/pid/proc 略）。 */
private fun formatTimelineLine(line: String): String {
    val o = runCatching { JSONObject(line) }.getOrNull() ?: return line.take(120)
    val ts = o.optLong("ts", 0L)
    val time = if (ts > 0) SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ts)) else "?"
    val skip = setOf("ts", "type", "pid", "proc")
    val data = o.keys().asSequence().filter { it !in skip }.joinToString(" ") { "$it=${o.opt(it)}" }
    return "$time ${o.optString("type")} $data"
}
