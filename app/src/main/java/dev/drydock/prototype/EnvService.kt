package dev.drydock.prototype

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.File
import org.json.JSONObject

/**
 * 环境宿主前台服务（:env 进程）。
 * 根本职责：承载 proot/dtach/ttyd 进程树——AMS 对死亡 App 是按进程组清剿的
 * （AV2 实测：主进程 kill -9 后连 ppid=1 的孤儿 dtach 也被清），主 UI 进程
 * 崩溃/重启时，只有让环境挂在独立的前台服务进程下，会话才能存活并被接回。
 * UI 通过 filesDir/terminal-session.json 发现端口与 token。
 */
class EnvService : Service() {

    companion object {
        private const val TAG = "DrydockEnv"
        private const val CHANNEL_ID = "drydock_env"
        private const val NOTIFICATION_ID = 1001

        fun sessionFile(context: Context): File = File(context.filesDir, "terminal-session.json")

        /** UI 进程读会话信息（服务进程写）。 */
        fun readSession(context: Context): TerminalManager.Session? = try {
            val f = sessionFile(context)
            if (!f.exists()) null
            else {
                val json = JSONObject(f.readText())
                TerminalManager.Session(json.getInt("port"), json.getString("token"))
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "环境宿主",
                NotificationManager.IMPORTANCE_MIN,
            ),
        )
        val notification: Notification =
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Drydock 环境运行中")
                .setContentText("Linux 会话与终端服务")
                .setSmallIcon(android.R.drawable.stat_sys_download) // 原型占位
                .build()
        startForeground(NOTIFICATION_ID, notification)
        Log.i(TAG, "EnvService 前台化完成（:env 进程 pid=${android.os.Process.myPid()}）")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext
        Thread {
            val s = TerminalManager.start(app)
            val f = sessionFile(app)
            if (s != null) {
                f.writeText(
                    JSONObject()
                        .put("port", s.port)
                        .put("token", s.token)
                        .put("pid", android.os.Process.myPid())
                        .put("ts", System.currentTimeMillis())
                        .toString(),
                )
                Log.i(TAG, "会话写入 port=${s.port}")
            } else {
                f.delete()
                Log.e(TAG, "会话启动失败")
            }
        }.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
