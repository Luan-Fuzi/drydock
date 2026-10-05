package dev.drydock.prototype

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义端点列表（D30 终版）：设置页表单追加、列表可见、逐条删除。
 * 每条 = 两个工具配置文件里的一个 provider 段（同构 JSON 的两种拼写，RecipeManager
 * 负责映射写入——只管理本列表的 provider 名，agent/用户手写的其他段不碰）。
 * 内置目录厂商（DeepSeek/OpenAI 等）不进列表：往 env.sh 放标准变量名即可自动识别。
 * API key 不经本类——值在 ~/.drydock/env.sh（D29），条目只存变量名引用。
 */
object EndpointStore {

    enum class Protocol(val label: String) {
        CHAT_COMPLETIONS("Chat Completions"),
        RESPONSES("Responses"),
        ANTHROPIC("Anthropic Messages"),
    }

    data class Endpoint(
        val providerId: String,
        val protocol: Protocol,
        val baseUrl: String,
        val model: String,
        val contextWindow: Long?,   // 可选；不填走工具默认
        val envVar: String,
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences("drydock", Context.MODE_PRIVATE)

    fun list(context: Context): List<Endpoint> = try {
        val txt = prefs(context).getString("endpoints", null) ?: "[]"
        val arr = JSONArray(txt)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Endpoint(
                providerId = o.getString("providerId"),
                protocol = Protocol.valueOf(o.getString("protocol")),
                baseUrl = o.getString("baseUrl"),
                model = o.getString("model"),
                contextWindow = if (o.isNull("contextWindow")) null else o.getLong("contextWindow"),
                envVar = o.getString("envVar"),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun persist(context: Context, items: List<Endpoint>) {
        val arr = JSONArray()
        items.forEach { e ->
            arr.put(JSONObject()
                .put("providerId", e.providerId)
                .put("protocol", e.protocol.name)
                .put("baseUrl", e.baseUrl)
                .put("model", e.model)
                .put("contextWindow", e.contextWindow ?: JSONObject.NULL)
                .put("envVar", e.envVar))
        }
        prefs(context).edit().putString("endpoints", arr.toString()).apply()
    }

    /** 追加一条（表单提交）。providerId 留空按 baseUrl 主机名推（如 open.bigmodel.cn → bigmodel-cn）。 */
    fun add(
        context: Context,
        protocol: Protocol,
        baseUrl: String,
        model: String,
        contextWindow: Long?,
        envVar: String,
        providerId: String = "",
    ): Endpoint {
        val id = providerId.trim().ifBlank {
            baseUrl.trim().trimEnd('/').substringAfter("://").substringBefore('/')
                .split(".").takeLast(2).joinToString("-")
                .replace(Regex("[^a-zA-Z0-9_-]"), "-").ifBlank { "custom" }
        }
        val e = Endpoint(
            providerId = id,
            protocol = protocol,
            baseUrl = baseUrl.trim().trimEnd('/'),
            model = model.trim(),
            contextWindow = contextWindow,
            envVar = envVar.trim().ifBlank { "DRYDOCK_API_KEY" },
        )
        persist(context, list(context) + e)
        return e
    }

    fun remove(context: Context, providerId: String) {
        persist(context, list(context).filterNot { it.providerId == providerId })
    }

    // ---------- 旧单端点字段（D25-D30 初版遗留）的兼容读：列表为空时回落 ----------

    fun legacyEndpoint(context: Context): Endpoint? {
        val protocol = prefs(context).getString("endpoint_protocol", null)
            ?.let { runCatching { Protocol.valueOf(it) }.getOrNull() } ?: return null
        val baseUrl = prefs(context).getString("endpoint_base_url", null)?.takeIf { it.isNotBlank() } ?: return null
        return Endpoint(
            providerId = prefs(context).getString("endpoint_provider_id", null)?.takeIf { it.isNotBlank() }
                ?: "custom",
            protocol = protocol,
            baseUrl = baseUrl,
            model = prefs(context).getString("endpoint_model", null) ?: "",
            contextWindow = prefs(context).getString("endpoint_context", null)?.trim()?.toLongOrNull(),
            envVar = prefs(context).getString("endpoint_env_var", null)?.takeIf { it.isNotBlank() }
                ?: "DRYDOCK_API_KEY",
        )
    }

    fun all(context: Context): List<Endpoint> =
        list(context).ifEmpty { legacyEndpoint(context)?.let { listOf(it) } ?: emptyList() }

    fun wizardDone(context: Context): Boolean = prefs(context).getBoolean("wizard_done", false)

    fun setWizardDone(context: Context) {
        prefs(context).edit().putBoolean("wizard_done", true).apply()
    }
}
