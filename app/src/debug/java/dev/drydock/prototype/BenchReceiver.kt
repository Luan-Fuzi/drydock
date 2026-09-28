package dev.drydock.prototype

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 验收自动化通道（仅 debug sourceSet 注册，release 无此组件）：
 * am broadcast -a dev.drydock.action.RUN_BENCH -n dev.drydock.prototype/.BenchReceiver
 * 触发 Bench.run 全链路（工具安装→hyperfine→JSON 落袋），结果进 logcat 与 Downloads。
 */
class BenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("DrydockBench", "broadcast 触发基准电池")
        Thread {
            val r = Bench.run(context.applicationContext) { Log.i("DrydockBench", it) }
            Log.i(
                "DrydockBench",
                "RESULT ok=${r.ok} landed=${r.landedUri} log=${r.log.takeLast(300)}",
            )
        }.start()
    }
}
