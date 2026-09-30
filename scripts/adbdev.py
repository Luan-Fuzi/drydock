#!/usr/bin/env python3
"""adb 单设备封装（真机纪律，见 AGENTS.md「真机纪律」）。

所有宿主侧 Python 脚本的 adb 调用经 adb_prefix() 组命令：
- ANDROID_SERIAL 指定目标时校验其在线；
- 未指定时恰有一台在线设备才放行，多设备在线直接拒绝（防止误伤接入的其他设备）。
"""
import os
import subprocess
import sys

ADB = os.environ.get("ADB", os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))


def online_devices() -> list:
    out = subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout
    return [line.split("\t")[0] for line in out.splitlines()[1:] if line.endswith("\tdevice")]


def adb_prefix() -> list:
    serial = os.environ.get("ANDROID_SERIAL", "").strip()
    devs = online_devices()
    if serial:
        if serial not in devs:
            sys.exit(f"ANDROID_SERIAL={serial} 不在线。当前设备：{devs or '无'}")
        return [ADB, "-s", serial]
    if not devs:
        sys.exit("无 adb 设备在线（检查 USB 调试开关 / RSA 授权弹窗）")
    if len(devs) > 1:
        sys.exit(f"多设备在线 {devs}：必须 ANDROID_SERIAL=<serial> 显式指定目标（真机纪律）")
    return [ADB]


def shell(*args, timeout=30) -> str:
    """设备 shell，返回 stdout。"""
    r = subprocess.run(adb_prefix() + ["shell"] + list(args), capture_output=True, text=True, timeout=timeout)
    return r.stdout


def resolved_serial() -> str:
    return adb_prefix()[-1] if len(adb_prefix()) == 3 else online_devices()[0]
