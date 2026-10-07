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
MAIN_ACTIVITY = f"{PKG}/.HomeActivity"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DRAFT = os.path.join(ROOT, "draft")
DUMP = "/data/local/tmp/uitap-ui.xml"


def repo(script):
    return os.path.join(ROOT, "scripts", script)


def child_env():
    """子进程（env-run.sh / uitap.py / av2-ws.py）继承已解析的 serial。"""
    return {**os.environ, "ANDROID_SERIAL": resolved_serial()}


def run(cmd, timeout=60, env=None):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, env=env)


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


def wait_focus_activity(name, timeout_s=180, poll=2):
    """等指定 Activity 出现在前台焦点。"""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        out = shell("dumpsys", "window")
        for line in out.splitlines():
            if "mCurrentFocus=" in line and name in line:
                return True
        time.sleep(poll)
    return False


def open_terminal_session(timeout_s=180):
    """主页 → 终端页（2026-10-06 界面：会话卡片即入口，副标题一律含「本地端口」）。
    空列表走「新建会话」对话框（默认名直接「创建」）。返回 TerminalActivity 是否前台。"""
    if tap_text("本地端口", 30):
        return wait_focus_activity("TerminalActivity", timeout_s)
    if tap_text("新建会话", 30) and tap_text("创建", 30):
        return wait_focus_activity("TerminalActivity", timeout_s)
    return False


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


# ---------- CDP 输入通道（ttyd 直连 ws 的输入帧实测不达，走 WebView xterm 为实证路径） ----------

def cdp_forward():
    """主进程 WebView DevTools → 本机 9222。"""
    pid = None
    for line in shell("ps", "-A").splitlines():
        if line.rstrip().endswith(PKG):
            pid = line.split()[1]
            break
    if not pid:
        return False
    r = subprocess.run(adb_prefix() + ["forward", "tcp:9222",
                                       f"localabstract:webview_devtools_remote_{pid}"],
                       capture_output=True, text=True, timeout=15)
    return r.returncode == 0


def cdp_type(text):
    """经 CDP 向 xterm 打字；行尾用字面 \\n（cdp-type.py 约定）表示回车。"""
    r = run(["uv", "run", "--with", "websockets", repo("cdp-type.py"), text], timeout=90)
    return r.returncode == 0


def start_workload(minutes, heartbeat="/root/s1-heartbeat.log", runner="/root/s1-run.sh"):
    """环境内落 runner 脚本 → CDP 启动（前台占用会话 shell，贴近真实任务形态）→ 验证心跳。"""
    script = (
        "#!/bin/bash\n"
        "# S1/S2 心跳负载：文件一拍 + PTY 一拍（防 L1 静默误报、保持 holder rchar 增长）\n"
        f"end=$(( $(date +%s) + {int(minutes) + 10} * 60 ))\n"
        "while [ $(date +%s) -lt $end ]; do\n"
        "  b=$(date +%s)\n"
        f"  echo \"$b\" >> {heartbeat}\n"
        "  echo \"BEAT $b\" > /dev/tty 2>/dev/null\n"
        "  sleep 30\ndone\n"
        f"echo \"S1DONE $(date +%s)\" >> {heartbeat}\n"
    )
    write_cmd = f"cat > {runner} <<'EOS'\n{script}EOS\nchmod +x {runner}; echo WROTE\n"
    out = env_read(write_cmd, timeout=90)
    if "WROTE" not in out:
        return False
    if not cdp_forward():
        print("cdp forward 失败（主进程在吗？）")
        return False
    # 新开的终端页首次 typing 可能不落地：先显式聚焦 xterm（S2/mini 实测）
    fr = run(["uv", "run", "--with", "websockets", repo("cdp-eval.py"),
              "term.focus(); document.activeElement.className"], timeout=30)
    print(f"   xterm focus: {fr.stdout.strip()[:60]}")
    if not cdp_type(f"bash {runner}\\n"):
        print("   cdp-type 失败")
        return False
    deadline = time.time() + 45
    while time.time() < deadline:
        n = env_read(f"wc -l < {heartbeat} 2>/dev/null || echo 0\n", timeout=60).strip()
        try:
            if int(n.splitlines()[-1]) >= 1:
                return True
        except (IndexError, ValueError):
            pass
        time.sleep(5)
    print("   45s 内心跳文件未出现（typing 未达或负载未跑）")
    return False
