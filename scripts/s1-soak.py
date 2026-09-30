#!/usr/bin/env python3
"""S1 锁屏挂机采样（真机周判据：锁屏 N 分钟后任务存活）。

脱线模式：注入心跳负载 → 熄屏 → 提示拔线 → Mac 侧静默计时 → 提示回线 → 取证判定。
采样期设备侧数据由 app 自身时间线记录（battery/心跳/cpu_sample，60s/5min 粒度），
Mac 侧只在脱线前后接触设备（真机纪律：S1 有效性数据必须电池供电）。

前置：s0-provision 已完成（rootfs + 终端层 + main 会话）；手机有锁屏密码不影响
（熄屏/取证均不经 UI）。用法：
  ANDROID_SERIAL=<serial> python3 scripts/s1-soak.py [分钟=35]
输出：draft/s1-soak-verdict.json、draft/s1-timeline.jsonl、draft/s1-heartbeat.log
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scommon as sc

from adbdev import online_devices

HEARTBEAT = "/root/s1-heartbeat.log"
BEAT_INTERVAL = 30  # 秒


def wait_replug(serial, timeout_s=None):
    if timeout_s is None:
        timeout_s = int(os.environ.get("S1_REPLUG_S", "600"))
    print(f"\n>> 计时结束。请重新插上 USB 线（{timeout_s // 60} 分钟内），插好即自动取证…", flush=True)
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        if serial in online_devices():
            time.sleep(3)  # 等 adb 授权握手稳定
            return True
        time.sleep(5)
    return False


def wait_unplug(serial, timeout_s=1800):
    """拔线自动开始计时：serial 从 adb 消失即 t0，全程无需人在 Mac 前。"""
    print(f"\n>> 准备就绪，等待拔线（{timeout_s // 60} 分钟内拔掉 USB 线即自动开始计时）…", flush=True)
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        if serial not in online_devices():
            print(f"   检测到拔线 @ {time.strftime('%H:%M:%S')}", flush=True)
            return time.time()
        time.sleep(2)
    return None


def analyze_heartbeat(lines, t0, t1):
    beats = [int(x) for x in (l.strip() for l in lines) if x.strip().isdigit()]
    win = [b for b in beats if t0 <= b <= t1 + 60]
    gaps = []
    for a, b in zip(win, win[1:]):
        if b - a > BEAT_INTERVAL * 3:  # >90s 视为断档
            gaps.append(b - a)
    return {"beats_total": len(beats), "beats_in_window": len(win),
            "first": win[0] if win else None, "last": win[-1] if win else None,
            "gaps_over_90s": gaps}


def analyze_timeline(timeline_text, t0_ms, t1_ms):
    events = []
    for line in timeline_text.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            events.append(json.loads(line))
        except ValueError:
            pass
    win = [e for e in events if t0_ms <= e.get("ts", 0) <= t1_ms]
    hb = [e for e in win if e.get("type") == "session_heartbeat"]
    hb_gaps = []
    for a, b in zip(hb, hb[1:]):
        if b["ts"] - a["ts"] > 180_000:  # 心跳周期 60s，>3 分钟视为断档
            hb_gaps.append((a["ts"], b["ts"]))
    types = {}
    for e in win:
        types[e.get("type", "?")] = types.get(e.get("type", "?"), 0) + 1
    charging_events = [e for e in win if e.get("type") == "battery"]
    return {
        "event_counts": types,
        "screen_off": any(e.get("type") == "screen_off" for e in win),
        "heartbeat_count": len(hb),
        "heartbeat_gaps_over_180s": len(hb_gaps),
        "charging_observed": any(e.get("charging") for e in charging_events),
        "l1_alerts": types.get("l1_alert", 0),
    }


def main():
    minutes = int(sys.argv[1]) if len(sys.argv) > 1 else 35
    if not sc.package_installed():
        sys.exit("app 未安装（先跑 s0-provision）")
    if not sc.main_session():
        sys.exit("main 会话不在注册表（先跑 s0-provision）")
    sess = sc.main_session()
    port, token = sess["port"], sess["token"]
    print(f"设备：{json.dumps(sc.device_identity(), ensure_ascii=False)}")
    print(f"main 会话 port={port}")

    print("\n== 注入心跳负载（会话内 nohup，%ds 一拍，tee 同时出 PTY 防 L1 误报静默）" % BEAT_INTERVAL)
    sc.forward(port)
    end_epoch = int(time.time()) + minutes * 60 + 600
    # 输出显式指回 /dev/tty：tee 的拍子必须到达 PTY（holder rchar 增长、L1 不误报、
    # ws attach 可见）；不能用 nohup 重定向（会把 tee 一起吞进 nohup.out 或 /dev/null）
    cmd = (f"sh -c 'while [ $(date +%s) -lt {end_epoch} ]; do "
           f"date +%s | tee -a {HEARTBEAT}; sleep {BEAT_INTERVAL}; done' "
           f">/dev/tty 2>&1 &\r")
    r = sc.ws(port, token, "send", cmd, timeout=30)
    if not r or not r.get("sent"):
        sys.exit("负载注入失败（ws 通道，看 av2-ws 输出）")
    time.sleep(5)
    out = sc.env_read(f"wc -l < {HEARTBEAT}; tail -1 {HEARTBEAT}\n").strip().splitlines()
    print(f"环境内确认：{out}")
    if not out or not out[0].strip().isdigit():
        sys.exit("心跳文件未出现")

    print("\n== 熄屏")
    was_awake = sc.screen_awake()
    if was_awake:
        sc.shell("input", "keyevent", "KEYCODE_POWER")  # 26：熄屏
        time.sleep(2)
    print(f"熄屏前 Awake={was_awake}，熄屏后 Awake={sc.screen_awake()}（应 False）")

    serial = sc.resolved_serial()
    print(f"""
