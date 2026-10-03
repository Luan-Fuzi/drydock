package dev.drydock.prototype

import android.content.Context
import android.util.Log

/**
 * 配方系统（D25：宿主不绑定 agent，安装以配方提供——安装脚本 + 端点注入 + 模型配置）。
 * 默认引导只含开源配方（OpenCode、pi，均 MIT）；Claude Code 不进默认引导（用户自行安装）。
 * 端点零预置：协议三类映射到各 agent 自己的 provider 配置；key 只经进程环境
 * （DRYDOCK_API_KEY），配置文件用插值引用，环境内文件零明文（I1）。
 */
object RecipeManager {

    private const val TAG = "DrydockRecipe"

    /** 配方 = npm 包 + 版本 pin（升级 = 改这里 + 重走安装判据，同 RootfsManifest 口径）。 */
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
    val ALL = listOf(OPENCODE, PI)

    fun byId(id: String): Recipe? = ALL.firstOrNull { it.id == id }

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

    /** 已安装且版本匹配的配方 id 列表（读环境，不动手）。 */
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
        onLog("安装 ${recipe.title} ${recipe.version}…")
        val toolsSh = if (recipe.aptTools.isEmpty()) "" else """
            command -v rg >/dev/null 2>&1 && command -v fd >/dev/null 2>&1 || {
              apt-get update -o Acquire::Retries=2 >/dev/null 2>&1
              DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ${recipe.aptTools.joinToString(" ")} 2>&1 | tail -1
              [ -e /usr/local/bin/fd ] || ln -sf /usr/bin/fdfind /usr/local/bin/fd
            }
        """.trimIndent()
        // npm 镜像回退（D12 精神：回退序进安装脚本，零用户配置）——npmmirror 失败换官方源重试
        val cmd = """
            $toolsSh
            command -v ${recipe.bin} >/dev/null 2>&1 && ${recipe.bin} --version 2>/dev/null | grep -q '${recipe.version}' \
              && { echo RECIPE_ALREADY; echo RECIPE_RC=0; exit 0; }
            npm install -g --no-fund --no-audit ${recipe.extraInstallFlags} ${recipe.npmPackage}@${recipe.version} 2>&1 | tail -3
            NPM_RC=${'$'}{PIPESTATUS[0]}
            if [ ${'$'}NPM_RC -ne 0 ]; then
              echo "npmmirror 失败，换官方 npmjs 源重试…"
              npm install -g --no-fund --no-audit ${recipe.extraInstallFlags} --registry=https://registry.npmjs.org ${recipe.npmPackage}@${recipe.version} 2>&1 | tail -3
              NPM_RC=${'$'}{PIPESTATUS[0]}
            fi
            ${recipe.bin} --version 2>/dev/null; BIN_RC=${'$'}?
            echo RECIPE_RC=${'$'}(( NPM_RC == 0 && BIN_RC == 0 ? 0 : 1 ))
        """.trimIndent()
        val r = RootfsManager.runInEnv(context, cmd)
        Log.i(TAG, "ensure ${recipe.id}: ${r.output.takeLast(400)}")
        return r
    }

    /**
     * 把 EndpointStore 的协议/Base URL/模型写进各已装 agent 的 provider 配置
     * （OpenCode: ~/.config/opencode/opencode.json；pi: ~/.pi/agent/models.json），
     * key 以插值引用 DRYDOCK_API_KEY。未配置端点则跳过；顺带写 motd 与
     * ~/.drydock-endpoint（不含 key——「agent 是配置器」的可读信息面）。
     */
    fun applyEndpointConfig(context: Context): RootfsManager.ExecResult {
        ensureMotd(context)
        val protocol = EndpointStore.protocol(context)
        val baseUrl = EndpointStore.baseUrl(context)
        val model = EndpointStore.model(context) ?: ""
        if (protocol == null || baseUrl.isNullOrBlank()) {
            return RootfsManager.ExecResult(0, "CFG_SKIPPED_NO_ENDPOINT")
        }
        val opencodeJson = opencodeConfig(protocol, baseUrl, model, EndpointStore.contextWindow(context))
        val piJson = piConfig(protocol, baseUrl, model)
        val endpointInfo = "protocol=${protocol.name}\nbase_url=$baseUrl\nmodel=$model\ncontext=${EndpointStore.contextWindow(context) ?: "-"}\n# API key 不落文件：经环境变量 DRYDOCK_API_KEY 注入（改配置请用 EndpointStore 或让 agent 改本文件旁的说明）\n"
        val cmd = """
            printf '%s\n' '${endpointInfo.replace("'", "'\\''")}' > /root/.drydock-endpoint
            if command -v opencode >/dev/null 2>&1; then
              mkdir -p /root/.config/opencode
              cat > /root/.config/opencode/opencode.json <<'OCJSON'
$opencodeJson
OCJSON
              echo OPENCODE_CFG_WRITTEN
            fi
            if command -v pi >/dev/null 2>&1; then
              mkdir -p /root/.pi/agent
              cat > /root/.pi/agent/models.json <<'PIJSON'
$piJson
PIJSON
              echo PI_CFG_WRITTEN
            fi
            echo CFG_RC=0
        """.trimIndent()
        return RootfsManager.runInEnv(context, cmd)
    }

    /** headless 冒烟：出第一句话即止（短 prompt、小输出）。判定标记 SMOKE_RC=0。 */
    fun smoke(context: Context, recipe: Recipe): RootfsManager.ExecResult {
        val protocol = EndpointStore.protocol(context) ?: return RootfsManager.ExecResult(2, "SMOKE_RC=2 no endpoint")
        val model = EndpointStore.model(context).takeUnless { it.isNullOrBlank() } ?: "drydock-default"
        val providerId = if (recipe.id == "opencode" && protocol == EndpointStore.Protocol.ANTHROPIC) "anthropic" else "drydock"
        val prompt = "只回复四个字符：OK 了"
        val cmd = when (recipe.id) {
            "opencode" -> """
                cd /root && timeout 180 opencode run --model $providerId/$model '$prompt' < /dev/null 2>&1 | tail -5
                echo SMOKE_RC=${'$'}{PIPESTATUS[0]}
            """.trimIndent()
            "pi" -> """
                cd /root && timeout 180 pi --print --provider drydock --model drydock/$model '$prompt' < /dev/null 2>&1 | tail -5
                echo SMOKE_RC=${'$'}{PIPESTATUS[0]}
            """.trimIndent()
            else -> return RootfsManager.ExecResult(2, "unknown recipe")
        }
        val env = sessionEnv(context)
        return RootfsManager.runInEnv(context, cmd, extraEnv = env)
    }

    /** 会话/冒烟共用的注入环境：配置了端点走 DRYDOCK_*，否则回落 AV3 仪器的 GLM 注入（向后兼容）。 */
    fun sessionEnv(context: Context): Map<String, String> {
        if (EndpointStore.configured(context)) {
            return mapOf(
                "DRYDOCK_API_KEY" to (SecretStore.load(context, EndpointStore.KEY_NAME) ?: ""),
                "DRYDOCK_BASE_URL" to (EndpointStore.baseUrl(context) ?: ""),
            )
        }
        return AgentManager.agentEnv(context)
    }

    /** 首启 motd：「agent 是配置器」的终端内引导（D25）。 */
    private fun ensureMotd(context: Context): RootfsManager.ExecResult {
        val motd = """
            cat > /etc/profile.d/zz-drydock.sh <<'MOTD'
            # Drydock 引导（改本文件即改启动提示）
            echo "Drydock：agent 已就绪。直接运行 opencode 或 pi 开始；"
            echo "端点/模型配置见 ~/.drydock-endpoint（key 不落盘）；"
            echo "模型元数据（上下文窗口等）在 ~/.config/opencode/opencode.json 与 ~/.pi/agent/models.json——直接让 agent 帮你改；"
            echo "想改启动项或装更多工具，也让 agent 帮你配。"
            MOTD
            echo MOTD_RC=${'$'}?
        """.trimIndent()
        return RootfsManager.runInEnv(context, motd)
    }

    /** OpenCode provider 配置：协议 → @ai-sdk 适配包；Anthropic 走内置 provider 的 baseURL 覆盖（免运行时拉包）。
     *  contextWindow 可选写入 limit.context（自定义 provider 的上下文元数据 OpenCode 不会自动识别，
     *  真机实测默认显示 128k；用户在向导里填了才写）。 */
    private fun opencodeConfig(protocol: EndpointStore.Protocol, baseUrl: String, model: String, contextWindow: Long?): String {
        val modelEntry = buildString {
            if (model.isNotBlank()) {
                append("\"$model\": {\"name\": \"$model\"")
                contextWindow?.let { append(", \"limit\": {\"context\": $it}") }
                append("},")
            }
        }
        val models = modelEntry
        return when (protocol) {
            EndpointStore.Protocol.CHAT_COMPLETIONS -> """
                {
                  "${'$'}schema": "https://opencode.ai/config.json",
                  "provider": {
                    "drydock": {
                      "npm": "@ai-sdk/openai-compatible",
                      "name": "Drydock Endpoint",
                      "options": { "baseURL": "$baseUrl", "apiKey": "{env:DRYDOCK_API_KEY}" },
                      "models": { $models "drydock-default": {"name": "Drydock Endpoint 默认"} }
                    }
                  }
                }
            """.trimIndent()
            EndpointStore.Protocol.RESPONSES -> """
                {
                  "${'$'}schema": "https://opencode.ai/config.json",
                  "provider": {
                    "drydock": {
                      "npm": "@ai-sdk/openai",
                      "name": "Drydock Endpoint",
                      "options": { "baseURL": "$baseUrl", "apiKey": "{env:DRYDOCK_API_KEY}" },
                      "models": { $models "drydock-default": {"name": "Drydock Endpoint 默认"} }
                    }
                  }
                }
            """.trimIndent()
            EndpointStore.Protocol.ANTHROPIC -> """
                {
                  "${'$'}schema": "https://opencode.ai/config.json",
                  "provider": {
                    "anthropic": {
                      "name": "Anthropic 兼容端点",
                      "options": { "baseURL": "$baseUrl", "apiKey": "{env:DRYDOCK_API_KEY}" },
                      "models": { $models "drydock-default": {"name": "Drydock Endpoint 默认"} }
                    }
                  }
                }
            """.trimIndent()
        }
    }

    /** pi provider 配置：api 字段映射协议（openai-completions / openai-responses / anthropic-messages）。 */
    private fun piConfig(protocol: EndpointStore.Protocol, baseUrl: String, model: String): String {
        val api = when (protocol) {
            EndpointStore.Protocol.CHAT_COMPLETIONS -> "openai-completions"
            EndpointStore.Protocol.RESPONSES -> "openai-responses"
            EndpointStore.Protocol.ANTHROPIC -> "anthropic-messages"
        }
        val models = if (model.isBlank()) "" else "{\"id\": \"$model\"},"
        return """
            {
              "providers": {
                "drydock": {
                  "baseUrl": "$baseUrl",
                  "api": "$api",
                  "apiKey": "${'$'}DRYDOCK_API_KEY",
                  "models": [ $models {"id": "drydock-default"} ]
                }
              }
            }
        """.trimIndent()
    }
}
