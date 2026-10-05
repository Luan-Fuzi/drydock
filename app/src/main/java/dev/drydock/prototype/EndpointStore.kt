package dev.drydock.prototype

import android.content.Context

/**
 * 端点配置存储（D25：零预置厂商，用户先选协议再配 Base URL）。
 * 协议、Base URL、模型 id 进 SharedPreferences；API key 不经本类——2026-10-05 起
 * 走环境变量 DRYDOCK_API_KEY（~/.drydock/env.sh 用户自管，D29），配置文件只写引用。
 */
object EndpointStore {

    enum class Protocol(val label: String) {
        CHAT_COMPLETIONS("Chat Completions"),
        RESPONSES("Responses"),
        ANTHROPIC("Anthropic Messages"),
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("drydock", Context.MODE_PRIVATE)

    fun save(context: Context, protocol: Protocol, baseUrl: String, model: String, contextWindow: String = "") {
        prefs(context).edit()
            .putString("endpoint_protocol", protocol.name)
            .putString("endpoint_base_url", baseUrl.trim().trimEnd('/'))
            .putString("endpoint_model", model.trim())
            .putString("endpoint_context", contextWindow.trim())
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove("endpoint_protocol").remove("endpoint_base_url").remove("endpoint_model")
            .remove("endpoint_context")
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
