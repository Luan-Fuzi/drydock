#!/usr/bin/env python3
"""真机判据剧本共用件（s0-provision / s1-soak / s2-reattach）。

全部操作遵守 AGENTS.md「真机纪律」：只读默认；写仅限安装自家 APK、
pm grant 自家权限、push /data/local/tmp、run-as 自家私有目录、
UI 驱动（uiautomator dump + input tap）与电源/HOME 键（采样需要）。
"""
import json
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from adbdev import adb_prefix, online_devices, resolved_serial

PKG = "dev.drydock.prototype"
MAIN_ACTIVITY = f"{PKG}/.MainActivity"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DRAFT = os.path.join(ROOT, "draft")
DUMP = "/data/local/tmp/uitap-ui.xml"


def repo(script):
    return os.path.join(ROOT, "scripts", script)


def child_env():
    """子进程（env-run.sh / uitap.py / av2-ws.py）继承已解析的 serial。"""
    return {**os.environ, "ANDROID_SERIAL": resolved_serial()}


def run(cmd, timeout=60):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def shell(*args, timeout=30):
    r = subprocess.run(adb_prefix() + ["shell"] + list(args), capture_output=True, text=True, timeout=timeout)
    return r.stdout


# ---------- 设备与包状态（只读） ----------

def device_identity():
    def prop(k):
        return shell("getprop", k).strip()
    return {
        "serial": resolved_serial(),
        "model": prop("ro.product.model"),
        "device": prop("ro.product.device"),
        "android": prop("ro.build.version.release"),
        "incremental": prop("ro.build.version.incremental"),
        "hyperos": prop("ro.mi.os.version.name") or prop("ro.miui.ui.version.name"),
    }


def package_installed():
    return shell("pm", "path", PKG).strip().startswith("package:")


def processes():
    """{进程名尾段: pid}，只看自家包。"""
    out = {}
    for line in shell("ps", "-A").splitlines():
        if PKG in line:
            parts = line.split()
            if len(parts) >= 2 and parts[0] != "USER":
                name = parts[-1].split(":")[-1]
                out[name] = parts[1]
    return out


def battery():
    out = shell("dumpsys", "battery")
    d = {}
    for line in out.splitlines():
        if ":" in line:
            k, _, v = line.strip().partition(":")
            d[k.strip()] = v.strip()
    return {
        "level": d.get("level"),
        "temperature": d.get("temperature"),
        "status": d.get("status"),
        "usb_powered": d.get("USB powered"),
        "ac_powered": d.get("AC powered"),
    }


def screen_awake():
    return "mWakefulness=Awake" in shell("dumpsys", "power")


def foreground_is_drydock():
    out = shell("dumpsys", "window")
    return PKG in (out.split("mCurrentFocus=")[-1].splitlines()[0] if "mCurrentFocus=" in out else "")


# ---------- app 私有目录（run-as，只读） ----------

def run_as_cat(path):
    return shell("run-as", PKG, "cat", path)


def registry_sessions():
    txt = run_as_cat("files/terminal-sessions.json")
    try:
        v = json.loads(txt)
        return v if isinstance(v, list) else []
    except (json.JSONDecodeError, ValueError):
        return []


def main_session():
    return next((s for s in registry_sessions() if s.get("name") == "main"), None)


def pull_timeline():
    """Timeline.readAll 同口径：.old + 当前。"""
    parts = []
    for p in ("files/timeline.jsonl.old", "files/timeline.jsonl"):
        t = run_as_cat(p)
        if t and "No such file" not in t and "not debuggable" not in t:
            parts.append(t)
    return "\n".join(parts)


# ---------- UI 驱动（uiautomator dump 只读 + input tap 白名单） ----------

def ui_dump():
    shell("uiautomator", "dump", DUMP, timeout=60)
    return shell("cat", DUMP)


def wait_text(text, timeout_s, poll=3):
    """等 UI 出现含 text 的节点；熄屏/锁屏 dump 为空时按未出现处理。"""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        xml = ui_dump()
        if xml and text in xml:
            return True
        time.sleep(poll)
    return False


def swipe_up():
    """页内向下滚动一屏（Compose 列表按钮常被挤到可视区外，uiautomator 只见可视节点）。"""
    shell("input", "swipe", "540", "1600", "540", "500", "300")


def tap_text(text, timeout_s=180):
    """等出现并点击；首屏找不到先向下滚动两轮再找（先 dump 核对，真机纪律输入限界）。"""
    for scroll_round in range(3):
        if wait_text(text, timeout_s if scroll_round == 0 else 15):
            for _ in range(3):  # dump 抖动重试
                r = run([sys.executable, repo("uitap.py"), text], timeout=120)
                if r.returncode == 0:
                    return True
                time.sleep(3)
        swipe_up()
    return False


# ---------- 会话注入与取证 ----------

def forward(port):
    subprocess.run(adb_prefix() + ["forward", f"tcp:{port}", f"tcp:{port}"],
                   capture_output=True, text=True, timeout=15)


def ws(port, token, mode, arg, timeout=30):
    """调 av2-ws.py：mode=send|watch。返回解析后的 JSON 或 None。"""
    cmd = ["uv", "run", "--with", "websockets", repo("av2-ws.py"),
           str(port), token, mode, arg]
    r = run(cmd, timeout=timeout)
    if r.returncode != 0:
        return None
    try:
        return json.loads(r.stdout)
    except (json.JSONDecodeError, ValueError):
        return None


def env_read(script_body, timeout=120):
    """环境内只读取证：脚本体在 app 私有 rootfs 里以 bash 执行（经 env-run.sh）。"""
    tmp = "/tmp/drydock-probe-read.sh"
    with open(tmp, "w") as f:
        f.write(script_body)
    r = run(["bash", repo("env-run.sh"), tmp], timeout=timeout, env=child_env())
    return r.stdout
