package dev.drydock.prototype

import android.content.Context

/**
 * 直通绑定（D25 文件互通第三档，最小版）：手机 Download 目录 proot -b 绑进环境
 * /root/AndroidDownload。默认关闭的高级选项（决策口径），需 MANAGE_EXTERNAL_STORAGE
 * （「所有文件访问」，用户在系统设置里本人开启）。固定目录，不做任意选择——
 * 任意目录双向实时同步在不做清单里。
 */
object BindStore {

    const val HOST_DIR = "/storage/emulated/0/Download"
    const val ENV_DIR = "/root/AndroidDownload"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("drydock", Context.MODE_PRIVATE).getBoolean("bind_download", false)

    fun setEnabled(context: Context, v: Boolean) {
        context.getSharedPreferences("drydock", Context.MODE_PRIVATE).edit()
            .putBoolean("bind_download", v).apply()
    }

    /** proot -b 的绑定参数（host:env 成对）；未开启或未授权返回空。 */
    fun binds(context: Context): List<String> =
        if (enabled(context) && android.os.Environment.isExternalStorageManager()) {
            listOf("$HOST_DIR:$ENV_DIR")
        } else emptyList()
}