>> 布防完成，两种走法自动切换：
>>   A. 拔掉 USB 线 → 立即开始计时（正式 S1，电池供电）；
>>   B. 45 分钟内没拔 → 自动转插线稳定性长跑（数据照采，不作 S1 判据）。
>> 拔线后如果屏幕又亮了，手动按一下电源键熄屏。""")
    t0 = wait_unplug(serial, timeout_s=2700)
    mode = "unplugged-s1"
    if t0 is None:
        mode = "plugged-stability"
        print("   未检测到拔线 → 插线稳定性模式（不作 S1 判据）", flush=True)
        t0 = time.time()
    t0_ms = int(t0 * 1000)

    for i in range(minutes, 0, -1):
        print(f"   剩余 {i} 分钟", flush=True)
        time.sleep(60)
    t1 = time.time()
    t1_ms = int(t1 * 1000)

    if not wait_replug(serial):
        sys.exit("设备未在时限内回线")

    print("\n== 取证")
    bat = sc.battery()
    print(f"回线时电池：{json.dumps(bat, ensure_ascii=False)}")
    timeline = sc.pull_timeline()
    open(os.path.join(sc.DRAFT, "s1-timeline.jsonl"), "w").write(timeline)
    hb_lines = sc.env_read(f"cat {HEARTBEAT}\n").splitlines()
    open(os.path.join(sc.DRAFT, "s1-heartbeat.log"), "w").write("\n".join(hb_lines) + "\n")

    print("\n== 判定")
    hb = analyze_heartbeat(hb_lines, int(t0), int(t1))
    tl = analyze_timeline(timeline, t0_ms, t1_ms)
    print(f"负载心跳：{json.dumps(hb, ensure_ascii=False)}")
    print(f"时间线窗口：{json.dumps(tl, ensure_ascii=False)}")
    expected_beats = int((t1 - t0) / BEAT_INTERVAL)
    strict = mode == "unplugged-s1"
    survived = (hb["beats_in_window"] >= expected_beats * 0.7 and not hb["gaps_over_90s"]
                and tl["heartbeat_count"] >= 1 and tl["heartbeat_gaps_over_180s"] == 0
                and tl["screen_off"])
    passed = survived and (not tl["charging_observed"] if strict else True)
    verdict = {
        "av": f"S1 锁屏 {minutes} 分钟任务存活",
        "s1_mode": mode + ("" if strict else "（充电态，不作 S1 判据）"),
        "date": time.strftime("%Y-%m-%d"),
        "device": sc.device_identity(),
        "battery_at_end": bat,
        "window_s": round(t1 - t0),
        "beats_expected": expected_beats,
        "workload": hb,
        "timeline": tl,
        "charging_during_soak": tl["charging_observed"],
        "survived": survived,
        "passed": passed,
        "evidence": ["draft/s1-heartbeat.log", "draft/s1-timeline.jsonl"],
    }
    out = os.path.join(sc.DRAFT, "s1-soak-verdict.json")
    json.dump(verdict, open(out, "w"), ensure_ascii=False, indent=2)
    label = "S1 判据" if strict else "稳定性长跑（非 S1 判据）"
    print(f"\n{label} {'✓ 任务存活' if survived else '✗ 任务死亡'}，报告：{out}")
    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    main()
