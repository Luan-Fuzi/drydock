package dev.drydock.prototype

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.File
import org.json.JSONObject

/**
 * 环境宿主前台服务（:env 进程）。职责（D18 + 步骤 5 仪器）：
 * 1. 承载 proot/dtach/ttyd 进程树——AMS 对死亡 App 按进程组清剿，环境挂独立
 *    前台服务进程下才能在宿主 UI 崩溃/重启时存活并被接回；
 * 2. 仪器：WakeLock、锁屏/充电广播、会话心跳（60s）、CPU 温度频率采样（5min），
 *    全部进 Timeline ring buffer；L1 最小通知（会话退出 / 静默 5 分钟启发式）。
 * UI 经 terminal-sessions.json 发现端口与 token。
 */
class EnvService : Service() {

    companion object {
        private const val TAG = "DrydockEnv"
        private const val CHANNEL_FG = "drydock_env"
        private const val CHANNEL_ALERT = "drydock_alerts"
        private const val NOTIFICATION_ID = 1001
        private const val HEARTBEAT_TICKS = 12 // 5s × 12 = 60s
        private const val CPU_TICKS = 60 // 5s × 60 = 5min
        private const val SILENT_ALERT_MIN = 5L
        private const val FREEZE_GAP_MS = 60_000L // 5s tick 出现 >60s 断档视为曾被冻结
    }

    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var monitorRuns = false
    private var screenReceiver: BroadcastReceiver? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var lastBatteryState: String? = null
    private val notifiedSilent = HashSet<String>()
    private val notifiedDead = HashSet<String>()
    private var alertSeq = 2000

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_FG, "环境宿主", NotificationManager.IMPORTANCE_MIN),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "会话提醒", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val notification: Notification =
            Notification.Builder(this, CHANNEL_FG)
                .setContentTitle("Drydock 环境运行中")
                .setContentText("Linux 会话与终端服务")
                .setSmallIcon(android.R.drawable.stat_sys_download) // 原型占位
                .build()
        startForeground(NOTIFICATION_ID, notification)

        // WakeLock：锁屏后 CPU 不睡（S1 观测的前提），service 销毁时释放
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "drydock:env:cpu")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        Timeline.log(this, "wakelock_acquire", mapOf("tag" to "drydock:env:cpu"))
        Timeline.log(this, "service_start", mapOf("pid" to android.os.Process.myPid()))
        Log.i(TAG, "EnvService 前台化完成（:env pid=${android.os.Process.myPid()}，WakeLock 已持）")

        registerReceivers()
        startMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext
        val newSession = intent?.getStringExtra("new_session")
        // key_id：缺省=默认密钥；""=不注入；其余=KeyVault 条目 id（D27 密钥两层制）
        val keyId = if (intent?.hasExtra("key_id") == true) intent.getStringExtra("key_id") else null
        Thread {
            // 新会话优先：ensureAll 串行恢复历史会话每个 ~10s，堆多个时会把
            // 发起方的 60s 轮询耗光（夜批实锤）；先建新会话再恢复其余（幂等）
            if (newSession != null) {
                TerminalManager.start(app, newSession, keyId)
            }
            TerminalManager.ensureAll(app)
            Timeline.log(this, "sessions_ready", mapOf("names" to TerminalManager.readSessions(app).map { it.name }))
        }.start()
        return START_STICKY
    }

    override fun onDestroy() {
        monitorRuns = false
        Timeline.log(this, "service_destroy", mapOf("pid" to android.os.Process.myPid()))
        wakeLock?.let {
            if (it.isHeld) it.release()
            Timeline.log(this, "wakelock_release")
        }
        screenReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        batteryReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 锁屏/解锁与充电状态进时间线（engineering-plan 事件清单）。 */
    private fun registerReceivers() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    Intent.ACTION_SCREEN_OFF -> Timeline.log(this@EnvService, "screen_off")
                    Intent.ACTION_SCREEN_ON -> Timeline.log(this@EnvService, "screen_on")
                }
            }
        }
        val sf = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, sf, Context.RECEIVER_NOT_EXPORTED)

        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val level = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                val status = i?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == android.os.BatteryManager.BATTERY_STATUS_FULL
                if (level < 0 || scale <= 0) return
                val pct = level * 100 / scale
                // BATTERY_CHANGED 触发频繁，只在状态翻转时记录
                val key = "$charging:$pct"
                if (key != lastBatteryState) {
                    lastBatteryState = key
                    Timeline.log(this@EnvService, "battery", mapOf("pct" to pct, "charging" to charging))
                }
            }
        }
        // sticky 首查：落初始充电状态
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            ?.let { batteryReceiver?.onReceive(this, it) }
    }

    /** 仪器主循环：5s tick；每 12 tick 会话心跳（含 L1 静默判定），每 60 tick CPU 采样。
     *  冻结感知（真机周 D1 实测教训）：线程被系统冻结时 sleep 整段停摆，恢复后
     *  wall-clock 断档远大于 tick 周期——据此记 freeze_suspected 并通知用户。 */
    private fun startMonitor() {
        monitorRuns = true
        Thread {
            var tick = 0
            var lastTickAt = System.currentTimeMillis()
            while (monitorRuns) {
                try {
                    Thread.sleep(5_000)
                    val now = System.currentTimeMillis()
                    val gapMs = now - lastTickAt
                    lastTickAt = now
                    if (gapMs > FREEZE_GAP_MS) {
                        onFreezeSuspected(gapMs)
                    }
                    if (tick % HEARTBEAT_TICKS == 0) {
                        val beats = TerminalManager.heartbeat(this)
                        l1Judge(beats)
                    }
                    if (tick % CPU_TICKS == 0) cpuSample()
                } catch (e: Exception) {
                    Log.w(TAG, "monitor tick 异常: $e")
                }
                tick++
            }
        }.apply { isDaemon = true }.start()
    }

    /** 断档 >60s：进程曾被挂起（HyperOS 冻结 / 深度休眠 / 整机深睡）。 */
    private fun onFreezeSuspected(gapMs: Long) {
        val min = gapMs / 60_000
        Timeline.log(this, "freeze_suspected", mapOf("gapMs" to gapMs, "gapMin" to min))
        // 冻结期间的 rchar 静默计数跨着熄屏断档，恢复后首拍易误报"静默"——先占位抑制
        TerminalManager.heartbeat(this).forEach { b ->
            if (b.alive) notifiedSilent.add(b.name)
        }
        alert(
            getSystemService(NotificationManager::class.java),
            "任务曾被系统暂停约 $min 分钟",
            "环境与仪器同时停摆（疑似省电策略冻结），现已自动恢复；若频繁出现请检查省电策略",
        )
    }

    /** L1 最小通知：退出（holder 死）与静默（rchar 5 分钟无增长）启发式，每事件只报一次。 */
    private fun l1Judge(beats: List<TerminalManager.Heartbeat>) {
        val nm = getSystemService(NotificationManager::class.java)
        for (b in beats) {
            if (!b.alive) {
                if (b.name !in notifiedDead) {
                    notifiedDead.add(b.name)
                    notifiedSilent.remove(b.name)
                    alert(nm, "会话 ${b.name} 已退出", "环境进程结束；重开终端会重建会话")
                }
            } else {
                notifiedDead.remove(b.name)
                if (b.silentMin >= SILENT_ALERT_MIN && b.name !in notifiedSilent) {
                    notifiedSilent.add(b.name)
                    alert(nm, "会话 ${b.name} 静默 ${b.silentMin} 分钟", "PTY 无输出（可能任务结束或等待输入）")
                } else if (b.silentMin == 0L) {
                    notifiedSilent.remove(b.name)
                }
            }
        }
    }

    private fun alert(nm: NotificationManager, title: String, text: String) {
        Timeline.log(this, "l1_alert", mapOf("title" to title))
        nm.notify(
            alertSeq++,
            Notification.Builder(this, CHANNEL_ALERT)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** CPU 温度/频率采样；AVD 常无 thermal，读不到记空样本（判据只要求记录机制）。 */
    private fun cpuSample() {
        val temps = mutableListOf<Int>()
        val zones = File("/sys/class/thermal")
        zones.listFiles()?.sortedBy { it.name }?.forEach { z ->
            runCatching {
                z.resolve("temp").readText().trim().toInt().let { temps.add(it / 1000) }
            }
        }
        val freqs = mutableListOf<Int>()
        val cpus = File("/sys/devices/system/cpu")
        cpus.listFiles { f -> f.name.startsWith("cpu") && f.name.removePrefix("cpu").all { it.isDigit() } }
            ?.sortedBy { it.name }?.forEach { c ->
                runCatching {
                    c.resolve("cpufreq/scaling_cur_freq").readText().trim().toInt().let { freqs.add(it / 1000) }
                }
            }
        Timeline.log(
            this,
            "cpu_sample",
            mapOf("tempC" to temps, "freqMHz" to freqs),
        )
    }
}
