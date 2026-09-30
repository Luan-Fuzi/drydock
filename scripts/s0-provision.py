#!/usr/bin/env python3
"""S0 真机装机供给：装 APK → UI 驱动部署 rootfs → AV1 → 终端层 → 开终端会话。

真机周第一步（步骤 6）。全程可插线；AV1 部署计时在此采集（真机口径）。
前置：用户已在手机上开好开发者选项与 USB 调试（见 docs/dev-environment.md 待办），
屏幕保持解锁。用法：
  ANDROID_SERIAL=<serial> python3 scripts/s0-provision.py [apk 路径=draft/drydock-probe-ready.apk]
输出：draft/s0-provision.json
"""
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scommon as sc

from adbdev import adb_prefix


def step(name):
    print(f"\n== {name}", flush=True)


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else os.path.join(sc.DRAFT, "drydock-probe-ready.apk")
    if not os.path.exists(apk):
        sys.exit(f"APK 不存在：{apk}")

    report = {"apk": apk, "started": time.strftime("%Y-%m-%d %H:%M:%S")}
    step("设备与包状态")
    report["device"] = sc.device_identity()
    print(json.dumps(report["device"], ensure_ascii=False))
    report["already_installed"] = sc.package_installed()

    step(f"安装 APK（{apk}）")
    r = sc.run(adb_prefix() + ["install", "-r", apk], timeout=180)
    print(r.stdout.strip() or r.stderr.strip())
    if "Success" not in (r.stdout + r.stderr):
        report["install"] = "failed"
        json.dump(report, open(os.path.join(sc.DRAFT, "s0-provision.json"), "w"), ensure_ascii=False, indent=2)
        sys.exit("安装失败（检查 HyperOS「USB 安装」开关：可能要求登录小米账号+SIM+联网）")
    report["install"] = "ok"
    sc.shell("pm", "grant", sc.PKG, "android.permission.POST_NOTIFICATIONS")

    step("启动 app（请保持屏幕解锁）")
    sc.shell("am", "start", "-n", sc.MAIN_ACTIVITY)
    if not sc.wait_text("Drydock 原型", 60):
        sys.exit("MainActivity 未出现（锁屏？前台非 drydock？看手机屏幕）")

    step("proot 自检")
    if not sc.tap_text("运行 proot 自检", 30):
        sys.exit("找不到「运行 proot 自检」按钮")
    if not sc.wait_text("通过（exit=", 300):
        sys.exit("自检未通过")

    step("部署 rootfs（AV1 真机计时，最长 15 分钟）")
    xml = sc.ui_dump()
    resumed = bool(xml) and ("已部署" in xml or "部署完成" in xml)
    if resumed:
        print("rootfs 已部署（续跑），跳过部署步骤")
        report["rootfs_deploy_s"] = "skipped-resume"
    else:
        if not sc.tap_text("下载并部署 rootfs", 30):
            sys.exit("找不到「下载并部署 rootfs」按钮")
        t0 = time.time()
        xml = ""
        while time.time() - t0 < 900:
            xml = sc.ui_dump()
            if xml and ("✓ 部署完成" in xml or "✗" in xml):
                break
            time.sleep(5)
        m = re.search(r"部署完成（(\d+) 秒）", xml or "")
        report["rootfs_deploy_s"] = int(m.group(1)) if m else None
        if not m:
            sys.exit(f"部署未完成（900s 超时或失败），UI 片段：{(xml or '')[:200]}")
        print(f"部署耗时 {report['rootfs_deploy_s']} 秒")

    step("AV1 环境自检")
    if not sc.tap_text("进入环境自检（AV1）", 30):
        sys.exit("找不到 AV1 按钮")
    if not sc.wait_text("AV1 通过", 600):
        sys.exit("AV1 未通过")
    xml = sc.ui_dump()
    m = re.search(r"AV1 通过（(\d+) 秒）", xml or "")
    report["av1_s"] = int(m.group(1)) if m else None

    step("安装终端层（幂等，几分钟）")
    if not sc.tap_text("安装终端层", 30):
        sys.exit("找不到「安装终端层」按钮")
    if not sc.wait_text("终端层就绪", 900):
        sys.exit("终端层未就绪")

    step("打开终端会话")
    if not sc.tap_text("打开终端", 30):
        sys.exit("找不到「打开终端」按钮")
    sess = None
    t0 = time.time()
    while time.time() - t0 < 90:
        sess = sc.main_session()
        if sess:
            break
        time.sleep(3)
    report["main_session"] = bool(sess)
    if not sess:
        sys.exit("main 会话未在注册表出现（看 logcat DrydockEnv/DrydockTerminal）")

    report["finished"] = time.strftime("%Y-%m-%d %H:%M:%S")
    out = os.path.join(sc.DRAFT, "s0-provision.json")
    json.dump(report, open(out, "w"), ensure_ascii=False, indent=2)
    print(f"\nS0 完成，报告：{out}")
    print("下一步：ANDROID_SERIAL=<serial> python3 scripts/s1-soak.py [分钟=35]（拔线采样）")


if __name__ == "__main__":
    main()
