package dev.drydock.prototype

import android.content.Context
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/**
 * 终端链路（步骤 3 / AV2；步骤 5 扩多会话）：每会话一对进程——宿主持有的
 * proot+dtach holder（-n 分离建会话）+ ttyd（attach）。会话注册表
 * terminal-sessions.json（:env 写，UI 读）。I2：随机端口 + 轮换 token；
 * I1：agent 环境变量只注入 holder。
 * tmux 因 proot ptrace 冲突弃用、AMS 按进程组清剿两铁律见 D18；
 * link2symlink 自绑定见 D20。
 */
object TerminalManager {

    private const val TAG = "DrydockTerminal"
    const val MAIN = "main"

    /** keyId：null=默认密钥；""=不注入；其余=KeyVault 条目 id（D27 密钥两层制第②层）。 */
    data class Session(val name: String, val port: Int, val token: String, val keyId: String? = null)

    /** L1 心跳样本：alive=holder 存活；rchar=holder /proc/io 读字节（PTY 输出代理）。 */
    data class Heartbeat(val name: String, val alive: Boolean, val rchar: Long, val silentMin: Long)

    private val holders = HashMap<String, Process>()
    private val ttyds = HashMap<String, Process>()
    private val sessions = HashMap<String, Session>()
    private val lastRchar = HashMap<String, Long>()
    private val rcharSince = HashMap<String, Long>()

    fun registryFile(context: Context) = File(context.filesDir, "terminal-sessions.json")

    /** 幂等安装 ttyd + dtach（走 apt，http 源）。tmux 因 proot ptrace 冲突暂缓，见 D18。 */
    fun ensureTerminalLayer(context: Context): RootfsManager.ExecResult {
        val cmd = (
            "dpkg -s ttyd >/dev/null 2>&1 && dpkg -s dtach >/dev/null 2>&1 && echo LAYER_ALREADY " +
                "|| (apt-get update -o Acquire::Retries=2 >/dev/null 2>&1; " +
                "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ttyd dtach 2>&1 | tail -3); " +
                "command -v ttyd dtach; echo LAYER_RC=\$?"
            )
        return RootfsManager.runInEnv(context, cmd)
    }

