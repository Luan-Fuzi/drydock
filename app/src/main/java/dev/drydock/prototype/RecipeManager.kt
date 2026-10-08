package dev.drydock.prototype

import android.content.Context
import java.io.File
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配方系统（D25：宿主不绑定 agent，安装以配方提供——安装脚本 + 端点注入 + 模型配置）。
 * 默认引导只含开源配方（OpenCode、pi，均 MIT）；Claude Code 不进默认引导（用户自行安装）。
 * 端点零预置：协议三类映射到各 agent 自己的 provider 配置；key 走环境变量
 * （DRYDOCK_API_KEY），配置文件只写引用——值由用户自管（~/.drydock/env.sh 或让
 * agent 代配，2026-10-05 起宿主不再托管密钥，见 decisions D29）。
 */
object RecipeManager {

    private const val TAG = "DrydockRecipe"

    /** 官方源兜底（回退序第二位；首位默认 npmmirror，可被 ~/.drydock/mirrors 覆盖）。 */
    const val NPM_PRIMARY_SOURCE = "https://registry.npmmirror.com"
    const val NPM_FALLBACK_SOURCE = "https://registry.npmjs.org"

    /** 配方 = npm 包 + 基线参考版本。2026-10-07 用户定调：不 pin 版本，安装走 npm
     *  latest（opencode 自带 autoupdate 亦随之放行，断链由 BinDoctor 兜底）；version
     *  仅作展示/文档参考，不参与已装判定与安装。 */
    data class Recipe(
        val id: String,
        val title: String,
        val npmPackage: String,
        val version: String,
        val bin: String,
        val extraInstallFlags: String = "",
        /** 随配方经 apt 预装的工具（走国内镜像源）。pi 首启会从 GitHub releases 拉
         *  fd/ripgrep，国内网络实测挂死；PATH 里已有则 pi 跳过下载（真机 2026-10-03 实证）。 */
        val aptTools: List<String> = emptyList(),
    )

    val OPENCODE = Recipe(
        id = "opencode", title = "OpenCode",
        npmPackage = "opencode-ai", version = "1.18.34", bin = "opencode",
    )
    val PI = Recipe(
        id = "pi", title = "pi",
        npmPackage = "@earendil-works/pi-coding-agent", version = "1.0.0", bin = "pi",
        // 官方安装口径带 --ignore-scripts（纯 JS 包，无 postinstall 需求）
        extraInstallFlags = "--ignore-scripts",
        aptTools = listOf("ripgrep", "fd-find"),
    )
    val DSH = Recipe(
        id = "dsh", title = "DSH（DeepSeek Harness）",
        npmPackage = "@deepseek-ai/dsh", version = "0.2.0-rc.2", bin = "dsh",
    )
    val ALL = listOf(OPENCODE, PI, DSH)

