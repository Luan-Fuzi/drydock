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

    data class Session(val name: String, val port: Int, val token: String)

    /** L1 心跳样本：alive=holder 存活；rchar=holder /proc/io 读字节（PTY 输出代理）。 */
    data class Heartbeat(val name: String, val alive: Boolean, val rchar: Long, val silentMin: Long)

    private val holders = HashMap<String, Process>()
    private val ttyds = HashMap<String, Process>()
    private val sessions = HashMap<String, Session>()
    private val lastRchar = HashMap<String, Long>()
    private val rcharSince = HashMap<String, Long>()

    fun registryFile(context: Context) = File(context.filesDir, "terminal-sessions.json")

    /** 终端基础层 apt 包（17 个，ensureTerminalLayer 幂等装；R8 升级报告的
     *  「非自装」扣除项之一，D33 细则 3）。 */
    val APT_LAYER_PACKAGES = listOf(
        "ttyd", "dtach", "git", "ripgrep", "fd-find", "curl", "wget", "zip", "unzip",
        "xz-utils", "bzip2", "jq", "file", "procps", "openssh-client", "ca-certificates", "less",
    )

    /** 幂等安装终端基础层（走 apt，http 源）。rootfs 是 ubuntu-base 裸底盘（30MB），
     *  基础层从完整安装里按 agent 刚需取回（2026-10-07 用户定调），下载合计 ~10MB：
     *  终端链路 ttyd+dtach；git 全家（git+ca-certificates+less 分页器）；搜索
     *  ripgrep+fd-find（pi 缺它会转 GitHub 下载在国内网络挂死；Ubuntu 包名 fd-find
     *  二进制 fdfind，补 fd 符号链接）；网络 curl+wget；压缩 zip/unzip/xz-utils/
     *  bzip2（源码包常见格式）；文本/系统 jq（JSON 处理高频）+file+procps（ps/top）；远端 openssh-client（ssh/scp，
     *  纯客户端无监听面，key 走惯例 ~/.ssh 用户自理）。
     *  python3（~60MB）/build-essential（数百 MB）/vim（编辑器偏好）不进默认，按需
     *  apt 装。tmux 因 proot ptrace 冲突暂缓（D18）。EnvService 会话 ensure 后异步
     *  补跑，存量环境缺包自动补齐。
     *  存在性判定用 command -v 探二进制而非 dpkg -s（R12，2026-10-08 教训）：dpkg -s
     *  对 half-installed（安装中途 app 被杀、dpkg 状态库记着装了一半）也返回 0，
     *  误判已装 → 会话永久起不来且无自愈；command -v 直接反映可用性。修复路径先
     *  dpkg --configure -a 收拾残局再 --reinstall 补齐（half-installed 与「装了但
     *  文件丢失」两种形态都覆盖）。 */
    fun ensureTerminalLayer(context: Context): RootfsManager.ExecResult {
        val layer = APT_LAYER_PACKAGES.joinToString(" ")
        val cmd = (
            "fdlink() { [ -e /usr/bin/fdfind ] && { [ -e /usr/local/bin/fd ] || ln -sf /usr/bin/fdfind /usr/local/bin/fd; }; }; " +
                "miss=0; for b in ttyd dtach git rg fd curl wget zip unzip xz bzip2 jq file ps ssh; do command -v \$b >/dev/null 2>&1 || miss=1; done; fdlink; " +
                // 多参数 command -v 只要任一找到就返回 0（实测），必须逐个探测
                "[ \$miss -eq 0 ] && echo LAYER_ALREADY " +
                "|| { dpkg --configure -a >/dev/null 2>&1; apt-get update -o Acquire::Retries=2 >/dev/null 2>&1; " +
                "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends --reinstall $layer 2>&1 | tail -3; fdlink; }; " +
                "for b in ttyd dtach git rg fd curl wget zip unzip xz bzip2 jq file ps ssh; do command -v \$b || echo MISSING_\$b; done; " +
                "miss=0; for b in ttyd dtach git rg fd curl wget zip unzip xz bzip2 jq file ps ssh; do command -v \$b >/dev/null 2>&1 || miss=1; done; echo LAYER_RC=\$miss"
            )
        return RootfsManager.runInEnv(context, cmd)
    }

    fun readSessions(context: Context): List<Session> = try {
        val txt = registryFile(context).takeIf { it.exists() }?.readText()
        if (txt.isNullOrBlank()) emptyList()
        else JSONArray(txt).let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Session(o.getString("name"), o.getInt("port"), o.getString("token"))
                // 旧注册表里的 key_id 字段（D27 密钥两层制遗留）直接忽略
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** main 会话（默认入口）。 */
    fun current(context: Context): Session? = readSessions(context).firstOrNull { it.name == MAIN }

    /** 服务重启后恢复注册表内全部会话；空表则起 main。 */
    fun ensureAll(context: Context): List<Session> {
        val existing = readSessions(context)
        val names = existing.map { it.name }.ifEmpty { listOf(MAIN) }
        return names.mapNotNull { n -> start(context, n) }
    }

    fun newSessionName(context: Context): String {
        val used = readSessions(context).map { it.name }.toSet()
        var i = used.size
        while ("s$i" in used) i++
        return "s$i"
    }

    /** 关闭会话：杀 holder/ttyd 并移出注册表。dtach 无 server，会话内容随 holder
     *  丢失（重新打开 = 全新 shell）；全部关完后由 ensureAll 的空表回退在下次
     *  打开时重建 main。必须在 :env 进程调用（Process 句柄在服务进程内存里）。
     *  destroy() 只杀直接子进程，ptrace 下的 dtach/bash 可能存活——先按 sock 名
     *  清点幸存者逐个 SIGKILL（同 uid，ps 可见）。 */
    @Synchronized
    fun stop(context: Context, name: String): Boolean {
        runCatching {
            val ps = ProcessBuilder("ps", "-A", "-o", "PID,ARGS").start()
                .inputStream.bufferedReader().readText()
            ps.lineSequence()
                .filter { "/root/$name.sock" in it }
                .map { it.trim().split(Regex("\\s+"))[0] }
                .filter { it.toIntOrNull() != null && it.toInt() != android.os.Process.myPid() }
                .forEach { runCatching { android.os.Process.killProcess(it.toInt()) } }
        }
        holders.remove(name)?.destroy()
        ttyds.remove(name)?.destroy()
        val removed = sessions.remove(name) != null
        persist(context)
        Log.i(TAG, "会话 $name 已关闭（剩 ${sessions.size} 个）")
        return removed
    }

    /** 启动（或复用）指定会话。dtach 无 server：holder 死 = 会话内容丢，
     *  探活失败即重建全新 shell（session_recreated 入时间线）。 */
    @Synchronized
    fun start(context: Context, name: String = MAIN): Session? {
        val rootfs = RootfsManager.rootfsDir(context)
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val sockHost = File(rootfs, "root/$name.sock")

        if (holders[name]?.isAlive != true && !unixSocketAlive(sockHost)) {
            sockHost.delete()
            val existed = readSessions(context).any { it.name == name }
            val holder = spawnHolder(context, name, sockHost) ?: return null
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
            sessions[name] = Session(name, port, token)
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
                        put(JSONObject().put("name", it.name).put("port", it.port).put("token", it.token))
                    }
                }.toString(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "注册表写入失败: $e")
        }
    }

    private fun spawnHolder(context: Context, name: String, sockHost: File): Process? {
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
                // key 自 2026-10-05 起不注入（D29）：登录 shell（bash -l）经 profile.d
                // source ~/.drydock/env.sh，DRYDOCK_API_KEY 等变量由用户在该文件自管
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
