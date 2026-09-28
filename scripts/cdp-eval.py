#!/usr/bin/env python3
"""在设备 WebView 里执行 JS 并取结果（CDP Runtime.evaluate）。
用法: scripts/cdp-eval.py "<js 表达式>"
前置: adb forward tcp:9222 localabstract:webview_devtools_remote_<app_pid>
依赖: uv run --with websockets scripts/cdp-eval.py ...
"""
import asyncio
import json
import subprocess
import sys
import urllib.request

import websockets

ADB = "/Users/scliang/Library/Android/sdk/platform-tools/adb"


def app_pid() -> str:
    out = subprocess.run([ADB, "shell", "ps", "-A"], capture_output=True, text=True).stdout
    for line in out.splitlines():
        if "dev.drydock.prototype" in line and line.split()[2] in ("1",):
            pass
        if "dev.drydock.prototype" in line:
            parts = line.split()
            if len(parts) > 2 and parts[2] != "0" and parts[1] != "PID":
                # 主进程：ppid 较大者为子进程，取 ppid 最小的
                return parts[1]
    return ""


async def main():
    expr = sys.argv[1]
    # 列出可调试页面
    pages = json.load(urllib.request.urlopen("http://127.0.0.1:9222/json", timeout=5))
    page = next((p for p in pages if "127.0.0.1" in p.get("url", "")), pages[0])
    ws_url = page["webSocketDebuggerUrl"]
    async with websockets.connect(ws_url, max_size=2**22) as ws:
        await ws.send(json.dumps({
            "id": 1,
            "method": "Runtime.evaluate",
            "params": {"expression": expr, "returnByValue": True},
        }))
        while True:
            msg = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
            if msg.get("id") == 1:
                print(json.dumps(msg.get("result", {}).get("result", {}).get("value"), ensure_ascii=False))
                return


asyncio.run(main())
