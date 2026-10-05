package dev.drydock.prototype

import android.content.Context

/**
 * 端点配置存储（D25 零预置 → D30 修订：高频国产 plan 预设表 + 自定义）。
 * 协议、Base URL、模型 id、provider 名进 SharedPreferences；API key 不经本类——
 * 走环境变量（~/.drydock/env.sh 用户自管，D29），配置文件只写引用。
 * 内置目录已覆盖的厂商（DeepSeek/OpenAI 等）不进本类：向导引导往 env.sh 放标准
 * 变量名即可，工具自动识别（D30）。
 */
object EndpointStore {

    enum class Protocol(val label: String) {
        CHAT_COMPLETIONS("Chat Completions"),
        RESPONSES("Responses"),
        ANTHROPIC("Anthropic Messages"),
    }

    /** 高频国产 plan 预设（D30）：四件套一次给对——把 coding plan 端点路径/协议/
     *  模型白名单这些踩过的坑固化成数据（D25 实测：coding/paas/v4 订阅端点 vs
     *  /api/paas/v4 按量端点 1113）。provider 名即配置段真名（无 drydock 前缀）。
     *  envVar 是约定俗成的变量名（用户可改，生成配置跟随）。 */
    data class Preset(
        val id: String,
        val label: String,
        val protocol: Protocol,
        val baseUrl: String,
        val model: String,
        val contextWindow: Long,
        val providerId: String,
        val envVar: String,
        val note: String,
    )

    val presets = listOf(
        Preset(
            "glm-coding", "GLM Coding Plan（智谱 open.bigmodel.cn）",
            Protocol.CHAT_COMPLETIONS, "https://open.bigmodel.cn/api/coding/paas/v4",
            "glm-5.3-flash", 1_048_576L, "zai-coding", "ZAI_CODING_KEY",
            "订阅套餐专用端点；按量端点 /api/paas/v4 会报 1113",
        ),
        Preset(
            "glm-anthropic", "GLM Coding Plan（Anthropic 兼容）",
            Protocol.ANTHROPIC, "https://open.bigmodel.cn/api/anthropic",
            "glm-5.3-flash", 1_048_576L, "zai-coding", "ZAI_CODING_KEY",
            "同订阅的 Anthropic 协议路径；pi 建议选这个",
        ),
    )

    fun presetById(id: String): Preset? = presets.firstOrNull { it.id == id }

    private fun prefs(context: Context) =
        context.getSharedPreferences("drydock", Context.MODE_PRIVATE)

    /** providerId：配置段真名（D30 起必填；旧数据缺省时按 baseUrl 推）。 */
    fun save(
        context: Context,
        protocol: Protocol,
        baseUrl: String,
        model: String,
        contextWindow: String = "",
        providerId: String = "",
        envVar: String = "DRYDOCK_API_KEY",
    ) {
        prefs(context).edit()
            .putString("endpoint_protocol", protocol.name)
            .putString("endpoint_base_url", baseUrl.trim().trimEnd('/'))
            .putString("endpoint_model", model.trim())
            .putString("endpoint_context", contextWindow.trim())
            .putString("endpoint_provider_id", providerId.trim())
            .putString("endpoint_env_var", envVar.trim())
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove("endpoint_protocol").remove("endpoint_base_url").remove("endpoint_model")
            .remove("endpoint_context").remove("endpoint_provider_id").remove("endpoint_env_var")
            .apply()
    }

    fun protocol(context: Context): Protocol? =
        prefs(context).getString("endpoint_protocol", null)?.let { runCatching { Protocol.valueOf(it) }.getOrNull() }

    fun baseUrl(context: Context): String? =
        prefs(context).getString("endpoint_base_url", null)?.takeIf { it.isNotBlank() }

    fun model(context: Context): String? =
        prefs(context).getString("endpoint_model", null)?.takeIf { it.isNotBlank() }

    /** 上下文窗口（token 数，可选）：OpenCode 的 limit.context；pi 无此字段（上游限制，
     *  自定义 provider 固定默认显示），仅在配置信息文件里展示。 */
    fun contextWindow(context: Context): Long? =
        prefs(context).getString("endpoint_context", null)?.trim()?.toLongOrNull()

    /** 配置段 provider 真名（D30）；旧数据/自定义未填时按 baseUrl 主机名推。 */
    fun providerId(context: Context): String =
        prefs(context).getString("endpoint_provider_id", null)?.takeIf { it.isNotBlank() }
            ?: baseUrl(context)?.substringAfter("://")?.substringBefore('/')
                ?.split(".")?.takeLast(2)?.joinToString("-")
            ?: "custom"

    /** key 的环境变量名（配置文件里 {env:XX}/$XX 引用的名字；D29 后 key 值在 env.sh）。 */
    fun envVar(context: Context): String =
        prefs(context).getString("endpoint_env_var", null)?.takeIf { it.isNotBlank() }
            ?: "DRYDOCK_API_KEY"

    /** 两要素齐（协议 + Base URL）才算已配置；模型可空（agent 端有各自的默认选择）。
     *  key 不在此判断里——它走环境变量，由用户自管（D29）。 */
    fun configured(context: Context): Boolean =
        protocol(context) != null && !baseUrl(context).isNullOrBlank()

    fun summary(context: Context): String =
        if (configured(context)) "${protocol(context)!!.label} · ${baseUrl(context)}" else "未配置"

    fun wizardDone(context: Context): Boolean = prefs(context).getBoolean("wizard_done", false)

    fun setWizardDone(context: Context) {
        prefs(context).edit().putBoolean("wizard_done", true).apply()
    }
}
