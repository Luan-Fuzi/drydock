// Drydock 终端覆盖层：虚拟键条 + AV2 观测桥。
// 观测桥：terminal 渲染文本变化 → console.log("AV2TEXT:…") → 宿主 WebChromeClient → logcat。
(function () {
  if (window.__drydockOverlay) return;
  window.__drydockOverlay = true;

  // ---------- 字体修正（D24 实测）----------
  // ttyd 默认字体链（Consolas/Liberation/Menlo/Courier）在 Android 全不存在，
  // 落到通用 monospace 后缺 U+23F5(⏵) 等字形变豆腐块；换安卓实际有的等宽链。
  // term 就绪后顺带做 resize 踹脚（见 nudgeResize）。
  function fixFont() {
    try {
      if (typeof term !== 'undefined' && term.options) {
        term.options.fontFamily =
          '"Noto Sans Mono","Roboto Mono","Droid Sans Mono",monospace';
        nudgeResize();
        return true;
      }
    } catch (e) { /* term 未就绪则稍后重试 */ }
    return false;
  }
  if (!fixFont()) {
    var fontTimer = setInterval(function () { if (fixFont()) clearInterval(fontTimer); }, 500);
    setTimeout(function () { clearInterval(fontTimer); }, 15000);
  }

  // ---------- 会话 TUI 模式记忆（跨页面重载）----------
  // ttyd 不向后来接入的客户端重放终端模式（alt-screen/鼠标上报）；页面重进即失同步，
  // normal buffer 下 TUI 的全量重绘会追加成重复帧（2026-10-04 用户实锤"两遍 π 启动头"）。
  // 嗅探输出流里的模式序列并按端口存 localStorage；重载时先恢复模式再 resize 踹脚，
  // TUI 重绘落进 alt 屏（覆盖而非追加）。已知局限：TUI 在页面离开期间退出时状态过期，
  // 页面会停留在 alt 屏（reset 可解）；完整修复需带输出历史的 WS 代理（产品期）。
  var TUI_KEY = '__dkTui.' + location.port;
  function tuiSave(st) {
    try { localStorage.setItem(TUI_KEY, JSON.stringify(st)); } catch (e) {}
  }
  var tuiState = { alt: false, mouse: false };
  try {
    var t0 = window.term;
    if (t0) {
      var origWrite = t0.write.bind(t0);
      var dec = new TextDecoder('utf-8');
      t0.write = function (data) {
        try {
          var s = typeof data === 'string' ? data : dec.decode(data);
          if (s.indexOf('\x1b[?1049h') >= 0 || s.indexOf('\x1b[?47h') >= 0) tuiState.alt = true;
          if (s.indexOf('\x1b[?1049l') >= 0 || s.indexOf('\x1b[?47l') >= 0) tuiState.alt = false;
          if (/\x1b\[\?(1000|1002|1003|1006)h/.test(s)) tuiState.mouse = true;
          if (/\x1b\[\?(1000|1002|1003|1006)l/.test(s)) tuiState.mouse = false;
          tuiSave(tuiState);
        } catch (e) { /* 嗅探失败不影响正常输出 */ }
        return origWrite(data);
      };
    }
  } catch (e) { /* term 未就绪则跳过（模式靠既有存量） */ }

  // ---------- resize 踹脚（2026-10-04 滞后接入实证）----------
  // ttyd 服务端对新客户端无屏幕重放：TUI 启动后才接入的页面只有等新输出才有内容。
  // 载入后先恢复记忆的终端模式，再双次 resize（真尺寸变化 → 内核 SIGWINCH → dtach 链
  // → TUI 重绘），重绘落进（恢复的）alt 屏即覆盖而非追加。
  var nudged = false;
  function nudgeResize() {
    if (nudged) return;
    nudged = true;
    try {
      var saved = null;
      try { saved = JSON.parse(localStorage.getItem(TUI_KEY) || 'null'); } catch (e) {}
      var restored = false;
      if (saved && saved.alt) { term.write('\x1b[?1049h'); restored = true; }
      if (saved && saved.mouse) { term.write('\x1b[?1000h\x1b[?1002h\x1b[?1006h'); restored = true; }
      var c = term.cols, r = term.rows;
      setTimeout(function () {
        try {
          term.resize(c, r - 1);
          setTimeout(function () {
            try { term.resize(c, r); } catch (e) {}
            if (restored) canaryValidate();
          }, 250);
        } catch (e) {}
      }, 120);
    } catch (e) { /* 不具备 resize 能力则放弃，不影响主功能 */ }
  }

  // 金丝雀自愈：恢复的记忆可能过期（TUI 在页面离开期间退出——2026-10-04 实锤其
  // 恶性形态：滚轮序列被 bash 当键盘输入，回显 M64/M65 垃圾进命令行甚至提交执行）。
  // 恢复模式后发一个滚轮事件探路：TUI 活着会静默消费；bash 会把序列尾巴回显出来。
  // 检测到回显 → 记忆过期 → 撤销模式、清行、清记忆，页面回落 normal buffer。
  function canaryValidate() {
    try {
      var el = document.querySelector('.xterm-screen') || document.querySelector('.terminal');
      el.dispatchEvent(new WheelEvent('wheel', { bubbles: true, cancelable: true, deltaY: 16, deltaMode: 0 }));
      setTimeout(function () {
        try {
          var t = window.term, b = t.buffer.active, hit = false;
          for (var i = Math.max(0, t.rows - 3); i < t.rows; i++) {
            var l = b.getLine(i);
            if (l && /M6[0-9]/.test(l.translateToString(true))) { hit = true; break; }
          }
          if (hit) {
            t.write('\x1b[?1049l');
            t.write('\x1b[?1000l\x1b[?1002l\x1b[?1006l');
            tuiSave({ alt: false, mouse: false });
            window.__dk.sendKey({ key: 'c', code: 'KeyC', keyCode: 67, which: 67, ctrlKey: true });
          }
        } catch (e) {}
      }, 400);
    } catch (e) {}
  }

  // ---------- 原生手势层落点（2026-10-04）----------
  // 宿主在 View 层接管拖动/甩动（真手指轨迹页面层拿不到，见 TerminalTouchLayout 注释），
  // 位移按显示帧回调到这里，按行高换算成 wheel 打给 xterm——两种 buffer 的语义
  // （鼠标模式转 SGR 序列 / normal buffer 滚自身缓冲）都由 xterm 的 wheel 链处理。
  window.__dkScroll = function (dyCss) {
    var t = window.term;
    var vp = document.querySelector('.xterm-viewport');
    if (!t || !vp) return;
    var rH = vp.clientHeight / (t.rows || 1);
    if (!(rH > 0)) return;
    window.__dkScrollAcc = (window.__dkScrollAcc || 0) + dyCss / rH;
    var n = Math.trunc(window.__dkScrollAcc);
    if (!n) return;
    window.__dkScrollAcc -= n;
    var el = document.querySelector('.xterm-screen') || document.querySelector('.terminal');
    for (var i = 0; i < Math.abs(n); i++) {
      el.dispatchEvent(new WheelEvent('wheel', {
        bubbles: true, cancelable: true, deltaY: n > 0 ? rH : -rH, deltaMode: 0
      }));
    }
  };

  // ---------- 凭据补丁 ----------
  // WebView 的 basic auth 凭据缓存不进页面 JS 发起的 fetch/ws，
  // 而 ttyd 1.7 的 /token（一次性 AuthToken 的来源）受 basic auth 保护。
  // 给 /token 请求补 Authorization 头（值由宿主注入 window.__DRYDOCK_CRED）。
  var cred = window.__DRYDOCK_CRED;
  if (cred) {
    var origFetch = window.fetch;
    window.fetch = function (input, init) {
      try {
        var url = typeof input === 'string' ? input : (input && input.url) || '';
        if (url.indexOf('/token') >= 0) {
          init = init || {};
          var headers = new Headers(init.headers || {});
          headers.set('Authorization', 'Basic ' + cred);
          init.headers = headers;
        }
      } catch (e) { /* 保持原样 */ }
      return origFetch.call(this, input, init);
    };
  }

  // ---------- 触摸与键盘注入（键条本体已迁原生，见 TerminalActivity.buildKeyBar） ----------
  var css = document.createElement('style');
  css.textContent =
    // touch-action:none：合成器不再截走触摸流（2026-10-04 真机手势实测：
    // auto 下每手势仅一个 move 到达 JS ≈ 固定滚 1-2 行；拖动已由原生层接管，
    // 此处兜底剩余直达页面的触摸）
    '.terminal,.xterm,.xterm-screen,.xterm-viewport{touch-action:none;}';
  document.head.appendChild(css);

  function sendKey(init) {
    var ta = document.querySelector('.xterm-helper-textarea') || document.querySelector('.terminal');
    if (!ta) return;
    var ev = new KeyboardEvent('keydown', Object.assign({
      bubbles: true, cancelable: true
    }, init));
    ta.dispatchEvent(ev);
  }

  // Ctrl 粘滞：点亮后拦截下一个字母键，合成 Ctrl+字母（IME 输入不受影响——只拦单字母 keydown）。
  // 状态由原生键条的 Ctrl 按钮经 armCtrl 切换（页面 DOM 键条时代的自切按钮已迁走）。
  var ctrlArmed = false;
  function setCtrl(on) {
    ctrlArmed = on;
  }
  document.addEventListener('keydown', function (e) {
    // !e.ctrlKey：不拦自带 Ctrl 的事件（含本处理器合成的回环），否则自递归
    if (ctrlArmed && !e.ctrlKey && e.key && e.key.length === 1 && /[a-z]/i.test(e.key)) {
      e.stopPropagation();
      e.preventDefault();
      setCtrl(false); // 先解除再合成
      var lower = e.key.toLowerCase();
      var code = lower.toUpperCase().charCodeAt(0);
      sendKey({ key: lower, code: 'Key' + lower.toUpperCase(), keyCode: code, which: code, ctrlKey: true });
    }
  }, true);

  // 双击终端区 = 回车；单击保持原生行为（聚焦拉输入法）
  var lastTap = 0, lastX = 0, lastY = 0;
  var termArea = document.querySelector('.terminal');
  if (termArea) {
    termArea.addEventListener('touchend', function (e) {
      var t = e.changedTouches[0];
      var now = Date.now();
      if (now - lastTap < 320 &&
          Math.abs(t.clientX - lastX) < 40 && Math.abs(t.clientY - lastY) < 40) {
        sendKey({ key: 'Enter', code: 'Enter', keyCode: 13, which: 13 });
        lastTap = 0;
      } else {
        lastTap = now; lastX = t.clientX; lastY = t.clientY;
      }
    }, { passive: true });
  }

  // 验收钩子：CDP 可直接调用/断言（无视觉环境）
  window.__dk = {
    sendKey: sendKey,
    keyLabels: ['Ctrl','Esc','Tab','⇧Tab','PgUp','PgDn','←','↑','↓','→','↵'],
    armCtrl: setCtrl,
    isCtrlArmed: function () { return ctrlArmed; },
  };

  // ---------- AV2 观测桥 ----------
  function readTerminal() {
    var el = document.querySelector('.terminal');
    return el ? el.innerText : '';
  }

  var last = '';
  var timer = null;
  function schedule() {
    if (timer) return;
    timer = setTimeout(function () {
      timer = null;
      var t = readTerminal();
      if (t !== last) {
        last = t;
        console.log('AV2TEXT:' + t.slice(-600));
      }
    }, 300);
  }

  if (termArea) {
    new MutationObserver(schedule).observe(termArea, {
      childList: true, subtree: true, characterData: true
    });
    console.log('AV2READY rows=' + readTerminal().length);
  } else {
    console.log('AV2READY no-terminal-element');
  }
})();
