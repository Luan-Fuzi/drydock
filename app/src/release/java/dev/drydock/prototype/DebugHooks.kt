package dev.drydock.prototype

import android.app.Activity
import android.content.Intent

/** release 构建：验收注入口不存在（debug 源集为真实实现）。 */
object DebugHooks {
    const val WEBVIEW_DEBUGGING = false

    @Suppress("UNUSED_PARAMETER")
    fun handle(activity: Activity, intent: Intent) {}
}
