package dev.drydock.prototype

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Insets
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import java.io.File
import kotlin.math.abs

/** 容器 padding 区背景，取 ttyd 页面终端底色（视觉批次校准值）。 */
private const val TERM_BG = 0xFF2B2B2B.toInt()

/** ime insets 增长被认定源自用户点按的背书窗口（键盘动画 <1s，留足余量）。 */
private const val IME_INTENT_GRACE_MS = 10_000L

/**
 * 终端触摸拦截层：在 Chromium 手势管线之前拿全 MotionEvent 流。
 * （2026-10-04 真机实证：页面级监听对合成手势全量到达、对真手指每手势仅 ~1 个
 * move——合成器认领拖动后页面拿不到轨迹，输入必须在 View 层接管。）
 * 滚动物理按平台惯例：拖动 1:1 直接操纵，松手按末速进惯性（指数衰减）——
 * 对应 Android OverScroller.fling / iOS decelerationRate 的通用形态。
 * 点按（未过 slop）不拦截：聚焦/IME 走 WebView 原路；拖动与甩动按帧把位移
 * （CSS px，>0=看新内容）经 __dkScroll 打给页面，由 xterm 按当前 buffer 语义
 * 转 wheel。
 */
private class TerminalTouchLayout(
    context: Context,
    private val onFirstTouch: () -> Unit,
    private val emit: (dyCss: Float) -> Unit,
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
                // DOWN 必经本层：给 ime insets 门控记点按背书（用户点终端=可能要拉键盘）
                onFirstTouch()
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

    /** 惯性：指数衰减（每帧 95%），帧间隔按 16.7ms 归一。
     *  2 px/ms 的甩动滑行约 670 CSS px（≈1/3 屏）——「一点点」的量级。 */
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
                emit(pendingCss)
                pendingCss = 0f
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

    // 合并浮钮与键条的联动件：下半格（键条开关）与其底色（激活态用）、键条本体、
    // 以及开合目标态（防快速连点时滑出动画的 endAction 把刚展开的键条又收走）
    private var keyFabHalf: android.view.View? = null
    private var keyFabHalfBg: android.graphics.drawable.GradientDrawable? = null
    private var keyBarView: android.view.View? = null
    private var keyBarTargetShown = false

    // ime insets 门控（2026-10-05 真机实锤）：WeType 在键盘未显示时可持幻影 touchable
    // region 吞掉下半屏手势，并向 app 派发 ime insets 把终端压半高（无键盘可见）。
    // insets 增长只有近期真实点按背书才落 padding；无背书的增长视为幻影——不落 padding
    // 并探钉 hideSoftInput 顶掉幻影窗口（region 是否放行由系统侧决定，高度确定性保住）。
    private var lastTerminalTouchAt = 0L
    private var appliedImePad = 0
    private var lastImeNudgeAt = 0L

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

        // CDP 验收通道只在 debug 包开启
        WebView.setWebContentsDebuggingEnabled(DebugHooks.WEBVIEW_DEBUGGING)

        val webView = WebView(this)
        this.webView = webView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        webView.webViewClient = object : WebViewClient() {
            // 终端里点到的链接只放行本会话 ttyd 页自身；其余 URL（dsh web 等本机
            // 服务、外部链接）甩系统浏览器（D25 直达形态）。同 WebView 导航会走离
            // ttyd 页致 ws 断开，返回后页面自缓存恢复但 xterm 失焦——真机实锤
            // 「返回后终端什么都输不进」（2026-10-08 用户报告）。
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
            ): Boolean {
                val u = request?.url ?: return false
                val port = session?.port
                val isOwnPage = u.host == "127.0.0.1" && (port == null || u.port == port)
                if (isOwnPage) return false
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, u))
                return true
            }

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
                    // 凭据 + 终端显示配置（字号/回滚，overlay 落地 xterm options）
                    "window.__DRYDOCK_CRED='$cred';" +
                        "window.__DK_CFG={fontSize:${TermPrefs.fontSize(this@TerminalActivity)},scrollback:${TermPrefs.scrollback(this@TerminalActivity)}};",
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

        // targetSdk 35+ 强制 edge-to-edge，window 不再避让系统栏，adjustResize 也随之失效；
        // 状态栏/cutout insets 以容器 padding 落地。ime insets 走点按背书包络（见字段注释）：
        // 收起总是接受；增长须 10s 内有终端区触摸背书；等值重放维持既有决定。
        val root = FrameLayout(this).apply {
            setBackgroundColor(TERM_BG)
            setOnApplyWindowInsetsListener { v, insets ->
                val sys = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                val imeNow = ime.bottom
                val pad = when {
                    imeNow == appliedImePad ->
                        if (appliedImePad > 0) Insets.max(sys, ime) else sys
                    imeNow < appliedImePad -> {
                        appliedImePad = imeNow
                        nudgeHideIme() // 收起时补一记 hide：WeType 自带折叠键可能留幻影窗口
                        sys
                    }
                    SystemClock.uptimeMillis() - lastTerminalTouchAt < IME_INTENT_GRACE_MS -> {
                        appliedImePad = imeNow
                        Insets.max(sys, ime)
                    }
                    else -> {
                        nudgeHideIme()
                        sys
                    }
                }
                v.setPadding(pad.left, pad.top, pad.right, pad.bottom)
                WindowInsets.CONSUMED
            }
        }
        // 触摸拦截层只包 WebView：终端区拖动/甩动在 View 层接管，点按透传。
        // 原生键条在 WebView 之外——键条起手的手势不进终端触摸层（用户实锤：
        // 页内键条时代按住键条上滑会带动终端滚动），触摸分流由视图结构天然完成。
        // 滚动 = 1:1 直接操纵 + 松手惯性（平台惯例，见 TerminalTouchLayout 注释）；
        // 曾用的 1-3x 速度增益按用户反馈移除（2026-10-07）：与 TUI 单事件大步长
        // 相乘，高速拖动直接窜到头。
        val touch = TerminalTouchLayout(
            this,
            { lastTerminalTouchAt = SystemClock.uptimeMillis() },
        ) { dyCss ->
            val dy = Math.round(dyCss * 10) / 10.0
            webView.evaluateJavascript("window.__dkScroll&&window.__dkScroll($dy)", null)
        }
        touch.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        content.addView(
            touch,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        val keyBar = buildKeyBar(webView)
        keyBarView = keyBar
        val uiPrefs = getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
        // 键条默认收起（2026-10-08 用户定调），状态记忆跨会话；初始态直接落、不播动画
        val keyBarShown = !uiPrefs.getBoolean("keybar_collapsed", true)
        keyBarTargetShown = keyBarShown
        keyBar.visibility =
            if (keyBarShown) android.view.View.VISIBLE else android.view.View.GONE
        content.addView(
            keyBar,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        // 合并浮钮（2026-10-08 用户定调两钮并一钮）：上半菜单/下半键条。此前 ☰ 文本
        // 字形与 ⌨ emoji 两枚裸 TextView 各搞一套 padding/字号，真机上又丑又不一致、
        // 且无点按反馈；现统一 vector 图标 + 胶囊底 + 半格反馈（见 makeFabDraggable），
        // 键条展开时下半格亮起——面板开合状态一眼可见。
        val fab = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xF01A1A1C.toInt())
                cornerRadius = 28.dp().toFloat()
            }
            elevation = 6.dp().toFloat()
            // 背景 pill 圆角即视图轮廓：半格的方形 ripple 遮罩随之裁成圆角
            clipToOutline = true
        }
        fun half(iconRes: Int, desc: String): android.widget.FrameLayout =
            android.widget.FrameLayout(this).apply {
                contentDescription = desc
                foreground = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x40FFFFFF),
                    null,
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFF000000.toInt())
                    },
                )
                addView(
                    android.widget.ImageView(context).apply {
                        setImageResource(iconRes)
                        setColorFilter(0xF2FFFFFF.toInt())
                    },
                    android.widget.FrameLayout.LayoutParams(
                        24.dp(),
                        24.dp(),
                        android.view.Gravity.CENTER,
                    ),
                )
            }
        val menuHalf = half(R.drawable.ic_fab_menu, getString(R.string.terminal_fab_menu)).apply { id = R.id.terminal_menu_btn }
        val keyHalf = half(R.drawable.ic_fab_keyboard, getString(R.string.terminal_fab_keyboard)).apply {
            id = R.id.terminal_key_btn
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
            }.also { keyFabHalfBg = it }
        }
        keyFabHalf = keyHalf
        fab.addView(menuHalf, android.widget.LinearLayout.LayoutParams(56.dp(), 48.dp()))
        fab.addView(
            android.view.View(this).apply { setBackgroundColor(0x24FFFFFF) },
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                1.dp(),
            ),
        )
        fab.addView(keyHalf, android.widget.LinearLayout.LayoutParams(56.dp(), 48.dp()))
        makeFabDraggable(fab, menuHalf, keyHalf, { showSessionMenu() }) {
            setKeyBarVisible(keyBar.visibility != android.view.View.VISIBLE, uiPrefs)
        }
        root.addView(
            fab,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.END,
            ).apply { topMargin = 24; rightMargin = 24 },
        )
        setContentView(root)
        loadSession(initial)
    }

    /** 键条展开/收起（浮钮下半格点按）：滑入/滑出 + 下半格激活态（与键条 Ctrl
     *  粘滞同一绿）+ 状态记忆。目标态哨兵挡连点竞态：滑出 endAction 只在收起仍是
     *  目标态时才落 GONE，否则刚展开就被旧动画收走。 */
    private fun setKeyBarVisible(show: Boolean, uiPrefs: android.content.SharedPreferences) {
        val bar = keyBarView ?: return
        keyBarTargetShown = show
        uiPrefs.edit().putBoolean("keybar_collapsed", !show).apply()
        keyFabHalfBg?.setColor(if (show) 0xFF166534.toInt() else android.graphics.Color.TRANSPARENT)
        if (show) {
            bar.visibility = android.view.View.VISIBLE
            bar.post {
                bar.translationY = bar.height.toFloat()
                bar.animate().translationY(0f).setDuration(180).start()
            }
        } else {
            bar.animate().translationY(bar.height.toFloat()).setDuration(140)
                .withEndAction {
                    if (!keyBarTargetShown) bar.visibility = android.view.View.GONE
                }
                .start()
        }
    }

    /** 原生虚拟键条（2026-10-04 从页面 DOM 迁移）：三行等权重、不溢出不横向滚动。
     * 2026-10-08 重评估（用户反馈键太多）：PgUp/PgDn 移除——常规回翻已被触控滚动
     * 覆盖（TerminalTouchLayout），整条默认收起（浮钮下半格展开）。保留键依据：
     * 四方向=claude/opencode TUI 菜单导航（D24）；Esc=取消/返回；Ctrl 粘滞=Ctrl+C
     * 中断；Tab=补全；⇧Tab=claude TUI 模式切换（auto/manual/plan）；↵=绕 IME 的
     * 干净回车通道（D32 WeType 教训的保险丝）。同日用户真机反馈补组合键行：
     * Ctrl+C 中断/Ctrl+Z 挂起/Ctrl+D EOF/Ctrl+L 清屏/Ctrl+W 删词——一次点按合成
     * ctrlKey 序列，覆盖粘滞 Ctrl 要两步的场景。键位经页面 __dk.sendKey 合成（与
     * IME 同链路）；Ctrl 粘滞状态与 overlay 的字母拦截逻辑（armCtrl）共用。 */
    private fun buildKeyBar(webView: WebView): android.view.View {
        fun send(js: String) = webView.evaluateJavascript("window.__dk&&window.__dk.sendKey($js)", null)

        data class Key(val label: String, val js: String, val icon: Int? = null)
        val esc = "{key:'Escape',code:'Escape',keyCode:27,which:27}"
        val tab = "{key:'Tab',code:'Tab',keyCode:9,which:9}"
        // 方向与回车用 vector 图标键（2026-10-08 用户反馈字形键细且小）：字体里
        // ←↑↓→/↵ 的字形粗细与大小不可控，Material 箭头路径笔画均匀、随密度缩放
        val nav = listOf(
            Key(getString(R.string.terminal_key_left), "{key:'ArrowLeft',code:'ArrowLeft',keyCode:37,which:37}", R.drawable.ic_key_arrow_left),
            Key(getString(R.string.terminal_key_up), "{key:'ArrowUp',code:'ArrowUp',keyCode:38,which:38}", R.drawable.ic_key_arrow_up),
            Key(getString(R.string.terminal_key_down), "{key:'ArrowDown',code:'ArrowDown',keyCode:40,which:40}", R.drawable.ic_key_arrow_down),
            Key(getString(R.string.terminal_key_right), "{key:'ArrowRight',code:'ArrowRight',keyCode:39,which:39}", R.drawable.ic_key_arrow_right),
        )
        val actions = listOf(
            Key("Esc", esc),
            Key("Tab", tab),
            Key("Shift+Tab", "$tab,shiftKey:true"),
            Key(getString(R.string.terminal_key_enter), "{key:'Enter',code:'Enter',keyCode:13,which:13}", R.drawable.ic_key_return),
        )

        fun keyRipple(): android.graphics.drawable.RippleDrawable =
            android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF2E2E2E.toInt())
                    cornerRadius = 6.dp().toFloat()
                },
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF000000.toInt())
                    cornerRadius = 6.dp().toFloat()
                },
            )

        fun keyButton(label: String, textSp: Float, onClick: android.view.View.OnClickListener): android.widget.Button =
            android.widget.Button(this).apply {
                text = label
                textSize = textSp
                setTextColor(0xFFDDDDDD.toInt())
                // setBackgroundColor 会盖掉默认背景连带 ripple——真机反馈「按了没反应」
                // 的根源之一；改自绘圆角底 + 前景 ripple，按下有可见反馈
                stateListAnimator = null
                background = keyRipple()
                setPadding(0, 0, 0, 0)
                minHeight = 0
                minWidth = 0
                setOnClickListener(onClick)
            }

        /** 图标键（方向/回车）：Material 箭头路径 22dp 居中，笔画粗细与浮钮图标
         *  同源；desc 沿用 Key.label（「左/上/下/右/回车」），uiautomator 可锚。 */
        fun keyIcon(desc: String, iconRes: Int, onClick: android.view.View.OnClickListener): android.widget.FrameLayout =
            android.widget.FrameLayout(this).apply {
                contentDescription = desc
                stateListAnimator = null
                background = keyRipple()
                setOnClickListener(onClick)
                addView(
                    android.widget.ImageView(context).apply {
                        setImageResource(iconRes)
                        setColorFilter(0xFFDDDDDD.toInt())
                    },
                    android.widget.FrameLayout.LayoutParams(
                        22.dp(),
                        22.dp(),
                        android.view.Gravity.CENTER,
                    ),
                )
            }

        fun keyView(k: Key): android.view.View =
            if (k.icon != null) keyIcon(k.label, k.icon) { send(k.js) }
            else keyButton(k.label, if (k.label.length > 4) 13f else 15f) { send(k.js) }

        fun row(buttons: List<android.view.View>) = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF141414.toInt())
            val lp = android.widget.LinearLayout.LayoutParams(0, 46.dp(), 1f)
            lp.setMargins(2, 2, 2, 2)
            buttons.forEach { addView(it, android.widget.LinearLayout.LayoutParams(lp)) }
        }

        val combos = listOf(
            Key("Ctrl+C", "{key:'c',code:'KeyC',keyCode:67,which:67,ctrlKey:true}"),
            Key("Ctrl+Z", "{key:'z',code:'KeyZ',keyCode:90,which:90,ctrlKey:true}"),
            Key("Ctrl+D", "{key:'d',code:'KeyD',keyCode:68,which:68,ctrlKey:true}"),
            Key("Ctrl+L", "{key:'l',code:'KeyL',keyCode:76,which:76,ctrlKey:true}"),
            Key("Ctrl+W", "{key:'w',code:'KeyW',keyCode:87,which:87,ctrlKey:true}"),
        )

        // Ctrl 粘滞：点亮色跟着自绘底走（setBackgroundColor 会压掉 ripple）
        val ctrlBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF2E2E2E.toInt())
            cornerRadius = 6.dp().toFloat()
        }
        val ctrlBtn = keyButton("Ctrl", 15f) { }
        ctrlBtn.background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33FFFFFF),
            ctrlBg,
            android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF000000.toInt())
                cornerRadius = 6.dp().toFloat()
            },
        )
        ctrlBtn.setOnClickListener {
            val armed = !it.isSelected
            it.isSelected = armed
            ctrlBg.setColor(if (armed) 0xFF166534.toInt() else 0xFF2E2E2E.toInt())
            webView.evaluateJavascript(
                "window.__dk&&window.__dk.armCtrl($armed)", null,
            )
        }
        val navRow = row(nav.map { keyView(it) })
        val actionRow = row(listOf(ctrlBtn) + actions.map { keyView(it) })
        val comboRow = row(combos.map { keyView(it) })

        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(navRow, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(actionRow, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(comboRow, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    /** 合并浮钮拖动（2026-10-08 两钮并一钮）：按住任意方向拖，松手水平吸附到最近
     *  边缘（保留拖放高度，纵向上限夹在屏内）；位移超过 2×touchSlop 才算拖动，
     *  否则判为点按、按落点半区（上=菜单/下=键条）触发。touch 层消费事件，点按
     *  不走 setOnClickListener。ACTION_CANCEL 只按拖动收尾处理、绝不补点按——
     *  2026-10-08 实锤：浮钮贴右缘，系统返回手势抢走触摸流时最后送来的是 CANCEL，
     *  当点按处理会弹错菜单。配套把浮钮矩形加进 systemGestureExclusionRects
     *  ，从浮钮起手的拖动不再被抢。
     *  半区按压反馈：isPressed 驱动前景 ripple + 缩放 0.92（真机反馈「点击无反馈」
     *  的正主），拖动判定成立即还原；UP 点按补一记 VIRTUAL_KEY 触感。 */
    private fun makeFabDraggable(
        v: android.view.View,
        topHalf: android.view.View,
        bottomHalf: android.view.View,
        onTopTap: () -> Unit,
        onBottomTap: () -> Unit,
    ) {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0f; var startY = 0f; var moved = false
        var pressedHalf: android.view.View? = null
        fun clearPress() {
            pressedHalf?.let {
                it.isPressed = false
                it.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
            }
            pressedHalf = null
        }
        fun excludeFromGestures() {
            v.setSystemGestureExclusionRects(
                listOf(android.graphics.Rect(0, 0, v.width, v.height)),
            )
        }
        v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> excludeFromGestures() }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = view.x; startY = view.y; moved = false
                    pressedHalf = if (e.y < view.height / 2f) topHalf else bottomHalf
                    pressedHalf?.isPressed = true
                    pressedHalf?.animate()?.scaleX(0.92f)?.scaleY(0.92f)?.setDuration(60)?.start()
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (Math.abs(dx) > slop * 2 || Math.abs(dy) > slop * 2)) {
                        moved = true
                        clearPress()
                    }
                    if (moved) { view.x = startX + dx; view.y = startY + dy }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val half = pressedHalf
                    clearPress()
                    if (moved) {
                        val parent = view.parent as android.view.ViewGroup
                        val targetX =
                            if (view.x + view.width / 2f < parent.width / 2f) 0f
                            else (parent.width - view.width).toFloat()
                        val targetY = view.y.coerceIn(0f, (parent.height - view.height).toFloat())
                        view.animate().x(targetX).y(targetY).setDuration(160).start()
                    } else if (e.actionMasked == android.view.MotionEvent.ACTION_UP) {
                        half?.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        if (half === topHalf) onTopTap() else onBottomTap()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** 设置页改字号/回滚后回到本页即套用（window.__dk.applyCfg 由 overlay 提供；
     *  首次进入时页面未就绪则静默跳过——首载配置走 __DK_CFG 注入）。 */
    override fun onResume() {
        super.onResume()
        webView?.evaluateJavascript(
            "window.__dk&&window.__dk.applyCfg&&window.__dk.applyCfg(" +
                "{fontSize:${TermPrefs.fontSize(this)},scrollback:${TermPrefs.scrollback(this)}})",
            null,
        )
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

    /** 幻影 IME 探钉：对没被用户点按背书的 ime 状态发 hideSoftInput，促 IME 释放
     *  幻影窗口（含吞手势的 touchable region）。隐藏态下调用是廉价 no-op，1.5s 节流。 */
    private fun nudgeHideIme() {
        val now = SystemClock.uptimeMillis()
        if (now - lastImeNudgeAt < 1500) return
        lastImeNudgeAt = now
        Log.i("DrydockAv2", "ime insets 无点按背书（幻影）：hideSoftInput 探钉")
        val token = webView?.windowToken ?: return
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(token, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val ime = window.decorView.rootWindowInsets?.getInsets(WindowInsets.Type.ime()) ?: return
        // 回前台时 ime 仍悬着且无点按背书：与 insets 监听同一幻影判据，探钉清场
        if (ime.bottom > appliedImePad &&
            SystemClock.uptimeMillis() - lastTerminalTouchAt >= IME_INTENT_GRACE_MS
        ) nudgeHideIme()
    }

    /** 终端页内会话菜单（2026-10-08 美化：贴底深色圆角面板，替代平台 AlertDialog
     *  ——裸 DeviceDefault 主题在 HyperOS 上与终端深色页不搭且不够单手）。结构：
     *  标题「会话」→ 会话行（当前会话绿点标记；整行文本「显示名 :端口」）→ 分隔线
     *  → 新建/回主页带图标行。行为与旧对话框一致：切会话走 singleTask onNewIntent，
     *  新建走 EnvService 默认名，回主页 finish。出入场滑动画走 DrydockBottomSheetAnim。
     *  i18n 批 1：会话行/动作行有 resource-id 锚点（terminal_menu_*），night-b t11
     *  按节点计数断言，不依赖行文本。 */
    private fun showSessionMenu() {
        val sessions = TerminalManager.readSessions(this)
        fun rowRipple(): android.graphics.drawable.RippleDrawable =
            android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.TRANSPARENT)
                },
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF000000.toInt())
                },
            )
        val dlg = android.app.Dialog(this)
        val panel = android.widget.LinearLayout(this).apply {
            id = R.id.terminal_menu_panel
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    24.dp().toFloat(), 24.dp().toFloat(),
                    24.dp().toFloat(), 24.dp().toFloat(),
                    0f, 0f, 0f, 0f,
                )
                setColor(0xFFF21B1B1D.toInt())
            }
            setPadding(4.dp(), 10.dp(), 4.dp(), 8.dp())
            // 面板自身避让系统栏：底部加导航条/手势条高度，顶部防挖孔
            setOnApplyWindowInsetsListener { v, ins ->
                val sys = ins.getInsets(
                    android.view.WindowInsets.Type.systemBars() or
                        android.view.WindowInsets.Type.displayCutout(),
                )
                v.setPadding(4.dp(), 10.dp() + sys.top, 4.dp(), 8.dp() + sys.bottom)
                android.view.WindowInsets.CONSUMED
            }
        }
        panel.addView(android.widget.TextView(this).apply {
            text = getString(R.string.terminal_menu_title)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0x99FFFFFF.toInt())
            setPadding(16.dp(), 6.dp(), 16.dp(), 6.dp())
        })
        sessions.forEach { s ->
            val current = s.name == session?.name
            val label = "${SessionNames.get(this, s.name)} :${s.port}"
            panel.addView(android.widget.TextView(this).apply {
                id = R.id.terminal_menu_session
                if (current) {
                    val ss = android.text.SpannableStringBuilder("● ")
                    ss.append(label)
                    ss.setSpan(
                        android.text.style.ForegroundColorSpan(0xFF4ADE80.toInt()),
                        0, 1,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    text = ss
                    setTextColor(0xFFF2FFFFFF.toInt())
                } else {
                    text = label
                    setTextColor(0xCCFFFFFF.toInt())
                }
                textSize = 16f
                setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
                background = rowRipple()
                setOnClickListener {
                    dlg.dismiss()
                    if (s.name != session?.name) {
                        // singleTask：路由回本实例 onNewIntent，同一 WebView 换 URL
                        startActivity(
                            android.content.Intent(
                                this@TerminalActivity,
                                TerminalActivity::class.java,
                            ).putExtra("session", s.name),
                        )
                    }
                }
            })
        }
        panel.addView(
            android.view.View(this).apply {
                setBackgroundColor(0x1FFFFFFF)
            },
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                1.dp(),
            ).apply { setMargins(12.dp(), 6.dp(), 12.dp(), 6.dp()) },
        )
        fun actionRow(iconRes: Int, label: String, idRes: Int, action: () -> Unit): android.view.View =
            android.widget.LinearLayout(this).apply {
                id = idRes
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = rowRipple()
                setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
                addView(
                    android.widget.ImageView(context).apply {
                        setImageResource(iconRes)
                        setColorFilter(0xF2FFFFFF.toInt())
                    },
                    android.widget.LinearLayout.LayoutParams(22.dp(), 22.dp()),
                )
                addView(
                    android.widget.TextView(context).apply {
                        text = label
                        textSize = 16f
                        setTextColor(0xF2FFFFFF.toInt())
                        setPadding(14.dp(), 0, 0, 0)
                    },
                    android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
                setOnClickListener { dlg.dismiss(); action() }
            }
        panel.addView(
            actionRow(R.drawable.ic_sheet_new, getString(R.string.session_new), R.id.terminal_menu_new) {
                val name = TerminalManager.newSessionName(this)
                // 菜单快建不弹对话框：默认名「会话 N」，主页可改名
                SessionNames.set(this, name, "会话 ${sessions.size + 1}")
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
            },
        )
        panel.addView(
            actionRow(R.drawable.ic_sheet_home, getString(R.string.terminal_menu_home), R.id.terminal_menu_home) {
                startActivity(
                    android.content.Intent(this, HomeActivity::class.java)
                        .addFlags(
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP,
                        ),
                )
                finish()
            },
        )
        dlg.setContentView(panel)
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setWindowAnimations(R.style.DrydockBottomSheetAnim)
            setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setGravity(android.view.Gravity.BOTTOM)
            setDimAmount(0.5f)
        }
        dlg.show()
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