    fun readSessions(context: Context): List<Session> = try {
        val txt = registryFile(context).takeIf { it.exists() }?.readText()
        if (txt.isNullOrBlank()) emptyList()
        else JSONArray(txt).let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Session(o.getString("name"), o.getInt("port"), o.getString("token"),
                    if (o.has("key_id")) o.getString("key_id") else null)
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** main 会话（默认入口）。 */
    fun current(context: Context): Session? = readSessions(context).firstOrNull { it.name == MAIN }

    /** 服务重启后恢复注册表内全部会话（含各自的 keyId）；空表则起 main。 */
    fun ensureAll(context: Context): List<Session> {
        val existing = readSessions(context)
        val names = existing.map { it.name }.ifEmpty { listOf(MAIN) }
        return names.mapNotNull { n -> start(context, n, existing.firstOrNull { it.name == n }?.keyId) }
    }

    fun newSessionName(context: Context): String {
        val used = readSessions(context).map { it.name }.toSet()
        var i = used.size
        while ("s$i" in used) i++
        return "s$i"
    }

    /** 启动（或复用）指定会话。dtach 无 server：holder 死 = 会话内容丢，
     *  探活失败即重建全新 shell（session_recreated 入时间线）。 */
    @Synchronized
    fun start(context: Context, name: String = MAIN, keyId: String? = null): Session? {
        val rootfs = RootfsManager.rootfsDir(context)
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val sockHost = File(rootfs, "root/$name.sock")

        if (holders[name]?.isAlive != true && !unixSocketAlive(sockHost)) {
            sockHost.delete()
            val existed = readSessions(context).any { it.name == name }
            val holder = spawnHolder(context, name, sockHost, keyId) ?: return null
            holders[name] = holder
            monitorExit(context, "holder:$name", holder)
            if (existed) {
                Timeline.log(context, "session_recreated", mapOf("name" to name, "reason" to "holder 死亡，重建空 shell"))
            }
        }

        if (sessions[name] == null || ttyds[name]?.isAlive != true) {
            ttyds[name]?.destroy()
            val port = 20000 + SecureRandom().nextInt(20000)
            val token = StringBuilder().apply {
                repeat(16) { append("0123456789abcdef"[SecureRandom().nextInt(16)]) }
            }.toString()
            val ttyd = spawnTtyd(context, sockHost, port, token) ?: return null
            ttyds[name] = ttyd
            sessions[name] = Session(name, port, token, keyId)
            monitorExit(context, "ttyd:$name", ttyd)
        }

        persist(context)
        Log.i(TAG, "会话 $name 就绪 (${sessions.size} 个)")
        return sessions[name]
    }

    fun stopAll() {
        holders.values.forEach { it.destroy() }
        ttyds.values.forEach { it.destroy() }
        holders.clear()
        ttyds.clear()
        sessions.clear()
    }

    /** 每 60s 心跳：探活 + rchar 采样（PTY 输出量的代理指标），写时间线并
     *  返回 L1 判定所需状态（静默 = rchar 连续无增长分钟数）。 */
    @Synchronized
    fun heartbeat(context: Context): List<Heartbeat> =
        holders.entries.map { (name, p) ->
            val alive = p.isAlive
            val rchar = if (alive) readRchar(name) else -1L
            val now = System.currentTimeMillis()
            if (rchar >= 0 && rchar > lastRchar.getOrDefault(name, -1)) {
                lastRchar[name] = rchar
                rcharSince[name] = now
            } else if (rchar >= 0 && !lastRchar.containsKey(name)) {
                lastRchar[name] = rchar
                rcharSince[name] = now
            }
            val silentMin = (now - rcharSince.getOrDefault(name, now)) / 60_000
            Timeline.log(
                context,
                "session_heartbeat",
                mapOf("name" to name, "alive" to alive, "rchar" to rchar, "silentMin" to silentMin),
            )
            Heartbeat(name, alive, rchar, silentMin)
        }

    /** 经 /proc 扫描定位该会话的 holder（cmdline 含 dtach 与 sock 名），读 rchar。 */
    private fun readRchar(name: String): Long {
        val needle = "$name.sock"
        val procs = File("/proc").listFiles { f -> f.name.all { it.isDigit() } } ?: return -1L
        for (p in procs) {
            val cmd = try {
                File(p, "cmdline").readText()
            } catch (_: Exception) {
                continue
            }
            if ("dtach" in cmd && needle in cmd) {
                try {
                    File(p, "io").useLines { lines ->
                        lines.firstOrNull { it.startsWith("rchar:") }
                            ?.substringAfter("rchar:")?.trim()?.toLong()?.let { return it }
                    }
                } catch (_: Exception) {
                }
            }
        }
        return -1L
    }

    /** proot 退出码进时间线（engineering-plan 事件清单）。 */
    private fun monitorExit(context: Context, who: String, p: Process) {
        Thread {
            val code = try {
                p.waitFor()
            } catch (_: Exception) {
                -1
            }
            Timeline.log(context, "proot_exit", mapOf("who" to who, "code" to code))
        }.apply { isDaemon = true }.start()
    }

    private fun persist(context: Context) {
        try {
            registryFile(context).writeText(
                JSONArray().apply {
                    sessions.values.forEach {
                        put(JSONObject().put("name", it.name).put("port", it.port).put("token", it.token).apply {
                            it.keyId?.let { k -> put("key_id", k) }
                        })
                    }
                }.toString(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "注册表写入失败: $e")
        }
    }

    private fun spawnHolder(context: Context, name: String, sockHost: File, keyId: String? = null): Process? {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val rootfs = RootfsManager.rootfsDir(context)
        val holder = ProcessBuilder(
            listOf(
                File(nativeDir, "libproot.so").absolutePath,
                "-0", "--link2symlink",
                "-r", rootfs.absolutePath,
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", RootfsManager.l2sSelfBind(context),
            ) + BindStore.binds(context).flatMap { listOf("-b", it) } + listOf(
                "-w", "/root",
                "/usr/bin/dtach", "-n", "/root/$name.sock",
                "/bin/bash", "-l",
            ),
        ).apply {
            redirectErrorStream(true)
            redirectOutput(File(context.cacheDir, "dtach-$name.log"))
            environment().apply {
                put("PROOT_LOADER", File(nativeDir, "libproot-loader.so").absolutePath)
                put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
                put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("HOME", "/root")
                put("TERM", "xterm-256color")
                put("LANG", "C.UTF-8")
                // I1 + D25 零预置 + D27 按会话选 key：keyId null=默认 / ""=不注入 / 条目 id；
                // 未配置端点时回落 AV3 仪器的 GLM 注入保持兼容；会话建立后配置变更需重建会话才生效
                RecipeManager.sessionEnv(context, keyId).forEach { (k, v) -> put(k, v) }
            }
        }.start()
        val deadline = System.currentTimeMillis() + 15_000
        while (!sockHost.exists() && System.currentTimeMillis() < deadline) {
            if (!holder.isAlive) {
                Log.e(TAG, "dtach 会话 $name 进程提前退出")
                return null
            }
            Thread.sleep(200)
        }
        if (!sockHost.exists()) {
            Log.e(TAG, "dtach socket $name 等待超时")
            holder.destroy()
            return null
        }
        return holder
    }

    private fun spawnTtyd(context: Context, sockHost: File, port: Int, token: String): Process? {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val argv = listOf(
            File(nativeDir, "libproot.so").absolutePath,
            "-0", "--link2symlink",
            "-r", RootfsManager.rootfsDir(context).absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", RootfsManager.l2sSelfBind(context),
        ) + BindStore.binds(context).flatMap { listOf("-b", it) } + listOf(
            "-w", "/root",
            "/usr/bin/ttyd",
            "-i", "127.0.0.1",
            "-p", port.toString(),
            "-c", "drydock:$token",
            "-W",
            // dom 渲染器：渲染文本落在 DOM，宿主可断言（AV2）；webgl/canvas 画布不可观测，
            // 其性能优化留给产品期（Q3 碎片化矩阵一并评估）
            "-t", "rendererType=dom",
            "/usr/bin/dtach", "-a", "/root/${sockHost.name}",
        )
        Log.i(TAG, "spawn ttyd $port for ${sockHost.name} token=${token.take(4)}…")
        val p = ProcessBuilder(argv).apply {
            redirectErrorStream(true)
            environment().apply {
                put("PROOT_LOADER", File(nativeDir, "libproot-loader.so").absolutePath)
                put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
                put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("HOME", "/root")
                put("TERM", "xterm-256color")
                put("LANG", "C.UTF-8")
            }
            redirectOutput(File(context.cacheDir, "ttyd-${sockHost.name}.log"))
        }.start()
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) {
                Log.e(TAG, "ttyd 提前退出")
                return null
            }
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 500)
                    if (s.getInputStream().read() >= 0) {
                    }
                }
                return p
            } catch (_: Exception) {
                Thread.sleep(400)
            }
        }
        Log.e(TAG, "ttyd 端口等待超时")
        p.destroy()
        return null
    }

    /** AF_UNIX 探活：能连上即为活会话。 */
    private fun unixSocketAlive(path: File): Boolean = try {
        val ls = android.net.LocalSocket()
        try {
            ls.connect(
                android.net.LocalSocketAddress(
                    path.absolutePath,
                    android.net.LocalSocketAddress.Namespace.FILESYSTEM,
                ),
            )
            true
        } finally {
            ls.close()
        }
    } catch (_: Exception) {
        false
    }
}
