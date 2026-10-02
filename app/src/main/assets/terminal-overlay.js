// Drydock 终端覆盖层：虚拟键条 + AV2 观测桥。
// 观测桥：terminal 渲染文本变化 → console.log("AV2TEXT:…") → 宿主 WebChromeClient → logcat。
(function () {
  if (window.__drydockOverlay) return;
  window.__drydockOverlay = true;

  // ---------- 字体修正（D24 实测）----------
  // ttyd 默认字体链（Consolas/Liberation/Menlo/Courier）在 Android 全不存在，
  // 落到通用 monospace 后缺 U+23F5(⏵) 等字形变豆腐块；换安卓实际有的等宽链。
  function fixFont() {
    try {
      if (typeof term !== 'undefined' && term.options) {
        term.options.fontFamily =
          '"Noto Sans Mono","Roboto Mono","Droid Sans Mono",monospace';
        return true;
      }
    } catch (e) { /* term 未就绪则稍后重试 */ }
    return false;
  }
  if (!fixFont()) {
    var fontTimer = setInterval(function () { if (fixFont()) clearInterval(fontTimer); }, 500);
    setTimeout(function () { clearInterval(fontTimer); }, 15000);
  }

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

  // ---------- 虚拟键条（阶段 2：Ctrl 粘滞 + 显式回车 + 翻页，横向可滚动） ----------
  var css = document.createElement('style');
  css.textContent =
    '#drydock-keys{position:fixed;left:0;right:0;bottom:0;display:flex;gap:4px;' +
    'padding:4px 6px;background:rgba(20,20,20,.92);z-index:99999;' +
    'overflow-x:auto;white-space:nowrap;-webkit-overflow-scrolling:touch;}' +
    '#drydock-keys button{flex:0 0 auto;min-width:44px;padding:8px 10px;font-size:13px;color:#ddd;' +
    'background:#333;border:1px solid #555;border-radius:6px;}' +
    '#drydock-keys button.dk-armed{background:#166534;border-color:#22c55e;color:#fff;}';
  document.head.appendChild(css);

  function sendKey(init) {
    var ta = document.querySelector('.xterm-helper-textarea') || document.querySelector('.terminal');
    if (!ta) return;
    var ev = new KeyboardEvent('keydown', Object.assign({
      bubbles: true, cancelable: true
    }, init));
    ta.dispatchEvent(ev);
  }

  // Ctrl 粘滞：点亮后拦截下一个字母键，合成 Ctrl+字母（IME 输入不受影响——只拦单字母 keydown）
  var ctrlArmed = false;
  var ctrlBtn = null;
  function setCtrl(on) {
    ctrlArmed = on;
    if (ctrlBtn) ctrlBtn.classList.toggle('dk-armed', on);
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

  var KEYS = [
    ['Ctrl', null],
    ['Esc',   { key: 'Escape', code: 'Escape', keyCode: 27, which: 27 }],
    ['Tab',   { key: 'Tab', code: 'Tab', keyCode: 9, which: 9 }],
    ['⇧Tab',  { key: 'Tab', code: 'Tab', keyCode: 9, which: 9, shiftKey: true }],
    ['PgUp',  { key: 'PageUp', code: 'PageUp', keyCode: 33, which: 33 }],
    ['PgDn',  { key: 'PageDown', code: 'PageDown', keyCode: 34, which: 34 }],
    ['←',     { key: 'ArrowLeft', code: 'ArrowLeft', keyCode: 37, which: 37 }],
    ['↑',     { key: 'ArrowUp', code: 'ArrowUp', keyCode: 38, which: 38 }],
    ['↓',     { key: 'ArrowDown', code: 'ArrowDown', keyCode: 40, which: 40 }],
    ['→',     { key: 'ArrowRight', code: 'ArrowRight', keyCode: 39, which: 39 }],
    ['↵',     { key: 'Enter', code: 'Enter', keyCode: 13, which: 13 }],
  ];

  var bar = document.createElement('div');
  bar.id = 'drydock-keys';
  KEYS.forEach(function (k) {
    var b = document.createElement('button');
    b.textContent = k[0];
    if (k[0] === 'Ctrl') {
      ctrlBtn = b;
      b.addEventListener('click', function () { setCtrl(!ctrlArmed); });
    } else {
      b.addEventListener('click', function () { sendKey(k[1]); });
    }
    bar.appendChild(b);
  });
  document.body.appendChild(bar);

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

  // 终端区底部让出键条高度
  if (termArea) termArea.style.paddingBottom = '52px';

  // 验收钩子：CDP 可直接调用/断言（无视觉环境）
  window.__dk = {
    sendKey: sendKey,
    keyLabels: KEYS.map(function (k) { return k[0]; }),
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
