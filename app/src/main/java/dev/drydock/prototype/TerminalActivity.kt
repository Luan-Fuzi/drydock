package dev.drydock.prototype

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import java.io.File
import kotlin.math.abs

/** 容器 padding 区背景，取 ttyd 页面终端底色（视觉批次校准值）。 */
private const val TERM_BG = 0xFF2B2B2B.toInt()

/**
 * 终端触摸拦截层：在 Chromium 手势管线之前拿全 MotionEvent 流。
 * （2026-10-04 真机实证：页面级监听对合成手势全量到达、对真手指每手势仅 ~1 个
 * move——合成器认领拖动后页面拿不到轨迹，输入必须在 View 层接管。）
 * 点按（未过 slop）不拦截：聚焦/IME 走 WebView 原路；拖动与甩动按帧把位移
 * （CSS px，>0=看新内容）经 __dkScroll 打给页面，由 xterm 按当前 buffer 语义
 * 转 wheel。速度增益与惯性衰减掩掉 TUI 重绘的 ~46ms 往返。
 */
private class TerminalTouchLayout(
    context: Context,
    private val emit: (dyCss: Float, speedPxMs: Float) -> Unit,
) : FrameLayout(context) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private val pressGuardMs = 400L     // 按住超过此时长才过 slop = 选区手势，放行
    private val gainMinSpeed = 0.5f     // px/ms：低于此 1:1
    private val gainMaxSpeed = 2.0f
    private val gainMax = 3.0f
    private val velTakeoff = 0.3f       // px/ms：起惯性的末速度阈值
    private val velStop = 0.06f

    private var downY = 0f
    private var downT = 0L
    private var lastY = 0f
    private var lastMoveT = 0L
    private var velocity = 0f          // px/ms，平滑
    private var dragging = false
    private var suppressed = false     // 本手势判为选区，全程放行

    // 发射侧：帧内累计，按显示帧率整流
    private var pendingCss = 0f
    private var pendingSpeed = 0f
    private var flushScheduled = false

    // 吞掉 WebView 的「禁止父层拦截」请求：快速甩动时 Chromium 会在 slop 之前
    // requestDisallowInterceptTouchEvent(true) 截走轨迹（2026-10-04 AVD 实测快甩
    // 0 帧到达本层）。拖动所有权归本层；点按与横向键条滚动不经此路径不受影响，
    // 长按选区由 pressGuard 放行。不向上传递。
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // 有意不调 super（吞掉请求）：拖动所有权归本层；点按与横向键条滚动不经此
        // 路径不受影响，长按选区由 pressGuard 放行。
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.y
                lastY = ev.y
                downT = SystemClock.uptimeMillis()
                lastMoveT = downT
                velocity = 0f
                dragging = false
                suppressed = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) return true
                if (suppressed) return false
                if (abs(ev.y - downY) < slop) return false
                if (SystemClock.uptimeMillis() - downT > pressGuardMs) {
                    suppressed = true
                    return false
                }
                dragging = true
                lastY = ev.y
                return true
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val now = SystemClock.uptimeMillis()
                val d = lastY - ev.y // >0：手指上移 = 看更新的内容
                val dt = (now - lastMoveT).coerceAtLeast(1L)
                velocity = 0.7f * velocity + 0.3f * (d / dt)
                lastY = ev.y
                lastMoveT = now
                pendingCss += d / density
                pendingSpeed = abs(velocity)
                scheduleFlush()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                if (ev.actionMasked == MotionEvent.ACTION_UP && abs(velocity) >= velTakeoff) {
                    fling(velocity)
                }
            }
        }
        return true
    }

    /** 惯性：指数衰减（每帧 95%），帧间隔按 16.7ms 归一。 */
    private fun fling(v0: Float) {
        var v = v0
        var prev = SystemClock.uptimeMillis()
        postOnAnimation(object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                val dt = (now - prev).coerceAtMost(48L)
                prev = now
                v *= Math.pow(0.95, dt / 16.7).toFloat()
                pendingCss += v * dt / density
                pendingSpeed = abs(v)
                scheduleFlush()
                if (abs(v) > velStop) postOnAnimation(this)
            }
        })
    }

    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        postOnAnimation {
            flushScheduled = false
            if (pendingCss != 0f) {
                emit(pendingCss, pendingSpeed)
                pendingCss = 0f
                pendingSpeed = 0f
            }
        }
    }
}

