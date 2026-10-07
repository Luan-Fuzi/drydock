#!/usr/bin/env python3
"""CDP 通道共用件（cdp-eval.py / cdp-type.py 共享）：WebView DevTools 连接。

前置：scommon.cdp_forward() 已把设备主进程 webview_devtools_remote 转到本机 9222。
依赖：uv run --with websockets scripts/cdp_common.py 内的函数。
"""
import json
import urllib.request


def pick_page():
    """选目标页面：优先 ttyd 终端页（127.0.0.1），否则第一个。"""
    pages = json.load(urllib.request.urlopen("http://127.0.0.1:9222/json", timeout=5))
    return next((p for p in pages if "127.0.0.1" in p.get("url", "")), pages[0])
