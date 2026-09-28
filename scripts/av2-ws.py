#!/usr/bin/env python3
"""AV2 WebSocket 旁路验证客户端：作为第二个客户端 attach dtach 会话，
直接观测/注入 pty 字节流。用法：
  scripts/av2-ws.py <port> <token> send <text>     # 发送文本并采集 3s 输出
  scripts/av2-ws.py <port> <token> watch <秒>      # 只采集
输出 JSON：{sent, received, ok_marker}
依赖：uv run --with websockets scripts/av2-ws.py ...
"""
import asyncio
import base64
import json
import sys
import urllib.request

import websockets


def get_auth_token(port: int, token: str) -> str:
    req = urllib.request.Request(f"http://127.0.0.1:{port}/token")
    req.add_header("Authorization", "Basic " + base64.b64encode(f"drydock:{token}".encode()).decode())
    with urllib.request.urlopen(req, timeout=5) as resp:
        return json.load(resp)["token"]


async def main():
    port, token, mode = int(sys.argv[1]), sys.argv[2], sys.argv[3]
    auth = get_auth_token(port, token)
    uri = f"ws://127.0.0.1:{port}/ws"
    received = []
    sent_ok = False
    # 注意：不带 Sec-WebSocket-Protocol 头——ttyd(lws) 未注册子协议，带 'tty' 握手会被直接断开
    async with websockets.connect(uri, compression=None, max_size=2**22) as ws:
        # ttyd 前端用 textEncoder.encode() 发二进制帧，服务端按二进制解析
        await ws.send(json.dumps({"AuthToken": auth, "columns": 90, "rows": 28}).encode())
        if mode == "send":
            text = sys.argv[4]
            await asyncio.sleep(1.0)
            await ws.send(text.encode())
            sent_ok = True
            collect = 4.0
        else:
            collect = float(sys.argv[4])
        try:
            end = asyncio.get_event_loop().time() + collect
            while True:
                remain = end - asyncio.get_event_loop().time()
                if remain <= 0:
                    break
                msg = await asyncio.wait_for(ws.recv(), timeout=remain)
                if isinstance(msg, bytes):
                    received.append(msg.decode("utf-8", "replace"))
                else:
                    received.append(str(msg))
        except asyncio.TimeoutError:
            pass
    out = "".join(received)
    print(json.dumps({
        "sent": sent_ok,
        "received_tail": out[-1500:],
        "bytes": len(out),
    }, ensure_ascii=False))


asyncio.run(main())