    fun byId(id: String): Recipe? = ALL.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }

    /** 已安装配方（prefs 记录，主线程可读；真实安装态以 ensure 幂等检查为准）。 */
    fun installedIds(context: Context): List<String> =
        context.getSharedPreferences("drydock", Context.MODE_PRIVATE)
            .getString("recipes_installed", "").orEmpty().split(",").filter { it.isNotBlank() }

    fun markInstalled(context: Context, id: String) {
        val cur = installedIds(context).toMutableSet()
        cur.add(id)
        context.getSharedPreferences("drydock", Context.MODE_PRIVATE)
            .edit().putString("recipes_installed", cur.joinToString(",")).apply()
    }

    /** 已安装配方 id 列表（读环境不动手；bin 在即算，基线版本不符记 id:other 不影响判定）。 */
    fun installed(context: Context): List<String> {
        val checks = ALL.joinToString(" ") { r ->
            "command -v ${r.bin} >/dev/null 2>&1 && ${r.bin} --version 2>/dev/null | grep -q '${r.version}' && echo ${r.id}" +
                " || command -v ${r.bin} >/dev/null 2>&1 && echo ${r.id}:other"
        }
        val out = RootfsManager.runInEnv(context, checks).output
        return ALL.map { it.id }.filter { id -> out.lineSequence().any { it.trim() == id || it.trim().startsWith("$id:") } }
    }

    /** 幂等安装配方（Node 层先行）。判定标记 RECIPE_RC=0。 */
    fun ensure(context: Context, recipe: Recipe, onLog: (String) -> Unit = {}): RootfsManager.ExecResult {
        val node = AgentManager.ensureNodeLayer(context, onLog)
        if (!node.output.contains("NODE_RC=0")) {
            return RootfsManager.ExecResult(1, "Node 层失败：${node.output.takeLast(300)}")
        }
        onLog("安装 ${recipe.title}（npm 最新版）…")
        val toolsSh = if (recipe.aptTools.isEmpty()) "" else """
            TOOLS_RC=0
            command -v rg >/dev/null 2>&1 && command -v fd >/dev/null 2>&1 || {
              apt-get update -o Acquire::Retries=2 >/dev/null 2>&1
              DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ${recipe.aptTools.joinToString(" ")} 2>&1 | tail -1
            }
            # 工具缺失会让 pi 首启转 GitHub releases 下载（国内网络挂死）——校验进 RECIPE_RC，
            # 不许静默带病通过（夜批实锤：apt 失败时 fd 符号链接盲建悬空、rg 缺失）
            command -v rg >/dev/null 2>&1 && command -v fd >/dev/null 2>&1 || TOOLS_RC=1
            [ -e /usr/bin/fdfind ] && { [ -e /usr/local/bin/fd ] || ln -sf /usr/bin/fdfind /usr/local/bin/fd; }
            [ -e /usr/local/bin/fd ] || TOOLS_RC=1
        """.trimIndent()
        // npm 镜像回退（D12 精神：默认回退序零配置；~/.drydock/mirrors 可覆盖首选源）
        val cmd = """
            . /root/.drydock/mirrors 2>/dev/null || true
            NPM_REG="${'$'}{DRYDOCK_NPM_REGISTRY:-$NPM_PRIMARY_SOURCE}"
            echo NPM_REG=${'$'}NPM_REG
            $toolsSh
            command -v ${recipe.bin} >/dev/null 2>&1 \
              && { echo RECIPE_ALREADY; echo RECIPE_RC=0; exit 0; }
            npm install -g --no-fund --no-audit ${recipe.extraInstallFlags} --registry=${'$'}NPM_REG ${recipe.npmPackage}@latest 2>&1 | tail -3
            NPM_RC=${'$'}{PIPESTATUS[0]}
            if [ ${'$'}NPM_RC -ne 0 ]; then
              echo "首选源失败，换官方 npmjs 源重试…"
              npm install -g --no-fund --no-audit ${recipe.extraInstallFlags} --registry=$NPM_FALLBACK_SOURCE ${recipe.npmPackage}@latest 2>&1 | tail -3
              NPM_RC=${'$'}{PIPESTATUS[0]}
            fi
            ${recipe.bin} --version 2>/dev/null; BIN_RC=${'$'}?
            echo RECIPE_RC=${'$'}(( NPM_RC == 0 && BIN_RC == 0 && ${if (recipe.aptTools.isEmpty()) "0" else "TOOLS_RC"} == 0 ? 0 : 1 ))
        """.trimIndent()
        val r = RootfsManager.runInEnv(context, cmd)
        Log.i(TAG, "ensure ${recipe.id}: ${r.output.takeLast(400)}")
        return r
    }

    /**
     * 把 EndpointStore 端点列表重算进各已装 agent 的 provider 配置（D30 终版）。
     * 只管理列表内的 provider 名：opencode.json 的 provider 对象按 key 合并（列表外
     * 的手写段原样保留）、pi models.json 的 providers 数组按 provider 名替换。
     * 列表为空且无旧数据时不动配置文件，只顺带写 motd 与 env.sh/mirrors 模板。
     */
    fun applyEndpointConfig(context: Context): RootfsManager.ExecResult {
        ensureMotd(context)
        val endpoints = EndpointStore.all(context)
        val envBlock = endpoints.firstOrNull()?.let { e ->
            """
            cat > /etc/profile.d/drydock-env.sh <<ENVEOF
export DRYDOCK_BASE_URL='${e.baseUrl}'
export DRYDOCK_MODEL='${e.model}'
export DRYDOCK_PROTOCOL='${e.protocol.name}'
# 用户自定义环境变量挂载点（让 agent 帮你加也行）
[ -f /root/.drydock/env.sh ] && . /root/.drydock/env.sh
ENVEOF
            """.trimIndent()
        } ?: ""
        val endpointInfo = if (endpoints.isEmpty()) "# 尚未添加自定义端点（设置 → Coding 端点）\n" else
            endpoints.joinToString("\n") { e ->
                "provider=${e.providerId}\nprotocol=${e.protocol.name}\nbase_url=${e.baseUrl}\nmodel=${e.model}\ncontext=${e.contextWindow ?: "-"}\noutput=${e.outputTokens ?: "-"}\nkey_env=${e.envVar}\n---"
            } + "\n# API key 走环境变量（~/.drydock/env.sh，用户自管；可让 agent 帮你写入）\n"
        // 合并脚本固定在 assets/dd-merge.js（不随端点变化）；端点数据经 JSON 注入 argv——
        // 消灭 Kotlin 字符串拼 JS 的多层转义（D30 三轮 bug 的根源）
        fun ocEntry(e: EndpointStore.Endpoint): JSONObject {
            val apiNpm = when (e.protocol) {
                EndpointStore.Protocol.CHAT_COMPLETIONS -> "@ai-sdk/openai-compatible"
                EndpointStore.Protocol.RESPONSES -> "@ai-sdk/openai"
                EndpointStore.Protocol.ANTHROPIC -> "@ai-sdk/anthropic"
            }
            val models = JSONObject()
            if (e.model.isNotBlank()) {
                val mObj = JSONObject().put("name", e.model)
                // output 显式值优先；只有上下文时回落 min(上下文, 128k)（历史口径，t3 断言依赖）
                if (e.contextWindow != null || e.outputTokens != null) {
                    val lim = JSONObject()
                    e.contextWindow?.let { lim.put("context", it) }
                    lim.put("output", e.outputTokens ?: minOf(e.contextWindow!!, 131_072L))
                    mObj.put("limit", lim)
                }
                models.put(e.model, mObj)
            }
            return JSONObject()
                .put("npm", apiNpm)
                .put("name", e.providerId)
                .put("options", JSONObject()
                    .put("baseURL", e.baseUrl)
                    .put("apiKey", "{env:${e.envVar}}"))
                .put("models", models)
        }
        fun piEntry(e: EndpointStore.Endpoint): JSONObject {
            val api = when (e.protocol) {
                EndpointStore.Protocol.CHAT_COMPLETIONS -> "openai-completions"
                EndpointStore.Protocol.RESPONSES -> "openai-responses"
                EndpointStore.Protocol.ANTHROPIC -> "anthropic-messages"
            }
            val models = JSONArray()
            if (e.model.isNotBlank()) {
                val m = JSONObject().put("id", e.model)
                e.contextWindow?.let { m.put("contextWindow", it) }
                // pi 的 maxTokens 即发给 API 的 max_tokens（D31：GLM 端点 ≤131072 实锤）
                val maxT = e.outputTokens ?: e.contextWindow?.let { minOf(it, 131_072L) }
                maxT?.let { m.put("maxTokens", it) }
                models.put(m)
            }
            return JSONObject()
                .put("providerId", e.providerId)
                .put("baseUrl", e.baseUrl)
                .put("api", api)
                .put("apiKey", "$" + e.envVar)
                .put("models", models)
        }
        val ocJson = JSONObject()
        endpoints.forEach { e -> ocJson.put(e.providerId, ocEntry(e)) }
        val piJson = JSONArray()
        endpoints.forEach { piJson.put(piEntry(it)) }
        val mergeJs = assetsJs(context, "dd-merge.js")
        val cmd = """
            . /root/.drydock/mirrors 2>/dev/null || true
            printf '%s\n' '${endpointInfo.replace("'", "'\\''")}' > /root/.drydock-endpoint
            ${envBlock.trimIndent()}
            mkdir -p /root/.drydock
            [ -f /root/.drydock/env.sh ] || printf '# 用户自定义环境变量，每个新 shell 生效；例如：\n# export HTTP_PROXY=http://127.0.0.1:7890\n' > /root/.drydock/env.sh
            [ -f /root/.drydock/mirrors ] || printf '# 镜像覆盖（可选）：\n# export DRYDOCK_NPM_REGISTRY=https://registry.npmjs.org\n# export DRYDOCK_APT_MIRROR=http://mirrors.ustc.edu.cn/ubuntu-ports\n' > /root/.drydock/mirrors
            if [ -n "${'$'}{DRYDOCK_APT_MIRROR:-}" ]; then
              sed -i "s|^[[:space:]]*URIs:.*|        URIs: ${'$'}DRYDOCK_APT_MIRROR|; /^           /d" /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null
              echo APT_MIRROR_APPLIED
            fi
            command -v node >/dev/null 2>&1 || { echo CFG_RC=1 NO_NODE; exit 0; }
            node $mergeJs '${ocJson}' '${piJson}'
            echo CFG_RC=0
        """.trimIndent()
        return RootfsManager.runInEnv(context, cmd)
    }

    /** assets 脚本落盘到 rootfs 的 /tmp（proot 内可见），返回环境内路径。 */
    private fun assetsJs(context: Context, name: String): String {
        val f = File(RootfsManager.rootfsDir(context), "tmp/$name")
        context.assets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
        return "/tmp/$name"
    }

    /** headless 冒烟：对列表第一个端点出第一句话即止。判定标记 SMOKE_RC=0。 */
    fun smoke(context: Context, recipe: Recipe): RootfsManager.ExecResult {
        val e = EndpointStore.all(context).firstOrNull()
            ?: return RootfsManager.ExecResult(2, "SMOKE_RC=2 no endpoint")
        val prompt = "只回复四个字符：OK 了"
        val cmd = when (recipe.id) {
            "opencode" -> """
                cd /root && timeout 180 opencode run --model ${e.providerId}/${e.model} '$prompt' < /dev/null 2>&1 | tail -5
                echo SMOKE_RC=${'$'}{PIPESTATUS[0]}
            """.trimIndent()
            "pi" -> """
                cd /root && timeout 180 pi --print --provider ${e.providerId} --model ${e.providerId}/${e.model} '$prompt' < /dev/null 2>&1 | tail -5
                echo SMOKE_RC=${'$'}{PIPESTATUS[0]}
            """.trimIndent()
            "dsh" -> """
                cd /root && timeout 180 dsh --profile headless '$prompt' < /dev/null 2>&1 | tail -5
                echo SMOKE_RC=${'$'}{PIPESTATUS[0]}
            """.trimIndent()
            else -> return RootfsManager.ExecResult(2, "unknown recipe")
        }
        // key 住 ~/.drydock/env.sh（runInEnv 已统一 source）；没配 key 时由端点返回 401，如实透传
        return RootfsManager.runInEnv(context, cmd)
    }

    /** 镜像源 GUI 落地（D27）：写 ~/.drydock/mirrors（清空即回默认回退链）；
     *  apt 覆盖即时重写 sources；默认选择则恢复出厂双 URI 源。 */
    fun applyMirrors(context: Context, aptChoice: String, npmUrl: String?): RootfsManager.ExecResult {
        val aptUrl = when (aptChoice) {
            "tuna" -> "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"
            "ustc" -> "http://mirrors.ustc.edu.cn/ubuntu-ports"
            "nju" -> "http://mirror.nju.edu.cn/ubuntu-ports"
            "aliyun" -> "http://mirrors.aliyun.com/ubuntu-ports"
            "official" -> "http://ports.ubuntu.com/ubuntu-ports"
            else -> null
        }
        if (aptUrl == null) {
            // apt 选「默认」即恢复出厂双 URI 源（npm 选择与否不影响 apt 的恢复）
            RootfsManager.resetAptSources(context)
        }
        val lines = buildString {
            if (npmUrl != null) append("export DRYDOCK_NPM_REGISTRY=$npmUrl\n")
            if (aptUrl != null) append("export DRYDOCK_APT_MIRROR=$aptUrl\n")
        }
        val cmd = """
            mkdir -p /root/.drydock
            cat > /root/.drydock/mirrors <<'MEOF'
${if (lines.isBlank()) "# 默认回退链（覆盖已清空）\n" else lines}MEOF
            . /root/.drydock/mirrors 2>/dev/null || true
            if [ -n "${'$'}{DRYDOCK_APT_MIRROR:-}" ]; then
              sed -i "s|^[[:space:]]*URIs:.*|        URIs: ${'$'}DRYDOCK_APT_MIRROR|; /^           /d" /etc/apt/sources.list.d/ubuntu.sources
              echo APT_SOURCE_NOW=${'$'}DRYDOCK_APT_MIRROR
            fi
            echo MIRROR_RC=0
        """.trimIndent()
        return RootfsManager.runInEnv(context, cmd)
    }

    /** 首启 motd：「agent 是配置器」的终端内引导（D25）。 */
    private fun ensureMotd(context: Context): RootfsManager.ExecResult {
        val motd = """
            cat > /etc/profile.d/zz-drydock.sh <<'MOTD'
            # Drydock 引导（改本文件即改启动提示）
            echo "Drydock：agent 已就绪。直接运行 opencode 或 pi 开始；"
            echo "API key 走环境变量（~/.drydock/env.sh，新会话生效）——发给 agent 代写或自己编辑；"
            echo "内置目录厂商（DeepSeek/智谱 GLM/OpenAI 等）放标准变量名即自动识别——GLM Coding Plan 用 ZHIPU_API_KEY，";
            echo "自定义端点配置在 opencode.json / models.json，让 agent 帮你改；DSH 用户：dsh web 起服务，"
echo "把日志里带 token 的网址复制到浏览器打开；key 放 DEEPSEEK_API_KEY（env.sh）。";
            echo "模型列表空 = 先查 env.sh 里的 key 变量名对不对。"
            MOTD
            echo MOTD_RC=${'$'}?
        """.trimIndent()
        return RootfsManager.runInEnv(context, motd)
    }
}
