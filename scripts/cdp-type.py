#!/usr/bin/env python3
"""CDP 键入终端。文本走 Input.insertText（与 IME 提交同一条 textarea input
事件数据路径，中文原样 UTF-8 全链路）；回车走 Input.dispatchKeyEvent。
不逐字符派发 keydown：VK=ord(ch) 会撞功能键码（如 t=116 是 F5、-=45 是
Insert），xterm 按功能键发转义序列搅乱 bash 行编辑（AV2 后经验证）。
用法: uv run --with websockets scripts/cdp-type.py "文本[字面 \\n 两字符表示回车]"
"""
import asyncio
import json
import sys
import urllib.request

import websockets


async def rpc(ws, i, method, params):
    await ws.send(json.dumps({"id": i, "method": method, "params": params}))
    while True:
        msg = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
        if msg.get("id") == i:
            return msg


async def press_enter(ws, i):
    i += 1
    await rpc(ws, i, "Input.dispatchKeyEvent", {
        "type": "keyDown", "key": "Enter", "code": "Enter",
        "windowsVirtualKeyCode": 13, "nativeVirtualKeyCode": 13})
    i += 1
    await rpc(ws, i, "Input.dispatchKeyEvent", {
        "type": "keyUp", "key": "Enter", "code": "Enter",
        "windowsVirtualKeyCode": 13, "nativeVirtualKeyCode": 13})
    return i


async def main():
    # 按字面 "\n"（反斜杠+n 两字符）切分；不做 unicode_escape，保中文原样
    lines = sys.argv[1].split("\\n")
    pages = json.load(urllib.request.urlopen("http://127.0.0.1:9222/json", timeout=5))
    page = next((p for p in pages if "127.0.0.1" in p.get("url", "")), pages[0])
    async with websockets.connect(page["webSocketDebuggerUrl"], max_size=2**22) as ws:
        i = 0
        n = 0
        for seg in lines:
            if seg:
                i += 1
                await rpc(ws, i, "Input.insertText", {"text": seg})
                n += len(seg)
                await asyncio.sleep(0.15)  # 给 xterm input 事件处理留时间
            if seg is not lines[-1]:
                i = await press_enter(ws, i)
                n += 1
                await asyncio.sleep(0.15)
        print(f"typed {n} chars (insertText mode)")


asyncio.run(main())
