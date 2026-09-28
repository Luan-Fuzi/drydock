package dev.drydock.prototype

import android.content.Context
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * 终端链路（步骤 3 / AV2）：环境内 ttyd + dtach 会话，宿主 WebView 连 localhost。
 * I2：随机端口 + 每次启动轮换的 token（ttyd -c basic auth），仅绑定 127.0.0.1。
 */
object TerminalManager {

    private const val TAG = "DrydockTerminal"

    data class Session(val port: Int, val token: String)

    @Volatile
    private var session: Session? = null

    @Volatile
    private var ttydProcess: Process? = null

    fun current(): Session? =
        if (ttydProcess?.isAlive == true) session else null

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

    @Volatile
    private var sessionHolder: Process? = null

    /** 启动（或复用）终端链路。会话由宿主持久的 proot+dtach 进程承载（不 daemonize，
     *  规避 tmux server 双 fork 在 proot ptrace 下卡死的问题，见 D18）；ttyd 只 attach。 */
    @Synchronized
    fun start(context: Context): Session? {
        // 复用前必须探活 dtach 会话：ttyd 进程活着 ≠ 会话活着（holder 可能已被杀）
        current()?.let {
            val sock = File(RootfsManager.rootfsDir(context), "root/main.sock")
            if (unixSocketAlive(sock)) return it
            Log.w(TAG, "ttyd 活着但 dtach 会话已死，整体重建")
            stop()
        }

        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val rootfs = RootfsManager.rootfsDir(context)
        val sockHost = File(rootfs, "root/main.sock")

        // 1. 幂等确保 dtach 会话（宿主持有进程）。socket 文件存在 ≠ 会话活着：
        //    AMS 主动杀（install/force-stop）清整个进程组，只留死 socket，必须探活。
        if (!sockHost.exists() || !unixSocketAlive(sockHost)) {
            sockHost.delete()
            val holder = ProcessBuilder(
                listOf(
                    File(nativeDir, "libproot.so").absolutePath,
                    "-0", "--link2symlink",
                    "-r", rootfs.absolutePath,
                    "-b", "/dev", "-b", "/proc", "-b", "/sys",
                    "-b", RootfsManager.l2sSelfBind(context),
                    "-w", "/root",
                    "/usr/bin/dtach", "-n", "/root/main.sock",
                    "/bin/bash", "-l",
                ),
            ).apply {
                redirectErrorStream(true)
                redirectOutput(File(context.cacheDir, "dtach.log"))
                environment().apply {
                    put("PROOT_LOADER", File(nativeDir, "libproot-loader.so").absolutePath)
                    put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
                    put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                    put("HOME", "/root")
                    put("TERM", "xterm-256color")
                    put("LANG", "C.UTF-8")
                    // I1：GLM 端点与密钥只经进程环境进终端会话（终端内 claude 直接可用）；
                    // key 在会话建立后变更的，需重建会话才生效
                    AgentManager.agentEnv(context).forEach { (k, v) -> put(k, v) }
                }
            }.start()
            val deadline = System.currentTimeMillis() + 15_000
            while (!sockHost.exists() && System.currentTimeMillis() < deadline) {
                if (!holder.isAlive) {
                    Log.e(TAG, "dtach 会话进程提前退出")
                    return null
                }
                Thread.sleep(200)
            }
            if (!sockHost.exists()) {
                Log.e(TAG, "dtach socket 等待超时")
                holder.destroy()
                return null
            }
            sessionHolder = holder
            Log.i(TAG, "dtach 会话就绪（宿主持有）")
        }

        val proot = File(nativeDir, "libproot.so")
        val port = 20000 + SecureRandom().nextInt(20000)
        val token = StringBuilder().apply {
            repeat(16) { append("0123456789abcdef"[SecureRandom().nextInt(16)]) }
        }.toString()

        val argv = listOf(
            proot.absolutePath,
            "-0", "--link2symlink",
            "-r", RootfsManager.rootfsDir(context).absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", RootfsManager.l2sSelfBind(context),
            "-w", "/root",
            "/usr/bin/ttyd",
            "-i", "127.0.0.1",
            "-p", port.toString(),
            "-c", "drydock:$token",
            "-W",
            // dom 渲染器：渲染文本落在 DOM，宿主可断言（AV2）；webgl/canvas 画布不可观测，
            // 其性能优化留给产品期（Q3 碎片化矩阵一并评估）
            "-t", "rendererType=dom",
            "/usr/bin/dtach", "-a", "/root/main.sock",
        )
        Log.i(TAG, "spawn ttyd port=$port token=${token.take(4)}…")

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
            redirectOutput(File(context.cacheDir, "ttyd.log"))
        }.start()

        // 等端口就绪（ttyd 启动需要几秒）
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) {
                Log.e(TAG, "ttyd 提前退出")
                return null
            }
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 500)
                    if (s.getInputStream().read() >= 0) { }
                }
                // 连接成功（HTTP 服务应答）
                session = Session(port, token)
                ttydProcess = p
                Log.i(TAG, "ttyd 就绪 port=$port")
                return session
            } catch (_: Exception) {
                Thread.sleep(400)
            }
        }
        Log.e(TAG, "ttyd 端口等待超时")
        p.destroy()
        return null
    }

    fun stop() {
        sessionHolder?.destroy()
        ttydProcess?.destroy()
        sessionHolder = null
        ttydProcess = null
        session = null
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
