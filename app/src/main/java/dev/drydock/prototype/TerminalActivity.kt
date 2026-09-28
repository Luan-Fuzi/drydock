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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val session = TerminalManager.current() ?: EnvService.readSession(this)
        if (session == null) {
            Log.e("DrydockAv2", "终端会话未启动")
            finish()
            return
        }

        WebView.setWebContentsDebuggingEnabled(true)

        val webView = WebView(this)
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
                handler.proceed("drydock", session.token)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val cred = android.util.Base64.encodeToString(
                    "drydock:${session.token}".toByteArray(),
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
        setContentView(root)
        webView.loadUrl("http://127.0.0.1:${session.port}/")
        Log.i("DrydockAv2", "loadUrl http://127.0.0.1:${session.port}/ token=${session.token.take(4)}…")
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
