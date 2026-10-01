package dev.drydock.prototype

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * 驾驶舱最小版（真机周后首块产品功能，L2 批准流落地）：
 * - 对话：`claude -p --output-format json`，后续轮 `--resume <session_id>` 续会话；
 * - 批准：环境内 hook（PreToolUse 网关，Q5 实测机制）把每次工具调用落 `.req` 文件，
 *   高危工具（Write/Edit/Bash 等）原地等待宿主写 `.dec`（approve/deny）——宿主与
 *   rootfs 同 uid，直读直写文件桥即可，不经 proot/adb；只读工具 hook 直接放行；
 * - 产物：cwd=cockpit/ws，文件列表经 MediaStore 落袋（I4）。
 * hook 的 .req 同时是工具事件流（tool_name + tool_input），喂养对话流展示。
 */
object CockpitManager {
    private const val TAG = "DrydockCockpit"
    const val TURN_TIMEOUT_S = 300

    // Tool 条目的批准状态
    const val WAITING = 0
    const val APPROVED = 1
    const val DENIED = 2
    const val TIMEOUT_DENIED = 3
    const val AUTO = 4

    sealed class Msg {
        data class User(val text: String) : Msg()
        data class Assistant(val text: String, val ok: Boolean) : Msg()
        data class Tool(
            val tool: String,
            val brief: String,
            val approvalId: String,
            val state: Int,
        ) : Msg()
        data class System(val text: String) : Msg()
    }

    private val _msgs = MutableStateFlow<List<Msg>>(emptyList())
    val msgs: StateFlow<List<Msg>> = _msgs
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    @Volatile private var sessionId: String? = null
    @Volatile private var initialized = false
    private val seenApprovals = HashSet<String>()

    private fun cockpitDir(context: Context) = File(RootfsManager.rootfsDir(context), "root/cockpit")
    private fun approvalsDir(context: Context) = File(cockpitDir(context), "approvals")
    fun wsDir(context: Context) = File(cockpitDir(context), "ws")

    fun isReady(context: Context) = File(cockpitDir(context), "hook.sh").exists()

    /** 幂等初始化：目录 + 批准网关 hook + ~/.claude/settings.json（整体覆写，原型口径）。 */
    fun init(context: Context) {
        val ctx = context.applicationContext
        val hook = """
            #!/bin/bash
            j=${'$'}(cat)
            tool=${'$'}(printf '%s' "${'$'}j" | node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>console.log(JSON.parse(d).tool_name||""))')
            id=${'$'}(date +%s%N)
            printf '%s\n' "${'$'}j" > /root/cockpit/approvals/${'$'}id.req
            case "${'$'}tool" in
              Write|Edit|MultiEdit|Bash|NotebookEdit)
                for i in ${'$'}(seq 1 300); do
                  [ -f /root/cockpit/approvals/${'$'}id.dec ] && break
                  sleep 0.5
                done
                if [ -f /root/cockpit/approvals/${'$'}id.dec ]; then
                  if [ "${'$'}(cat /root/cockpit/approvals/${'$'}id.dec)" = "approve" ]; then exit 0; fi
                  echo "用户在驾驶舱拒绝了此操作" >&2
                  exit 2
                else
                  echo "批准等待超时（150 秒），默认拒绝" >&2
                  exit 2
                fi
                ;;
              *) exit 0 ;;
            esac
        """.trimIndent()
        val settings = """
            {"hooks":{"PreToolUse":[{"matcher":"*","hooks":[{"type":"command","command":"bash /root/cockpit/hook.sh"}]}]}}
        """.trim()
        val script = "mkdir -p /root/cockpit/ws /root/cockpit/approvals; " +
            "cat > /root/cockpit/hook.sh <<'EOS'\n$hook\nEOS\n" +
            "chmod +x /root/cockpit/hook.sh; " +
            "mkdir -p /root/.claude && cat > /root/.claude/settings.json <<'EOJ'\n$settings\nEOJ\n" +
            "ls /root/cockpit/ && echo COCKPIT_INIT_OK"
        val r = RootfsManager.runInEnv(ctx, script)
        initialized = r.output.contains("COCKPIT_INIT_OK")
        Log.i(TAG, "cockpit init: ${r.output.takeLast(200)}")
    }

