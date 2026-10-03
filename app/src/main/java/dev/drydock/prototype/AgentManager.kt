package dev.drydock.prototype

import android.content.Context
import android.util.Log
import java.io.File
import java.net.URL
import org.json.JSONObject

/**
 * agent 链路（步骤 4 / AV3）：
 * - 版本固化安装（D8/D11）：Node 官方二进制 tarball（npmmirror 优先）+ npm 装 pin 版
 *   Claude Code（专有软件不打包，下载式安装）；
 * - 端点预设（D12）：GLM Anthropic 兼容 base_url，key 只从 SecretStore 按进程注入（I1）；
 * - 成果落袋（I4/D7）：环境内产物由宿主复制到 MediaStore Downloads/Drydock，
 *   不做通用文件管理器。
 */
object AgentManager {

    private const val TAG = "DrydockAgent"

    const val GLM_BASE_URL = "https://open.bigmodel.cn/api/anthropic"
    const val KEY_NAME = "glm_api_key"

    // Node 22 LTS（npmmirror 与 nodejs.org 的 SHASUMS256 交叉核对一致）
    const val NODE_VERSION = "v22.20.0"
    const val NODE_TARBALL = "node-v22.20.0-linux-arm64.tar.gz"
    const val NODE_SHA256 = "4181609e03dcb9880e7e5bf956061ecc0503c77a480c6631d868cb1f65a2c7dd"
    val NODE_MIRRORS = listOf(
        "https://registry.npmmirror.com/-/binary/node/$NODE_VERSION/",
        "https://nodejs.org/dist/$NODE_VERSION/",
    )

    const val CLAUDE_CODE_VERSION = "2.1.283"
    const val NPM_REGISTRY = "https://registry.npmmirror.com"

    data class Av3Result(
        val exitCode: Int,
        val output: String,        // 环境内命令原始输出（尾部）
        val resultText: String?,   // claude -p --output-format json 的 result 字段
        val producedFile: String?, // 环境内产物绝对路径（宿主侧校验存在）
        val landedUri: String?,    // MediaStore 落袋 uri；失败时为 "落袋失败: …"
    )

    fun av3Passed(r: Av3Result): Boolean =
        r.resultText != null && r.producedFile != null &&
            r.landedUri?.startsWith("content://") == true

    /** 注入给需要密钥的进程的环境变量（I1：只经 environment 传递，永不落文件）。 */
    fun agentEnv(context: Context): Map<String, String> {
        val key = SecretStore.load(context, KEY_NAME) ?: return emptyMap()
        return mapOf(
            "ANTHROPIC_BASE_URL" to GLM_BASE_URL,
            "ANTHROPIC_AUTH_TOKEN" to key,
            // 版本由宿主 pin（D11），环境内不自行升级
            "DISABLE_AUTOUPDATER" to "1",
        )
    }

    fun keyReady(context: Context): Boolean =
        SecretStore.load(context, KEY_NAME) != null

