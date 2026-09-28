package dev.drydock.prototype

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 密钥保管（I1）：明文只存在于进程内存与被注入进程的 environment；
 * 落盘形态 = AndroidKeyStore 主密钥（不可导出）+ prefs 密文，环境内文件永不出现。
 */
object SecretStore {

    private const val PREFS = "drydock_secrets"
    private const val KS_ALIAS = "drydock_secret_master"
    private const val IV_LEN = 12

    fun save(context: Context, name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val blob = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs(context).edit()
            .putString(name, Base64.encodeToString(blob, Base64.NO_WRAP))
            .apply()
    }

    fun load(context: Context, name: String): String? {
        val b64 = prefs(context).getString(name, null) ?: return null
        return try {
            val blob = Base64.decode(b64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, blob, 0, IV_LEN))
            String(cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN), Charsets.UTF_8)
        } catch (_: Exception) {
            null // 主密钥随清数据/卸载蒸发，密文读不出即视为未设置
        }
    }

    fun delete(context: Context, name: String) {
        prefs(context).edit().remove(name).apply()
    }

    /** 展示用掩码：头 4 尾 4，中间不可见。 */
    fun mask(context: Context, name: String): String? {
        val v = load(context, name) ?: return null
        return if (v.length <= 8) "…" else v.take(4) + "…" + v.takeLast(4)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KS_ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                KS_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return kg.generateKey()
    }
}