    /** 发起一轮对话（阻塞在后台线程；busy 期间 UI 轮询审批与事件）。 */
    fun send(context: Context, text: String) {
        if (_busy.value) return
        if (text.isBlank()) return
        val ctx = context.applicationContext
        if (SecretStore.load(ctx, AgentManager.KEY_NAME) == null) {
            _msgs.value = _msgs.value + Msg.System("未配置 GLM key（主页步骤 4 注入），无法发起对话")
            return
        }
        // hook 安装是异步 init：发送前等就绪（最长 30s）
        val waitStart = System.currentTimeMillis()
        while (!initialized && System.currentTimeMillis() - waitStart < 30_000) {
            Thread.sleep(500)
        }
        if (!initialized) {
            _msgs.value = _msgs.value + Msg.System("驾驶舱初始化未完成（hook 未就绪），本轮取消")
            return
        }
        _busy.value = true
        _msgs.value = _msgs.value + Msg.User(text)
        Thread {
            try {
                val esc = text.replace("'", "'\\''")
                val resume = sessionId?.let { "--resume $it" } ?: ""
                val cmd =
                    "printf '%s' '$esc' > /root/cockpit/msg.txt; " +
                        "cd /root/cockpit/ws && timeout $TURN_TIMEOUT_S claude -p $resume " +
                        "--permission-mode acceptEdits --output-format json " +
                        "< /root/cockpit/msg.txt > /root/cockpit/out.json 2>/root/cockpit/err.txt; " +
                        "echo COCKPIT_RC=\${'$'}?"
                val r = RootfsManager.runInEnv(ctx, cmd, extraEnv = AgentManager.agentEnv(ctx))
                val raw = File(cockpitDir(ctx), "out.json").takeIf { it.exists() }?.readText().orEmpty()
                val result = runCatching { JSONObject(raw).getString("result") }.getOrNull()
                runCatching { JSONObject(raw).getString("session_id") }.getOrNull()?.let { sessionId = it }
                pollApprovals(ctx)
                if (result != null) {
                    _msgs.value = _msgs.value + Msg.Assistant(result, ok = true)
                } else {
                    val err = File(cockpitDir(ctx), "err.txt").takeIf { it.exists() }?.readText().orEmpty()
                    _msgs.value = _msgs.value + Msg.Assistant(
                        "轮次失败（${r.output.takeLast(200)} ${err.takeLast(150)}）",
                        ok = false,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "send 异常", e)
                _msgs.value = _msgs.value + Msg.Assistant("异常：$e", ok = false)
            } finally {
                _busy.value = false
            }
        }.start()
    }

    /** 用户在批准卡片上点按钮：直写 .dec 文件，环境内 hook 随即感知。 */
    fun decide(context: Context, approvalId: String, approve: Boolean) {
        File(approvalsDir(context), "$approvalId.dec").writeText(if (approve) "approve" else "deny")
        Log.i(TAG, "decide $approvalId = $approve")
    }

    /** 扫描 .req/.dec：新事件进对话流，已决策的更新状态。 */
    fun pollApprovals(context: Context) {
        val dir = approvalsDir(context)
        val reqs = dir.listFiles { f -> f.name.endsWith(".req") } ?: return
        if (reqs.isEmpty()) return
        val fresh = mutableListOf<Msg.Tool>()
        val stateUpdates = mutableMapOf<String, Int>()
        for (req in reqs.sortedBy { it.name.removeSuffix(".req").toLongOrNull() ?: 0L }) {
            val id = req.name.removeSuffix(".req")
            if (id !in seenApprovals) {
                seenApprovals.add(id)
                val t = parseTool(req) ?: continue
                // 决策文件已存在（历史轮次）→ 定状态；新近且无决策 → 等待中
                val dec = File(dir, "$id.dec").takeIf { it.exists() }?.readText()?.trim()
                val state = when {
                    dec == "approve" -> APPROVED
                    dec == "deny" -> DENIED
                    dec != null -> DENIED
                    reqIsHighRisk(t.tool) && reqIsRecent(req) -> WAITING
                    reqIsHighRisk(t.tool) -> TIMEOUT_DENIED
                    else -> AUTO
                }
                fresh.add(Msg.Tool(t.tool, t.brief, id, state))
            } else {
                val dec = File(dir, "$id.dec").takeIf { it.exists() }?.readText()?.trim() ?: continue
                stateUpdates[id] = if (dec == "approve") APPROVED else DENIED
            }
        }
        if (fresh.isEmpty() && stateUpdates.isEmpty()) return
        _msgs.value = _msgs.value + fresh
        if (stateUpdates.isNotEmpty()) {
            _msgs.value = _msgs.value.map { m ->
                if (m is Msg.Tool && stateUpdates.containsKey(m.approvalId)) {
                    m.copy(state = stateUpdates[m.approvalId]!!)
                } else m
            }
        }
    }

    private data class ToolInfo(val tool: String, val brief: String)

    private fun parseTool(req: File): ToolInfo? = runCatching {
        val o = JSONObject(req.readText())
        val tool = o.optString("tool_name", "?")
        val input = o.optJSONObject("tool_input") ?: JSONObject()
        val brief = listOf("file_path", "command", "pattern", "path", "url", "description")
            .firstNotNullOfOrNull { k ->
                input.optString(k).takeIf { it.isNotBlank() }?.let { "$k: ${it.take(90)}" }
            } ?: input.keys().asSequence().take(2).joinToString(",")
        ToolInfo(tool, brief)
    }.getOrNull()

    private fun reqIsHighRisk(tool: String) =
        tool in setOf("Write", "Edit", "MultiEdit", "Bash", "NotebookEdit")

    private fun reqIsRecent(f: File) =
        System.currentTimeMillis() - f.lastModified() < 150_000

    /** 产物清单：ws/ 下的近期文件（新→旧）。 */
    fun workspaceFiles(context: Context): List<File> =
        wsDir(context).listFiles()?.filter { it.isFile && !it.name.startsWith(".") }
            ?.sortedByDescending { it.lastModified() }?.take(20) ?: emptyList()
}
