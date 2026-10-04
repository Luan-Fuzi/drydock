package dev.drydock.prototype

import android.content.Context

/**
 * 多密钥管理（D25/D27 密钥两层制第②层）：Keystore 多 key 按会话注入。
 * 元数据（id/名称/默认）进 prefs；密文仍在 SecretStore（AndroidKeyStore 主密钥加密），
 * 条目名 dk_<id>。旧单 key（EndpointStore.KEY_NAME）首访迁移为「初始密钥」条目，
 * 密文不搬家、按原名引用——行为兼容：不选 key 的会话注入默认 key。
 */
object KeyVault {

    data class KeyEntry(val id: String, val label: String, val secretName: String, val isDefault: Boolean)

    private fun prefs(context: Context) = context.getSharedPreferences("drydock", Context.MODE_PRIVATE)

    private fun ids(context: Context): List<String> =
        prefs(context).getString("key_ids", "").orEmpty().split(",").filter { it.isNotBlank() }

    /** 旧单 key → 条目化；无密钥也把 key_ids 初始化成空串，标记迁移已发生。 */
    private fun ensureMigrated(context: Context) {
        if (prefs(context).contains("key_ids")) return
        if (SecretStore.load(context, EndpointStore.KEY_NAME) != null) {
            prefs(context).edit()
                .putString("key_ids", "legacy")
                .putString("key_label_legacy", "初始密钥")
                .putString("key_secret_legacy", EndpointStore.KEY_NAME)
                .putString("key_default", "legacy")
                .apply()
        } else {
            prefs(context).edit().putString("key_ids", "").apply()
        }
    }

    fun entries(context: Context): List<KeyEntry> {
        ensureMigrated(context)
        val def = prefs(context).getString("key_default", null)
        return ids(context).map { id ->
            KeyEntry(
                id = id,
                label = prefs(context).getString("key_label_$id", null) ?: id,
                secretName = prefs(context).getString("key_secret_$id", null) ?: "dk_$id",
                isDefault = id == def,
            )
        }
    }

    fun add(context: Context, label: String, value: String): KeyEntry {
        ensureMigrated(context)
        val used = ids(context).toMutableSet()
        var i = used.size
        var id = "k$i"
        while (id in used) { i++; id = "k$i" }
        val secretName = "dk_$id"
        SecretStore.save(context, secretName, value)
        val e = prefs(context).edit().putString("key_label_$id", label.ifBlank { "密钥 $id" })
            .putString("key_secret_$id", secretName)
        if (prefs(context).getString("key_default", null) == null) e.putString("key_default", id)
        e.putString("key_ids", (ids(context) + id).joinToString(",")).apply()
        return KeyEntry(id, label.ifBlank { "密钥 $id" }, secretName, prefs(context).getString("key_default", null) == id)
    }

    fun delete(context: Context, id: String) {
        val entry = entries(context).firstOrNull { it.id == id } ?: return
        SecretStore.delete(context, entry.secretName)
        val rest = ids(context) - id
        prefs(context).edit()
            .remove("key_label_$id").remove("key_secret_$id")
            .putString("key_ids", rest.joinToString(","))
            .apply()
        if (prefs(context).getString("key_default", null) == id) {
            prefs(context).edit().putString("key_default", rest.firstOrNull()).apply()
        }
    }

    fun setDefault(context: Context, id: String) {
        if (ids(context).contains(id)) prefs(context).edit().putString("key_default", id).apply()
    }

    fun load(context: Context, id: String): String? {
        ensureMigrated(context)
        val secretName = prefs(context).getString("key_secret_$id", null) ?: return null
        return SecretStore.load(context, secretName)
    }

    fun mask(context: Context, id: String): String? {
        val secretName = loadSecretName(context, id) ?: return null
        return SecretStore.mask(context, secretName)
    }

    private fun loadSecretName(context: Context, id: String): String? {
        ensureMigrated(context)
        return prefs(context).getString("key_secret_$id", null)
    }

    fun defaultId(context: Context): String? {
        ensureMigrated(context)
        return prefs(context).getString("key_default", null)?.takeIf { ids(context).contains(it) }
    }

    /** 默认 key 的明文（只应在注入路径调用）。 */
    fun defaultKey(context: Context): String? {
        val id = defaultId(context) ?: return null
        return load(context, id)
    }

    /** 向导保存路径：改默认 key 的值（没有条目则新建「默认密钥」）。 */
    fun saveDefault(context: Context, value: String) {
        ensureMigrated(context)
        val id = defaultId(context)
        if (id == null) {
            add(context, "默认密钥", value)
        } else {
            SecretStore.save(context, prefs(context).getString("key_secret_$id", null) ?: "dk_$id", value)
        }
    }
}
