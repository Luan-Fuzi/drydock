package dev.drydock.prototype

import android.content.Context
import java.net.InetSocketAddress
import java.net.Socket

/**
 * D25 文件互通第四项 / 阶段 3 端口面板：列环境内可达本机回环的监听端口。
 * proot 不隔离网络——环境内服务即手机本机回环服务，系统浏览器可直接打开
 * http://127.0.0.1:port（D25 已记）；本面板只解决可发现性。
 * 探测方式为本地 connect 探活：/proc/net/tcp 对 app 域被 SELinux 屏蔽（实测），
 * 而回环 connect 无需任何权限、拒绝即时返回。
 */
object PortPanel {

    data class ListenPort(val port: Int, val isTerminal: Boolean)

    /** 常见开发/预览端口（开发者起服务的习惯范围，逐步补充）。 */
    private val COMMON_PORTS = listOf(
        3000, 3001, 3300, 4000, 4173, 4200, 5000, 5173, 5174, 8000, 8080, 8081, 8501, 8888, 9000,
    )

    fun listening(context: Context): List<ListenPort> {
        val termPorts = try {
            TerminalManager.readSessions(context).map { it.port }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
        val found = LinkedHashMap<Int, Boolean>()
        termPorts.forEach { p -> if (alive(p)) found[p] = true }
        COMMON_PORTS.filter { it !in found }.forEach { p -> if (alive(p)) found[p] = false }
        return found.map { (port, isTerm) -> ListenPort(port, isTerm) }
    }

    private fun alive(port: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 100)
            true
        }
    } catch (_: Exception) {
        false
    }
}