/**
 * 终端页：WebView 直连 127.0.0.1 上由宿主 spawn 的 ttyd（同源页面，凭据经
 * onReceivedHttpAuthRequest 注入，同源 ws 复用凭据）。页面加载后注入
 * terminal-overlay.js：虚拟键条 + AV2 观测桥（terminal 文本变化经 console 转发 logcat）。
 * 触摸由 TerminalTouchLayout 在 View 层接管（见其注释）。
 */
class TerminalActivity : ComponentActivity() {

    /** singleTask 复用：切会话走 onNewIntent 换 URL，全程只有一个 WebView/页面。
     * （2026-10-04 真机实锤：standard 模式下会话切换泄漏出同会话双 WebView，
     * 前台旧页面带着过期终端模式，滚动滚的是重放假历史。） */
    private var webView: WebView? = null
    private var session: TerminalManager.Session? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 终端/任务在用时 app 自持亮屏：HyperOS 的"充电时不熄屏"压不住自动锁屏（D24 实测），
        // 任务期 FLAG_KEEP_SCREEN_ON 是产品正解（D23"亮着屏用"工况）
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val initial = resolveSession(intent.getStringExtra("session"))
        if (initial == null) {
            Log.e("DrydockAv2", "终端会话未启动（${intent.getStringExtra("session")}）")
            finish()
            return
        }

        WebView.setWebContentsDebuggingEnabled(true)

        val webView = WebView(this)
        this.webView = webView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedHttpAuthRequest(
                view: WebView?,
                handler: android.webkit.HttpAuthHandler,
                host: String?,
                realm: String?,
            ) {
                val token = session?.token
                if (token != null) handler.proceed("drydock", token)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val token = session?.token ?: return
                val cred = android.util.Base64.encodeToString(
                    "drydock:$token".toByteArray(),
                    android.util.Base64.NO_WRAP,
                )
                view?.evaluateJavascript(
                    "window.__DRYDOCK_CRED='$cred';",
                    android.webkit.ValueCallback<String> {
                        val overlay = assets.open("terminal-overlay.js").bufferedReader().readText()
                        view.evaluateJavascript(overlay, null)
                        Log.i("DrydockAv2", "overlay 注入完成")
                    },
                )
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                Log.i("DrydockAv2", msg.message())
                return true
            }

