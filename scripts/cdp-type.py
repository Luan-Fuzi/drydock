#!/usr/bin/env python3
"""CDP trusted 键入：Input.dispatchKeyEvent 模拟真实键盘（xterm 只信 isTrusted 事件）。
用法: uv run --with websockets scripts/cdp-type.py "文本[\\n 结尾表示回车]"
"""
import asyncio
import json
import sys
import urllib.request

import websockets


async def send_key(ws, i, **params):
    await ws.send(json.dumps({"id": i, "method": "Input.dispatchKeyEvent", "params": params}))
    while True:
        msg = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
        if msg.get("id") == i:
            return


async def main():
    text = sys.argv[1].encode().decode("unicode_escape")
    pages = json.load(urllib.request.urlopen("http://127.0.0.1:9222/json", timeout=5))
    page = next((p for p in pages if "127.0.0.1" in p.get("url", "")), pages[0])
    async with websockets.connect(page["webSocketDebuggerUrl"], max_size=2**22) as ws:
        i = 0
        for ch in text:
            await asyncio.sleep(0.12)  # 快注会让 bash 行编辑错乱/丢字，按近人速键入
            if ch == "\n":
                i += 1
                await send_key(ws, i, type="keyDown", key="Enter", code="Enter",
                               windowsVirtualKeyCode=13, nativeVirtualKeyCode=13)
                i += 1
                await send_key(ws, i, type="keyUp", key="Enter", code="Enter",
                               windowsVirtualKeyCode=13, nativeVirtualKeyCode=13)
            else:
                i += 1
                await send_key(ws, i, type="keyDown", key=ch,
                               text=ch, unmodifiedText=ch,
                               windowsVirtualKeyCode=ord(ch))
                i += 1
                await send_key(ws, i, type="keyUp", key=ch, windowsVirtualKeyCode=ord(ch))
        print(f"typed {len(text)} chars")


asyncio.run(main())
