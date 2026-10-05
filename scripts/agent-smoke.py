#!/usr/bin/env python3
"""agent 链路真机冒烟（AV3 口径）：debug 通道注入 GLM key → 装 agent 层
（Node + Claude Code，npmmirror）→ 跑一次真实对话并验证产物落袋。

用法：ANDROID_SERIAL=<serial> python3 scripts/agent-smoke.py [key]
      key 缺省读 draft/api-key.txt（AV3 同款通道）。
输出：draft/agent-smoke.json；产物在设备 /sdcard/Download/Drydock/（可只读 pull）。
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scommon as sc


def main():
    key = sys.argv[1] if len(sys.argv) > 1 else None
    if not key:
        p = os.path.join(sc.DRAFT, "api-key.txt")
        if not os.path.exists(p):
            sys.exit("没有 key：传参或放 draft/api-key.txt")
        key = open(p).read().strip()
    if not key:
        sys.exit("draft/api-key.txt 为空")

    report = {"started": time.strftime("%Y-%m-%d %H:%M:%S"), "device": sc.device_identity()}
    print(f"设备：{json.dumps(report['device'], ensure_ascii=False)}")

    print("\n== 安装 agent 层（Node + Claude Code，npmmirror，最长 25 分钟）")
    if not sc.tap_text("安装 agent 层", 60):
        sys.exit("找不到「安装 agent 层」按钮")
    t0 = time.time()
    xml = ""
    while time.time() - t0 < 1500:
        xml = sc.ui_dump()
        if xml and ("agent 层就绪" in xml or "✗" in xml):
            break
        time.sleep(10)
    if not xml or "agent 层就绪" not in xml:
        report["agent_layer"] = "failed"
        json.dump(report, open(os.path.join(sc.DRAFT, "agent-smoke.json"), "w"), ensure_ascii=False, indent=2)
        sys.exit(f"agent 层未就绪（25 分钟超时），UI 片段：{(xml or '')[:300]}")
    report["agent_layer_s"] = round(time.time() - t0)
    print(f"agent 层就绪，耗时 {report['agent_layer_s']} 秒")

    print("\n== key 写入环境变量（env.sh；AV3 运行时经 runInEnv source）")
    out = sc.env_read(
        "mkdir -p /root/.drydock && touch /root/.drydock/env.sh\n"
        "grep -q DRYDOCK_API_KEY /root/.drydock/env.sh 2>/dev/null || "
        f"printf 'export DRYDOCK_API_KEY=%s\\n' '{key}' >> /root/.drydock/env.sh\n"
        "grep -q ANTHROPIC_AUTH_TOKEN /root/.drydock/env.sh 2>/dev/null || "
        f"printf 'export ANTHROPIC_AUTH_TOKEN=%s\\n' '{key}' >> /root/.drydock/env.sh\n"
        "echo KEY_ENV_OK\n", timeout=90)
    assert "KEY_ENV_OK" in out, f"key 环境变量写入失败：{out[-200:]}"

    print("\n== 跑 AV3 真实对话（最长 6 分钟，花 GLM 额度）")
    if not sc.tap_text("运行 AV3", 60):
        sys.exit("找不到「运行 AV3」按钮")
    t0 = time.time()
    xml = ""
    while time.time() - t0 < 360:
        xml = sc.ui_dump()
        if xml and ("AV3 通过" in xml or "AV3 未通过" in xml):
            break
        time.sleep(10)
    report["av3_s"] = round(time.time() - t0)
    passed = bool(xml) and "AV3 通过" in xml
    report["av3_passed"] = passed
    if passed:
        m = xml
        report["landed"] = "产物已落 Downloads/Drydock"
        out = sc.shell("ls", "-t", "/sdcard/Download/Drydock/")
        report["downloads_files"] = out.strip().splitlines()[:5]
    report["finished"] = time.strftime("%Y-%m-%d %H:%M:%S")
    json.dump(report, open(os.path.join(sc.DRAFT, "agent-smoke.json"), "w"), ensure_ascii=False, indent=2)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    main()
