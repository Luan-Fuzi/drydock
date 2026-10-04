package dev.drydock.prototype

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import java.io.File

/** 容器 padding 区背景，取 ttyd 页面终端底色（视觉批次校准值）。 */
private const val TERM_BG = 0xFF2B2B2B.toInt()

/**
 * 终端页：WebView 直连 127.0.0.1 上由宿主 spawn 的 ttyd（同源页面，凭据经
 * onReceivedHttpAuthRequest 注入，同源 ws 复用凭据）。页面加载后注入
 * terminal-overlay.js：虚拟键条 + AV2 观测桥（terminal 文本变化经 console 转发 logcat）。
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
        root.addView(
            webView,
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
        // 注意：不 stop()——杀宿主后 tmux 会话存活正是 AV2/S2 要验证的行为
        super.onDestroy()
    }
}