    /** Node 运行时层（agent 层与配方共用）：宿主侧下 tarball（镜像回退 + sha256），
     *  环境内解包 + npm 源配置。幂等，判定标记 NODE_RC=0。
     *  不装 ca-certificates：Node 自带 CA store，npm 走 npmmirror 实测不需要；
     *  而 ubuntu-base 没有 debconf，apt 装 ca-certificates 会在 postinst 半配置
     *  卡死（exec /usr/share/debconf/frontend not found），毒化 dpkg 状态。 */
    fun ensureNodeLayer(context: Context, onLog: (String) -> Unit = {}): RootfsManager.ExecResult {
        val tarball = File(context.cacheDir, NODE_TARBALL)
        val have = if (tarball.exists()) {
            val ok = RootfsManager.sha256(tarball) == NODE_SHA256
            if (!ok) tarball.delete()
            ok
        } else false
        if (!have) {
            var ok = false
            var lastErr = ""
            for (mirror in NODE_MIRRORS) {
                try {
                    onLog("下载 ${mirror.substringAfter("//").substringBefore("/")}…")
                    RootfsManager.download(URL(mirror + NODE_TARBALL), tarball) { }
                    if (RootfsManager.sha256(tarball) == NODE_SHA256) {
                        ok = true
                        break
                    }
                    lastErr = "sha256 mismatch on $mirror"
                } catch (e: Exception) {
                    lastErr = "$mirror: $e"
                    Log.w(TAG, "下载失败换下一镜像：$lastErr")
                }
            }
            if (!ok) return RootfsManager.ExecResult(1, "node 下载失败：$lastErr")
        }
        onLog("node tarball 校验通过，环境内安装…")
        val nodeDir = NODE_TARBALL.removeSuffix(".tar.gz")
        val cmd = """
            . /root/.drydock/mirrors 2>/dev/null || true
            NPM_REG="${'$'}{DRYDOCK_NPM_REGISTRY:-$NPM_REGISTRY}"
            command -v node >/dev/null 2>&1 && [ "${'$'}(node --version)" = "$NODE_VERSION" ] && { echo NODE_ALREADY; echo NODE_RC=0; exit 0; }
            mkdir -p /opt
            tar -xzf /node.tgz -C /opt 2>&1 | tail -2
            ln -sf /opt/$nodeDir/bin/node /usr/local/bin/node
            ln -sf /opt/$nodeDir/bin/npm /usr/local/bin/npm
            ln -sf /opt/$nodeDir/bin/npx /usr/local/bin/npx
            node --version || { echo NODE_RC=9 NODE_BROKEN; exit 0; }
            npm config set registry ${'$'}NPM_REG
            npm config set prefix /usr/local
            echo NODE_RC=0
        """.trimIndent()
        return RootfsManager.runInEnv(
            context,
            cmd,
            extraBinds = listOf("${tarball.absolutePath}:/node.tgz"),
        )
    }

    /** 幂等安装 agent 层（Claude Code）：Node 层先行。D25：此路径为原型验收仪器
     *  （AV3）保留，产品默认引导只含开源配方。判定标记 AGENT_RC=0。 */
    fun ensureAgentLayer(context: Context, onLog: (String) -> Unit): RootfsManager.ExecResult {
        val node = ensureNodeLayer(context, onLog)
        if (!node.output.contains("NODE_RC=0")) return node
        val cmd = """
            command -v claude >/dev/null 2>&1 && claude --version 2>/dev/null | grep -q $CLAUDE_CODE_VERSION \
              && node --version 2>/dev/null | grep -q $NODE_VERSION \
              && { echo AGENT_ALREADY; echo AGENT_RC=0; exit 0; }
            npm config set registry $NPM_REGISTRY
            npm config set prefix /usr/local
            npm install -g --no-fund --no-audit @anthropic-ai/claude-code@$CLAUDE_CODE_VERSION 2>&1 | tail -4
            NPM_RC=${'$'}{PIPESTATUS[0]}
            claude --version 2>/dev/null
            CLAUDE_RC=${'$'}?
            echo NODE=${'$'}(node --version) NPM_RC=${'$'}NPM_RC CLAUDE_RC=${'$'}CLAUDE_RC
            echo AGENT_RC=$(( NPM_RC == 0 && CLAUDE_RC == 0 ? 0 : 1 ))
        """.trimIndent()
        return RootfsManager.runInEnv(context, cmd)
    }

    /** AV3：环境内真实对话产出文件 → 宿主复制到 Downloads/Drydock（I4）。 */
    fun runAv3(context: Context): Av3Result {
        val prompt =
            "创建文件 av3-report.md：第一行写 AV3_OK，第二行写今天的日期，" +
                "第三行用一句话自我介绍。除此之外不要创建或修改任何其他文件。"
        val cmd = """
            rm -rf /root/av3 && mkdir -p /root/av3 && cd /root/av3
            # acceptEdits 而非 --dangerously-skip-permissions：proot -0 下进程恒为
            # root，Claude Code 拒绝 root 使用该旗标（实测报错拒跑）；acceptEdits
            # 自动批准工作区内的文件写入，AV3 只需要 Write。
            timeout 300 claude -p --permission-mode acceptEdits --output-format json \
              '$prompt' < /dev/null > .claude-out.json 2> .claude-err.txt
            echo CLAUDE_RC=${'$'}?
            echo '--- stderr tail ---'
            tail -c 400 .claude-err.txt
            echo '--- env files ---'
            ls -la /root/av3
        """.trimIndent()
        val r = RootfsManager.runInEnv(context, cmd, extraEnv = agentEnv(context))
        Log.i(TAG, "AV3 exit=${r.exitCode}\n${r.output.takeLast(2000)}")

        val av3Dir = File(RootfsManager.rootfsDir(context), "root/av3")
        val report = File(av3Dir, "av3-report.md")
        val resultText = try {
            JSONObject(File(av3Dir, ".claude-out.json").readText()).getString("result")
        } catch (_: Exception) {
            null
        }
        val landed = if (report.exists()) {
            try {
                Landing.toDownloads(context, report).toString()
            } catch (e: Exception) {
                Log.e(TAG, "落袋失败", e)
                "落袋失败: $e"
            }
        } else null
        return Av3Result(
            exitCode = r.exitCode,
            output = r.output,
            resultText = resultText,
            producedFile = if (report.exists()) "/root/av3/av3-report.md" else null,
            landedUri = landed,
        )
    }

    /** I1 反证：密钥注入后在环境内全量扫常见落点，任何命中即违规。输出供 verdict 引用。 */
    fun i1Sweep(context: Context): String {
        val cmd = """
            [ -n "${'$'}ANTHROPIC_AUTH_TOKEN" ] || { echo I1_SWEEP_SKIPPED_NO_KEY; exit 0; }
            for f in /root/.bashrc /root/.profile /root/.npmrc /root/.claude.json /etc/environment; do
              [ -f ${'$'}f ] && echo "${'$'}f: ${'$'}(grep -c ${'$'}ANTHROPIC_AUTH_TOKEN ${'$'}f)"
            done
            echo "claude-dir-hits: ${'$'}(grep -rl ${'$'}ANTHROPIC_AUTH_TOKEN /root/.claude 2>/dev/null | wc -l)"
            echo I1_SWEEP_DONE
        """.trimIndent()
        return RootfsManager.runInEnv(context, cmd, extraEnv = agentEnv(context)).output
    }
}