            // 单 WebView 切会话（onNewIntent 换 URL）撞上 ttyd 页面的 beforeunload，
            // 默认弹「Confirm Navigation」阻塞切换——切换是用户显式动作，直接放行。
            override fun onJsBeforeUnload(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?,
            ): Boolean {
                result?.confirm()
                return true
            }
        }
        webView.addJavascriptInterface(Av2Bridge(), "Drydock")

        // targetSdk 35+ 强制 edge-to-edge，window 不再避让系统栏，adjustResize 也随之失效；
        // 状态栏/cutout/软键盘 insets 一律以容器 padding 落地，IME 弹出时 WebView 收缩、
        // xterm.js 随尺寸 refit。padding 区背景与 ttyd 终端底色一致（TERM_BG）。
        val root = FrameLayout(this).apply {
            setBackgroundColor(TERM_BG)
            setOnApplyWindowInsetsListener { v, insets ->
                val pad = if (Build.VERSION.SDK_INT >= 30) {
                    insets.getInsets(
                        WindowInsets.Type.systemBars() or
                            WindowInsets.Type.displayCutout() or
                            WindowInsets.Type.ime()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsets
                }
                v.setPadding(pad.left, pad.top, pad.right, pad.bottom)
                WindowInsets.CONSUMED
            }
        }
        // 触摸拦截层包住 WebView：拖动/甩动在 View 层接管，点按透传
        val touch = TerminalTouchLayout(this) { dyCss, speedPxMs ->
            // 速度增益（1:1 → 最多 3x），与页面层 v3 实测参数一致
            val k = ((speedPxMs - 0.5f) / 1.5f).coerceIn(0f, 1f)
            val g = 1f + (3.0f - 1f) * k
            val dy = Math.round(dyCss * g * 10) / 10.0
            webView.evaluateJavascript("window.__dkScroll&&window.__dkScroll($dy)", null)
        }
        touch.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            touch,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        // 会话菜单（D27 补充条目，最小版）：右上角浮钮——会话切换/新建/回主页
        val menuBtn = android.widget.TextView(this).apply {
            text = "☰"
            setTextColor(Color.WHITE)
            textSize = 22f
            setBackgroundColor(0x88000000.toInt())
            setPadding(28, 8, 28, 16)
            setOnClickListener { showSessionMenu() }
        }
        root.addView(
            menuBtn,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.END,
            ).apply { topMargin = 24; rightMargin = 24 },
        )
        setContentView(root)
        loadSession(initial)
    }

    /** singleTask：切会话不经重建，本实例内换 URL（旧页面卸载=旧 ws 客户端断开）。 */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val target = resolveSession(intent.getStringExtra("session")) ?: return
        if (target.name != session?.name || target.port != session?.port) {
            loadSession(target)
        }
    }

    private fun resolveSession(name: String?): TerminalManager.Session? {
        val sessions = TerminalManager.readSessions(this)
        return sessions.firstOrNull { it.name == name } ?: sessions.firstOrNull()
    }

    private fun loadSession(s: TerminalManager.Session) {
        session = s
        webView?.loadUrl("http://127.0.0.1:${s.port}/")
        Log.i("DrydockAv2", "loadUrl http://127.0.0.1:${s.port}/ token=${s.token.take(4)}…")
    }

    /** 终端页内会话菜单：列表切换（含各自端口）、新建（默认密钥）、回主页。 */
    private fun showSessionMenu() {
        val sessions = TerminalManager.readSessions(this)
        val labels = sessions.map { if (it.name == TerminalManager.MAIN) "主终端 :${it.port}" else "${it.name} :${it.port}" } +
            listOf("＋ 新建会话（默认密钥）", "← 回主页")
        android.app.AlertDialog.Builder(this)
            .setTitle("会话")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < sessions.size && sessions.isNotEmpty() -> {
                        if (sessions[which].name != session?.name) {
                            // singleTask：路由回本实例 onNewIntent，同一 WebView 换 URL
                            startActivity(
                                android.content.Intent(this, TerminalActivity::class.java)
                                    .putExtra("session", sessions[which].name),
                            )
                        }
                    }
                    which == labels.size - 2 -> {
                        val name = TerminalManager.newSessionName(this)
                        startForegroundService(
                            android.content.Intent(this, EnvService::class.java).putExtra("new_session", name),
                        )
                        Thread {
                            var found = false
                            repeat(60) {
                                if (it > 0) Thread.sleep(1000)
                                if (TerminalManager.readSessions(this).any { s -> s.name == name }) {
                                    found = true; return@repeat
                                }
                            }
                            runOnUiThread {
                                if (found) {
                                    startActivity(
                                        android.content.Intent(this, TerminalActivity::class.java)
                                            .putExtra("session", name),
                                    )
                                }
                            }
                        }.start()
                    }
                    else -> {
                        startActivity(
                            android.content.Intent(this, HomeActivity::class.java)
                                .addFlags(
                                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                ),
                        )
                        finish()
                    }
                }
            }
            .show()
    }

    /** JS → Android 桥（预留；当前观测走 console→logcat）。 */
    inner class Av2Bridge {
        @JavascriptInterface
        fun report(s: String) {
            Log.i("DrydockAv2", "bridge: $s")
        }
    }

    override fun onDestroy() {
        // 不 stop() 会话——杀宿主后 dtach 会话存活正是 AV2/S2 要验证的行为。
        // WebView 必须显式 destroy：Activity 销毁后 native 实例与 devtools 页面
        // 不会随之释放（2026-10-04 实锤：僵尸 WebView 在 devtools 冒充真页面、
        // 挂着旧 ws 客户端，毒化诊断与 ttyd 多客户端状态）。
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
