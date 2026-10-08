// Drydock 终端覆盖层：虚拟键条 + AV2 观测桥。
// 观测桥：terminal 渲染文本变化 → console.log("AV2TEXT:…") → 宿主 WebChromeClient → logcat。
(function () {
  if (window.__drydockOverlay) return;
  window.__drydockOverlay = true;

  // nudge 状态必须在 IIFE 任何早期同步链（fixFont→applyCfg→nudgeResize，首调在第
  // ~37 行）之前初始化：声明靠后时守卫读到 undefined 直接放行，term 就绪快的加载
  // 不等 remote 就恢复，随后被 ttyd attach 期的 soft-reset 冲掉（2026-10-07 AVD
  // 打点两轮实锤 nudged=undefined → 恢复→canary 全部空转）
  var nudged = false, sawRemote = false, pendingNudge = false;
  var overlayLoadedAt = Date.now();

  // ---------- 字体修正（D24 实测）----------
  // ttyd 默认字体链（Consolas/Liberation/Menlo/Courier）在 Android 全不存在，
  // 落到通用 monospace 后缺 U+23F5(⏵) 等字形变豆腐块；换安卓实际有的等宽链。
  // term 就绪后顺带做 resize 踹脚（见 nudgeResize）。

  // ---------- 显示配置（设置页 TermPrefs → 宿主注入）----------
  // 首载走 window.__DK_CFG；运行中（设置页改完回到终端页）经 __dk.applyCfg 推送。
  // 字号变化重排后须 resize 踹脚，服务端 cols/rows 才跟上。
  function applyCfg(cfg) {
    try {
      if (typeof term === 'undefined' || !term.options) return false;
      if (cfg && cfg.fontSize) term.options.fontSize = cfg.fontSize;
      if (cfg && cfg.scrollback) term.options.scrollback = cfg.scrollback;
      nudgeResize(true);
      return true;
    } catch (e) { return false; }
  }

  function fixFont() {
    try {
      if (typeof term !== 'undefined' && term.options) {
        term.options.fontFamily =
          '"Noto Sans Mono","Roboto Mono","Droid Sans Mono",monospace';
        installSniff(); // term 就绪顺带装嗅探（注入早于 term 创建时此前永不安装）
        applyCfg(window.__DK_CFG || {});
        return true;
      }
    } catch (e) { /* term 未就绪则稍后重试 */ }
    return false;
  }
  if (!fixFont()) {
    var fontTimer = setInterval(function () { if (fixFont()) clearInterval(fontTimer); }, 500);
    setTimeout(function () { clearInterval(fontTimer); }, 15000);
  }

  // ---------- Android IME 回车丢字补丁（2026-10-07 真机实锤）----------
  // 病灶：Android 的 IME 提交都包在 keydown(229)/keyup 之间，xterm 5.3.0（ttyd
  // 1.7.4 内嵌）靠 keydown(229) 快照 + setTimeout(0) 差分发送，发完不清
  // textarea。微信输入法的换行键会先清理 IME 编辑状态（textarea 残留被清空
  // =值变短），差分逻辑把它误译成一个 DEL 发给终端——回车发送前输入框最后
  // 一字被删（实测「今天几号」回车只发出「今天几」；自家键条 ↵ 走 keydown
  // (13) 无此问题）。修法：变短分支不再立即发 DEL——整段清空（多字）直接判
  // 换行清理丢弃；其余挂起 40ms，期间（或紧前 60ms 内）出现回车即判定随行
  // 清理一并丢弃，无回车则如期补发（真退格通道不变）。增长/组合路径原样。
  // 已知残留：单字残留 + 超窗口的慢回车（Enter 距清理 >40ms）仍会漏发一个
  // DEL——真机换行序列实测间隔决定是否再收紧。
  function patchImeEnter() {
    try {
      var ch = window.term && term._core && term._core._compositionHelper;
      if (!ch || !ch._textarea || !ch._coreService) return false;
      if (ch.__dkImePatched) return true;
      var ta = ch._textarea;
      var PEND_MS = 40, LOOKBACK_MS = 60;
      var pendings = [], lastEnterAt = 0;
      ta.addEventListener('keydown', function (e) {
        if (e.keyCode === 13) {
          lastEnterAt = Date.now();
          // 回车随行 = 换行清理，撤掉挂起的 DEL
          pendings.forEach(function (p) { clearTimeout(p.timer); });
          pendings = [];
        }
      }, true);
      ch._handleAnyTextareaChanges = function () {
        var self = this;
        var oldValue = ta.value;
        setTimeout(function () {
          try {
            if (self._isComposing) return;
            var newValue = ta.value;
            var diff = newValue.replace(oldValue, '');
            self._dataAlreadySent = diff;
            if (newValue.length > oldValue.length) {
              self._coreService.triggerDataEvent(diff, true);
            } else if (newValue.length < oldValue.length) {
              if (newValue === '' && oldValue.length >= 2) return; // 整段清空=换行清理
              if (Date.now() - lastEnterAt < LOOKBACK_MS) return;  // 回车紧前（同任务形态）
              var p = { fire: function () { self._coreService.triggerDataEvent('\x7f', true); } };
              p.timer = setTimeout(function () {
                pendings = pendings.filter(function (x) { return x !== p; });
                p.fire();
              }, PEND_MS);
              pendings.push(p);
            } else if (newValue !== oldValue) {
              self._coreService.triggerDataEvent(newValue, true);
            }
          } catch (e) { /* 补丁失败不崩输入链路 */ }
        }, 0);
      };
      ch.__dkImePatched = true;
      return true;
    } catch (e) { return false; }
  }
  if (!patchImeEnter()) {
    var imeTimer = setInterval(function () { if (patchImeEnter()) clearInterval(imeTimer); }, 500);
    setTimeout(function () { clearInterval(imeTimer); }, 15000);
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
  // 模式保真（2026-10-07）：只存 mouse bool 恢复时无脑加 1006(SGR) 会偷换编码——
  // TUI 真实只开 1000(X10) 时，恢复后的 SGR 字节流进期待 X10 的 TUI 解析不了，
  // bubbletea 往输入框插字符（真机 C/y/u 垃圾的另一半来源，X10 三原始字节恰落
  // 可打印区：列 85→u、行 89→y、button 35→C）。嗅探按 1000/1002/1003/1006 分别
  // 记，恢复按原样发；旧存量记忆（仅 mouse bool）回落三连，行为同旧版。
  var tuiState = { alt: false, mouse: false };
  function installSniff() {
    try {
      if (window.__dkSniff) return true;
      var t0 = window.term;
      if (!t0 || !t0.write) return false;
      window.__dkSniff = true;
      var origWrite = t0.write.bind(t0);
      var dec = new TextDecoder('utf-8');
      // 透传全部参数：write(data, cb) 的解析完成回调被吞会让依赖回调的调用方
      //（canary 探针）永远不触发（2026-10-07 AVD 实锤 A2 稳定 miss 的根源）
      t0.write = function () {
        var data = arguments[0];
        try {
          var s = typeof data === 'string' ? data : dec.decode(data);
          var dirty = false;
          if (s.indexOf('\x1b[?1049h') >= 0 || s.indexOf('\x1b[?47h') >= 0) { tuiState.alt = true; dirty = true; }
          if (s.indexOf('\x1b[?1049l') >= 0 || s.indexOf('\x1b[?47l') >= 0) { tuiState.alt = false; dirty = true; }
          var mM = s.match(/\x1b\[\?(1000|1002|1003|1006)[hl]/g);
          if (mM) {
            for (var i = 0; i < mM.length; i++) {
              var mm = mM[i].match(/(\d+)([hl])$/);
              if (mm) {
                tuiState['m' + mm[1]] = mm[2] === 'h';
                tuiState.mouse = !!(tuiState.m1000 || tuiState.m1002 || tuiState.m1003);
                dirty = true;
              }
            }
          }
          if (dirty) tuiSave(tuiState);
        } catch (e) { /* 嗅探失败不影响正常输出 */ }
        // 首批外部数据（ttyd 服务端来的，非本地恢复序列）→ 触发挂起的模式恢复
        if (!window.__dkRestoring && !sawRemote) {
          sawRemote = true;
          if (pendingNudge) setTimeout(function () { doNudge(); }, 300);
        }
        return origWrite.apply(t0, arguments);
      };
      return true;
    } catch (e) { return false; /* term 未就绪则靠 fixFont 轮询重装 */ }
  }

  // ---------- resize 踢脚（2026-10-04 滞后接入实证 / 2026-10-07 竞态修正）----------
  // ttyd 服务端对新客户端无屏幕重放：TUI 启动后才接入的页面只有等新输出才有内容。
  // 载入后先恢复记忆的终端模式，再双次 resize（真尺寸变化 → 内核 SIGWINCH → dtach 链
  // → TUI 重绘），重绘落进（恢复的）alt 屏即覆盖而非追加。
  // 竞态修正：ttyd attach 期服务端的 soft-reset 会冲掉过早的本地恢复（AVD 实证
  // flaky——同一测试时过时不过），恢复推迟到首批外部数据后 300ms 或 2.5s 兜底。
  function doNudge() {
    if (nudged || !window.term) return; // term 未就绪不烧 nudged（兜底先到时让位 fixFont 重试）
    nudged = true;
    pendingNudge = false;
    try {
      var saved = null;
      try { saved = JSON.parse(localStorage.getItem(TUI_KEY) || 'null'); } catch (e) {}
      var restored = false;
      window.__dkRestoring = true; // 嗅探 hook 不把恢复序列当外部数据
      try {
        if (saved && saved.alt) { term.write('\x1b[?1049h'); restored = true; }
        if (saved && saved.mouse) {
          // 新存量：按嗅探到的模式原样恢复；旧存量（无 m 字段）：回落三连（含 1006，
          // 保 canary 探针走 SGR——分号数字指纹可靠，X10 原始字节形态误伤面大不采用）
          var modes = ['m1000', 'm1002', 'm1003', 'm1006'].filter(function (k) { return saved[k]; });
          if (modes.length) {
            for (var i = 0; i < modes.length; i++) term.write('\x1b[?' + modes[i].slice(1) + 'h');
          } else {
            term.write('\x1b[?1000h\x1b[?1002h\x1b[?1006h');
          }
          restored = true;
        }
      } finally { window.__dkRestoring = false; }
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
  function nudgeResize(force) {
    if (nudged && !force) return;
    if (!sawRemote && Date.now() - overlayLoadedAt < 2500) { pendingNudge = true; return; }
    doNudge();
  }
  setTimeout(function () { if (pendingNudge && !nudged) doNudge(); }, 2600);

  // 金丝雀自愈：恢复的记忆可能过期（TUI 在页面离开期间退出——2026-10-04 实锤其
  // 恶性形态：滚轮序列被 bash 当键盘输入，回显垃圾进命令行甚至提交执行）。
  // 恢复模式后发一个滚轮事件探路：TUI 活着会静默消费；bash 会把序列尾巴回显出来。
  // 检测到回显 → 记忆过期 → 撤销模式、清行、清记忆，页面回落 normal buffer。
  // 判据（2026-10-07 pty 回显采样修正）：bash 对 SGR 鼠标序列剥壳回显为
  // 「数字;数字;数字M/m」（如 64;1;1M、0;11;11m）——M 在尾部；旧判据 /M6[0-9]/
  // 只在连发多序列（前一个的 M 撞上后一个的 64）时碰巧命中，单滚轮漏检，
  // 真机实锤 C/y/u 等垃圾残留即此。残迹指纹误伤面≈0（正常文本不出此模式）。
  function canaryValidate() {
    try {
      var el = document.querySelector('.xterm-screen') || document.querySelector('.terminal');
      var saved = null;
      try { saved = JSON.parse(localStorage.getItem(TUI_KEY) || 'null'); } catch (e) {}
      var sgrOnly = saved && saved.m1006; // 探针统一走 SGR：临时补开 1006，检测后还原
      // 探针在临时编码 write 的解析完成回调里发（setTimeout 不保证 xterm write
      // 缓冲已解析——AVD 实证模式未生效时 wheel 被本地消化、pty 零帧）；三连发
      // 提高回显采样率
      function probe() {
        for (var i = 0; i < 3; i++) {
          el.dispatchEvent(new WheelEvent('wheel', { bubbles: true, cancelable: true, deltaY: 16, deltaMode: 0 }));
        }
      }
      if (!sgrOnly) term.write('\x1b[?1006h', function () { setTimeout(probe, 60); });
      else setTimeout(probe, 60);
      setTimeout(function () {
        try {
          var t = window.term, b = t.buffer.active, hit = false;
          // 检查光标行附近而非视口底部——TUI 退出后光标可能停在视口中部
          //（2026-10-07 实证漏检：bash 回显在光标行，视口底部是空行）
          var cur = b.baseY + b.cursorY;
          for (var i = Math.max(0, cur - 2); i <= Math.min(b.length - 1, cur); i++) {
            var l = b.getLine(i);
            if (l && /(?:\d+;){1,2}\d+[Mm]/.test(l.translateToString(true))) { hit = true; break; }
          }
          if (hit) {
            t.write('\x1b[?1049l');
            t.write('\x1b[?1000l\x1b[?1002l\x1b[?1003l\x1b[?1006l');
            tuiSave({ alt: false, mouse: false });
            window.__dk.sendKey({ key: 'c', code: 'KeyC', keyCode: 67, which: 67, ctrlKey: true });
          } else if (!sgrOnly) {
            term.write('\x1b[?1006l'); // 还原 X10-only 记忆的编码
          }
        } catch (e) {}
      }, 750);
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
    keyLabels: ['Ctrl','Esc','Tab','Shift+Tab','←','↑','↓','→','↵'],
    armCtrl: setCtrl,
    isCtrlArmed: function () { return ctrlArmed; },
    applyCfg: applyCfg,
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
