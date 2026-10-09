#!/usr/bin/env python3
"""S2 杀进程接回（真机周判据：系统杀进程后任务/会话的命运与接回）。

两层如实记录（全程可插线）：
  tier1 am kill——宿主 UI 死亡（AMS 只杀可杀进程）：:env 应存活、负载应继续滚、
       会话未重建（port/token 不变）、ws attach 仍能看到负载输出；
  tier2 am force-stop——整包处决（OEM 清剿形态）：负载预期死亡（dtach 全灭），
       记录 EnvService 重启后的会话重建（session_recreated）与新 shell 可用性。

前置：s0-provision 已完成。用法：
  ANDROID_SERIAL=<serial> python3 scripts/s2-reattach.py
输出：draft/s2-reattach-verdict.json、draft/s2-timeline.jsonl、draft/s2-heartbeat.log
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scommon as sc

HEARTBEAT = "/root/s2-heartbeat.log"
BEAT_INTERVAL = 10  # 秒


def hb_lines():
    out = sc.env_read(f"cat {HEARTBEAT} 2>/dev/null | wc -l\n")
    try:
        return int(out.strip().splitlines()[-1])
    except (IndexError, ValueError):
        return -1


def inject(sess, minutes=40):
    # 注入走 CDP→xterm 通道（ttyd 直连 ws 输入帧不达，见 night-log）
    if not sc.start_workload(minutes, heartbeat=HEARTBEAT, runner="/root/s2-run.sh"):
        sys.exit("负载注入失败（CDP 通道）")
    deadline = time.time() + 60
    while time.time() < deadline:
        if hb_lines() >= 2:
            return
        time.sleep(5)
    sys.exit("心跳负载未启动（60s 内无 2 拍）")


def bring_up_terminal():
    """app 冷启后的 UI 路径：主页 → 会话卡片接回（空表走新建对话框）。
    （「安装终端层」手动页随 MainActivity 移除——终端层由新建会话自动安装。）"""
    sc.shell("am", "start", "-n", sc.MAIN_ACTIVITY)
    if not sc.wait_res("home_new_session", 60):
        sys.exit("主页未出现（锁屏？请解锁后重试）")
    if not sc.open_terminal_session():
        sys.exit("打不开终端（卡片与新建对话框都不可用）")
    t0 = time.time()
    while time.time() - t0 < 90:
        if sc.main_session():
            return sc.main_session()
        time.sleep(3)
    return None


def main():
    if not sc.package_installed():
        sys.exit("app 未安装（先跑 s0-provision）")
    pre = sc.main_session()
    if not pre:
        sys.exit("main 会话不在注册表（先跑 s0-provision）")
    print(f"设备：{json.dumps(sc.device_identity(), ensure_ascii=False)}")
    print(f"main 会话（tier1 前）：port={pre['port']}")

    print("\n== 注入负载（%ds 一拍）" % BEAT_INTERVAL)
    inject(pre)
    print(f"负载在滚：{hb_lines()} 拍")

    # ---------- tier1：am kill（宿主 UI 层死亡） ----------
    print("\n== tier1：am kill（前台核对 → HOME 切后台 → am kill）")
    if not sc.foreground_is_drydock():
        sc.shell("am", "start", "-n", sc.MAIN_ACTIVITY)
        sc.wait_res("home_new_session", 30)
    if not sc.foreground_is_drydock():
        sys.exit("前台不是 drydock，拒绝发 HOME（真机纪律：输入限界）")
    sc.shell("input", "keyevent", "KEYCODE_HOME")
    time.sleep(3)
    sc.shell("am", "kill", sc.PKG)
    time.sleep(5)
    procs = sc.processes()
    tier1 = {
        "main_gone": sc.PKG not in procs,
        "env_survived": "env" in procs,
        "procs_after_kill": sorted(procs),
    }
    print(f"杀后进程：{procs}")
    n_before = hb_lines()
    time.sleep(30)
    n_after = hb_lines()
    tier1["task_alive"] = n_after > n_before
    print(f"负载拍数 {n_before} → {n_after}（30s，应增长）")

    print("-- tier1 接回（:env 未死无需重建：验证注册表不变 + ws 输出可见）")
    sc.shell("am", "start", "-n", sc.MAIN_ACTIVITY)
    time.sleep(3)
    post = sc.main_session()
    tier1["reattach_ui"] = bool(post)
    if post:
        tier1["same_session"] = (post["port"] == pre["port"] and post["token"] == pre["token"])
        sc.forward(post["port"])
        r = sc.ws(post["port"], post["token"], "watch", "12", timeout=30)
        tier1["ws_output_seen"] = bool(r and r.get("bytes", 0) > 0)
    tier1["pass"] = (tier1["env_survived"] and tier1["task_alive"]
                     and tier1.get("same_session") and tier1.get("ws_output_seen"))
    print(f"tier1：{json.dumps(tier1, ensure_ascii=False)}")

    # ---------- tier2：am force-stop（整包处决） ----------
    print("\n== tier2：am force-stop（负载预期死亡，验证会话重建）")
    sc.shell("am", "force-stop", sc.PKG)
    time.sleep(8)
    tier2 = {"procs_after_stop": sorted(sc.processes())}
    n_stop = hb_lines()
    time.sleep(20)
    tier2["workload_lost"] = hb_lines() == n_stop
    print(f"负载拍数停在 {n_stop}")

    post2 = bring_up_terminal()
    tier2["session_restarted"] = bool(post2)
    timeline = sc.pull_timeline()
    open(os.path.join(sc.DRAFT, "s2-timeline.jsonl"), "w").write(timeline)
    tier2["session_recreated_event"] = "session_recreated" in timeline
    if post2:
        sc.forward(post2["port"])
        r1 = sc.ws(post2["port"], post2["token"], "send", "echo S2_REATTACH_OK\r", timeout=30) or {}
        r2 = sc.ws(post2["port"], post2["token"], "watch", "6", timeout=20) or {}
        out = r1.get("received_tail", "") + r2.get("received_tail", "")
        tier2["new_shell_ok"] = "S2_REATTACH_OK" in out
    print(f"tier2：{json.dumps(tier2, ensure_ascii=False)}")

    hb = sc.env_read(f"cat {HEARTBEAT}\n")
    open(os.path.join(sc.DRAFT, "s2-heartbeat.log"), "w").write(hb)

    verdict = {
        "av": "S2 系统杀进程后接回",
        "date": time.strftime("%Y-%m-%d"),
        "device": sc.device_identity(),
        "tier1_am_kill": tier1,
        "tier2_force_stop": tier2,
        "passed": tier1["pass"],
        "evidence": ["draft/s2-heartbeat.log", "draft/s2-timeline.jsonl"],
    }
    out = os.path.join(sc.DRAFT, "s2-reattach-verdict.json")
    json.dump(verdict, open(out, "w"), ensure_ascii=False, indent=2)
    print(f"\nS2 {'✓ 通过（tier1）' if verdict['passed'] else '✗ 未通过'}，报告：{out}")
    sys.exit(0 if verdict["passed"] else 1)


if __name__ == "__main__":
    main()
