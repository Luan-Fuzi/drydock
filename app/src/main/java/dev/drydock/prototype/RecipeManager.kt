package dev.drydock.prototype

import android.content.Context
import android.util.Log

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
            command -v ${recipe.bin} >/dev/null 2>&1 && ${recipe.bin} --version 2>/dev/null | grep -q '${recipe.version}' \
              && { echo RECIPE_ALREADY; echo RECIPE_RC=0; exit 0; }
            npm install -g --no-fund --no-audit ${recipe.extraInstallFlags} --registry=${'$'}NPM_REG ${recipe.npmPackage}@${recipe.version} 2>&1 | tail -3
            NPM_RC=${'$'}{PIPESTATUS[0]}
            if [ ${'$'}NPM_RC -ne 0 ]; then
              echo "首选源失败，换官方 npmjs 源重试…"
              npm install -g --no-fund --no-audit ${recipe.extraInstallFlags} --registry=$NPM_FALLBACK_SOURCE ${recipe.npmPackage}@${recipe.version} 2>&1 | tail -3
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
     * 把 EndpointStore 的端点四件套写进各已装 agent 的 provider 配置
     * （OpenCode: ~/.config/opencode/opencode.json；pi: ~/.pi/agent/models.json）。
     * D30：provider 段用真名（providerId，不再有 drydock 前缀/占位模型）；
     * key 引用 EndpointStore.envVar()（值在 env.sh）。内置目录厂商（DeepSeek 等）
     * 不会走到这——向导对它们只引导 env.sh，不生成配置段。
     */
    fun applyEndpointConfig(context: Context): RootfsManager.ExecResult {
        ensureMotd(context)
        val protocol = EndpointStore.protocol(context)
        val baseUrl = EndpointStore.baseUrl(context)
        val model = EndpointStore.model(context) ?: ""
        val providerId = EndpointStore.providerId(context)
        val envVar = EndpointStore.envVar(context)
        val hasEndpoint = protocol != null && !baseUrl.isNullOrBlank()
        val contextWindow = EndpointStore.contextWindow(context)
        val opencodeJson = if (hasEndpoint && baseUrl != null && protocol != null) {
            opencodeConfig(protocol, baseUrl, model, contextWindow, providerId, envVar)
        } else ""
        val piJson = if (hasEndpoint && baseUrl != null && protocol != null) {
            piConfig(protocol, baseUrl, model, contextWindow, providerId, envVar)
        } else ""
        val endpointInfo = "provider=$providerId\nprotocol=${protocol?.name ?: "-"}\nbase_url=${baseUrl ?: "-"}\nmodel=$model\ncontext=${EndpointStore.contextWindow(context) ?: "-"}\n# API key 走环境变量 $envVar（~/.drydock/env.sh，用户自管；可让 agent 帮你写入）\n"
        val envBlock = if (hasEndpoint && baseUrl != null) """
            cat > /etc/profile.d/drydock-env.sh <<ENVEOF
export ${envVar}_BASE_URL='$baseUrl'
export ${envVar}_MODEL='$model'
export ${envVar}_PROTOCOL='${protocol!!.name}'
# 用户自定义环境变量挂载点（让 agent 帮你加也行）
[ -f /root/.drydock/env.sh ] && . /root/.drydock/env.sh
ENVEOF
        """ else ""
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
        val model = EndpointStore.model(context) ?: return RootfsManager.ExecResult(2, "SMOKE_RC=2 no model")
        val providerId = EndpointStore.providerId(context)
        val prompt = "只回复四个字符：OK 了"
        val cmd = when (recipe.id) {
            "opencode" -> """
                cd /root && timeout 180 opencode run --model $providerId/$model '$prompt' < /dev/null 2>&1 | tail -5
                echo SMOKE_RC=${'$'}{PIPESTATUS[0]}
            """.trimIndent()
            "pi" -> """
                cd /root && timeout 180 pi --print --provider $providerId --model $providerId/$model '$prompt' < /dev/null 2>&1 | tail -5
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
            echo "内置目录厂商（DeepSeek/OpenAI 等）放标准变量名即自动识别（如 export DEEPSEEK_API_KEY=…）；"
            echo "自定义端点配置在 opencode.json / models.json，让 agent 帮你加；"
            echo "模型列表空 = 先查 env.sh 里的 key 变量名对不对。"
            MOTD
            echo MOTD_RC=${'$'}?
        """.trimIndent()
        return RootfsManager.runInEnv(context, motd)
    }

    /** 模型元数据（D30 收缩）：knownModels 表退役——预设表（EndpointStore.presets）
     *  自带 contextWindow，预设路径元数据从那里来；自定义路径只透传向导上下文字段给 pi
     *  （其缺省 maxTokens=16384 会作为 max_tokens 发出，不动），opencode 不写 limit
     *  （schema 要求 limit.context/output 双全，无 output 出处不编造，2026-10-05 实锤）。 */
    private fun modelMeta(model: String, contextWindow: Long?): Pair<String, String> {
        // pi 的 maxTokens 会作为 max_tokens 发给 API——GLM 端点限制 ≤131072（1210 实锤），
        // 不能照抄 contextWindow；无独立出处时收敛到 128k（保守值，介于 pi 缺省 16384 与端点上限之间）
        val pi = contextWindow?.let { ", \"contextWindow\": $it, \"maxTokens\": ${minOf(it, 131_072L)}" } ?: ""
        val oc = contextWindow?.let { ", \"limit\": {\"context\": $it, \"output\": ${minOf(it, 131_072L)}}}" } ?: ""
        return pi to oc
    }

    /** OpenCode provider 配置（D30）：provider 段用真名（providerId），key 引用向导
     *  约定的环境变量（值在 env.sh），不再有 drydock 前缀与占位模型。
     *  limit 只在有上下文出处时写（schema 要求 context/output 双全）。 */
    private fun opencodeConfig(
        protocol: EndpointStore.Protocol,
        baseUrl: String,
        model: String,
        contextWindow: Long?,
        providerId: String,
        envVar: String,
    ): String {
        val ocLimit = modelMeta(model, contextWindow).second
        val models = if (model.isNotBlank()) "\"$model\": {\"name\": \"$model\"$ocLimit}" else ""
        return when (protocol) {
            EndpointStore.Protocol.CHAT_COMPLETIONS -> """
                {
                  "${'$'}schema": "https://opencode.ai/config.json",
                  "provider": {
                    "$providerId": {
                      "npm": "@ai-sdk/openai-compatible",
                      "name": "$providerId",
                      "options": { "baseURL": "$baseUrl", "apiKey": "{env:$envVar}" },
                      "models": { $models }
                    }
                  }
                }
            """.trimIndent()
            EndpointStore.Protocol.RESPONSES -> """
                {
                  "${'$'}schema": "https://opencode.ai/config.json",
                  "provider": {
                    "$providerId": {
                      "npm": "@ai-sdk/openai",
                      "name": "$providerId",
                      "options": { "baseURL": "$baseUrl", "apiKey": "{env:$envVar}" },
                      "models": { $models }
                    }
                  }
                }
            """.trimIndent()
            EndpointStore.Protocol.ANTHROPIC -> """
                {
                  "${'$'}schema": "https://opencode.ai/config.json",
                  "provider": {
                    "$providerId": {
                      "npm": "@ai-sdk/anthropic",
                      "name": "$providerId",
                      "options": { "baseURL": "$baseUrl", "apiKey": "{env:$envVar}" },
                      "models": { $models }
                    }
                  }
                }
            """.trimIndent()
        }
    }

    /** pi provider 配置（D30）：真名 provider + envVar 插值；api 字段映射协议
     *  （openai-completions / openai-responses / anthropic-messages）。
     *  contextWindow/maxTokens 见 modelMeta——pi 对缺省值回退 128e3/16384 且 maxTokens
     *  会作为 max_tokens 发给 API（bundle 实证）。 */
    private fun piConfig(
        protocol: EndpointStore.Protocol,
        baseUrl: String,
        model: String,
        contextWindow: Long?,
        providerId: String,
        envVar: String,
    ): String {
        val api = when (protocol) {
            EndpointStore.Protocol.CHAT_COMPLETIONS -> "openai-completions"
            EndpointStore.Protocol.RESPONSES -> "openai-responses"
            EndpointStore.Protocol.ANTHROPIC -> "anthropic-messages"
        }
        val piMeta = modelMeta(model, contextWindow).first
        val models = if (model.isBlank()) "" else "{\"id\": \"$model\"$piMeta}"
        return """
            {
              "providers": {
                "$providerId": {
                  "baseUrl": "$baseUrl",
                  "api": "$api",
                  "apiKey": "${'$'}$envVar",
                  "models": [ $models ]
                }
              }
            }
        """.trimIndent()
    }
}
