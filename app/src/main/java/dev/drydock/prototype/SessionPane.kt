package dev.drydock.prototype

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier

/** 等会话 ttyd 就绪：注册表项在（既有会话）不代表 holder/ttyd 活着，且重建 ttyd
 *  会换端口——每轮重读注册表并对当前端口做 TCP 探活。冷启动串行重建每会话
 *  ~10-15s（proot+ttyd 就绪探测自身 20s 上限），预算默认 120s。超时返回 null。 */
private suspend fun awaitSessionReady(
    appCtx: Context,
    name: String,
    timeoutMs: Long = 120_000,
): TerminalManager.Session? {
    val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
    while (android.os.SystemClock.elapsedRealtime() < deadline) {
        val s = withContext(Dispatchers.IO) {
            TerminalManager.readSessions(appCtx).firstOrNull { it.name == name }?.let { sess ->
                try {
                    java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", sess.port), 500) }
                    sess
                } catch (_: Exception) {
                    null
                }
            }
        }
        if (s != null) return s
        delay(1000)
    }
    return null
}

/** 会话页：管理已开终端、新建；空状态 = 首启教育。 */
@Composable
internal fun SessionPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf("") }
    var closeTarget by remember { mutableStateOf<TerminalManager.Session?>(null) }
    var renameTarget by remember { mutableStateOf<TerminalManager.Session?>(null) }
    var showNewDialog by remember { mutableStateOf(false) }
    val sessions = remember(tick) { TerminalManager.readSessions(context) }
    val displayNames = remember(tick) { SessionNames.load(context) }

    fun displayName(name: String): String =
        displayNames[name] ?: if (name == TerminalManager.MAIN) "主终端" else name

    // 会话列表保鲜：回主页/停留期间 5s 轮询注册表（修「回来不刷新」）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            tick++
        }
    }

    // 打开（或接回）会话：卡片即入口。全进程死亡后 holder/ttyd 不在，spawn 必须走
    // :env 进程组（app 进程直起的子进程会被 AMS 清剿，D18）；就绪判据见 awaitSessionReady
    fun openSession(name: String) {
        if (busy.isNotBlank()) return
        scope.launch {
            val appCtx = context.applicationContext
            busy = context.getString(R.string.session_busy_reattach, displayName(name))
            try {
                val i = Intent(appCtx, EnvService::class.java).putExtra("new_session", name)
                appCtx.startForegroundService(i)
                val ready = awaitSessionReady(appCtx, name)
                busy = ""
                if (ready != null) {
                    context.startActivity(
                        Intent(context, TerminalActivity::class.java).putExtra("session", name))
                } else busy = context.getString(R.string.session_err_reattach)
            } catch (e: Exception) {
                busy = ""
            }
        }
    }

    // 新建会话（唯一入口；注册表空 = 建主终端）。首启部署与终端层安装（原
    // 「打开终端」大按钮职责）一并承担：新用户从这里一步进终端
    fun createSession(display: String) {
        if (busy.isNotBlank()) return
        scope.launch {
            val appCtx = context.applicationContext
            try {
                val tech = if (sessions.isEmpty()) TerminalManager.MAIN else TerminalManager.newSessionName(appCtx)
                if (display.isNotBlank()) SessionNames.set(appCtx, tech, display)
                if (!RootfsManager.isDeployed(appCtx)) {
                    busy = context.getString(R.string.session_busy_deploy)
                    withContext(Dispatchers.IO) { RootfsManager.deploy(appCtx) { } }
                }
                busy = context.getString(R.string.session_busy_terminal_layer)
                withContext(Dispatchers.IO) { TerminalManager.ensureTerminalLayer(appCtx) }
                busy = context.getString(R.string.session_busy_starting)
                context.startForegroundService(
                    Intent(appCtx, EnvService::class.java).putExtra("new_session", tech))
                val ready = awaitSessionReady(appCtx, tech)
                busy = ""
                tick++
                if (ready != null) {
                    context.startActivity(
                        Intent(context, TerminalActivity::class.java).putExtra("session", tech))
                } else busy = context.getString(R.string.session_err_start)
            } catch (e: Exception) {
                busy = ""
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.nav_label_sessions), style = MaterialTheme.typography.titleLarge)

        if (busy.isNotBlank()) {
            BusyBar(busy)
        }

        if (sessions.isEmpty()) {
            Text(
                stringResource(R.string.session_empty_intro),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(enabled = busy.isBlank(), onClick = { showNewDialog = true }, modifier = Modifier.testTag("home_new_session")) { Text(stringResource(R.string.session_new)) }

        sessions.forEach { s ->
            Card(modifier = Modifier.fillMaxWidth().testTag("home_session_card").clickable { openSession(s.name) }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(displayName(s.name), style = MaterialTheme.typography.titleMedium)
                        Text(
                            (if (displayName(s.name) != s.name) "${s.name} · " else "") +
                                stringResource(R.string.session_card_local_port, s.port),
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(enabled = busy.isBlank(), onClick = { renameTarget = s }) { Text(stringResource(R.string.session_rename)) }
                    TextButton(enabled = busy.isBlank(), onClick = { closeTarget = s }) { Text(stringResource(R.string.session_close)) }
                }
            }
        }

        // 新建：默认名可改（display 层，SessionNames 落盘；technical 名照旧自动分配）
        if (showNewDialog) {
            var nameInput by remember(showNewDialog) {
                mutableStateOf(if (sessions.isEmpty()) "主终端" else "会话 ${sessions.size + 1}")
            }
            AlertDialog(
                modifier = Modifier.semantics { testTagsAsResourceId = true },
                onDismissRequest = { showNewDialog = false },
                title = { Text(stringResource(R.string.session_new)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.session_dlg_new_desc),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            label = { Text(stringResource(R.string.session_dlg_name_label)) },
                            singleLine = true,
                            modifier = Modifier.testTag("dlg_session_name"),
                        )
                    }
                },
                confirmButton = {
                    TextButton(enabled = nameInput.isNotBlank() && busy.isBlank(), onClick = {
                        showNewDialog = false
                        createSession(nameInput)
                    }, modifier = Modifier.testTag("dlg_create")) { Text(stringResource(R.string.session_dlg_create)) }
                },
                dismissButton = { TextButton(onClick = { showNewDialog = false }) { Text(stringResource(R.string.common_cancel)) } },
            )
        }

        renameTarget?.let { t ->
            var nameInput by remember(t.name) { mutableStateOf(displayName(t.name)) }
            AlertDialog(
                modifier = Modifier.semantics { testTagsAsResourceId = true },
                onDismissRequest = { renameTarget = null },
                title = { Text(stringResource(R.string.session_dlg_rename_title)) },
                text = {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text(stringResource(R.string.session_dlg_name_label)) },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(enabled = nameInput.isNotBlank(), onClick = {
                        SessionNames.set(context, t.name, nameInput)
                        renameTarget = null
                        tick++
                    }) { Text(stringResource(R.string.session_dlg_save)) }
                },
                dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.common_cancel)) } },
            )
        }

        closeTarget?.let { t ->
            AlertDialog(
                modifier = Modifier.semantics { testTagsAsResourceId = true },
                onDismissRequest = { closeTarget = null },
                title = { Text(stringResource(R.string.session_dlg_close_title, displayName(t.name))) },
                text = {
                    Text(
                        stringResource(R.string.session_dlg_close_desc) +
                            (if (t.name == TerminalManager.MAIN) stringResource(R.string.session_dlg_close_main_note) else ""),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        closeTarget = null
                        scope.launch {
                            val appCtx = context.applicationContext
                            busy = context.getString(R.string.session_busy_closing, displayName(t.name))
                            try {
                                appCtx.startForegroundService(
                                    Intent(appCtx, EnvService::class.java).putExtra("stop_session", t.name))
                                // 就绪判据：注册表文件里该会话消失（stop 在 :env 进程执行）
                                var closed = false
                                repeat(15) {
                                    if (it > 0) delay(1000)
                                    if (TerminalManager.readSessions(appCtx).none { s2 -> s2.name == t.name }) {
                                        closed = true; return@repeat
                                    }
                                }
                                busy = ""
                                if (!closed) busy = context.getString(R.string.session_err_close)
                                tick++
                            } catch (e: Exception) {
                                busy = ""
                            }
                        }
                    }) { Text(stringResource(R.string.session_close)) }
                },
                dismissButton = { TextButton(onClick = { closeTarget = null }) { Text(stringResource(R.string.common_cancel)) } },
            )
        }
    }
}
