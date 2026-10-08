#!/usr/bin/env python3
"""S3 发热降频采样（真机周判据：发热人工评估 + cpu_sample 客观佐证）。

脱线模式（协议同 s1-soak）：注入混合负载（gzip/sha256/tar/stat 风暴/npm 循环）
→ 验证负载在转 → 提示拔线+熄屏 → Mac 侧静默计时 → 提示回线 → 取证判定。
发热体感由用户人工评估（脚本只收客观面：负载连续性、cpu_sample、battery、
电池温度前后对比）；S3 有效性数据必须电池供电（真机纪律）。

前置：s0-provision 已完成（rootfs + 终端层 + main 会话）；省电白名单态（D22）。
用法：ANDROID_SERIAL=<serial> python3 scripts/s3-heat.py [分钟=45]
输出：draft/s3-heat-verdict.json、draft/s3-timeline.jsonl、draft/s3-heat.log
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scommon as sc

from adbdev import online_devices

LOG = "/root/s3-heat.log"


def wait_unplug(serial, timeout_s=1800):
    print(f"\n>> 负载已核实。请拔掉 USB 线并熄屏（{timeout_s // 60} 分钟内），拔线即开始计时…", flush=True)
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        if serial not in online_devices():
            print(f"   检测到拔线 @ {time.strftime('%H:%M:%S')}，计时开始", flush=True)
            return time.time()
        time.sleep(2)
    return None


def wait_replug(serial, timeout_s=900):
    print(f"\n>> 计时结束。拿起手机先感受发热（部位/程度，稍后口述记录），"
          f"然后插回 USB（{timeout_s // 60} 分钟内），插好即自动取证…", flush=True)
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        if serial in online_devices():
            time.sleep(3)
            return True
        time.sleep(5)
    return False


def write_runner(minutes):
    """环境内落 /root/s3-run.sh：时限内循环混合负载，每循环记一行 EPOCHREALTIME。"""
    script = (
        "#!/bin/bash\n"
        "# S3 发热负载：gzip/sha256/tar/stat 风暴/npm 混合循环，时限自灭\n"
        f"LOG={LOG}\n"
        f"END=$(( $(date +%s) + {int(minutes) + 10} * 60 ))\n"
        "B=/root/.s3-bench\n"
        "mkdir -p $B/tree\n"
        "if [ ! -f $B/tree/f400 ]; then\n"
        "  for i in $(seq 1 400); do head -c 64K /dev/zero > $B/tree/f$i; done\n"
        "fi\n"
        "c=0\n"
        'echo "S3START $(date +%s) $EPOCHREALTIME" >> $LOG\n'
        "while [ $(date +%s) -lt $END ]; do\n"
        "  c=$((c+1))\n"
        "  cat $B/tree/f* | gzip -1 > /dev/null\n"
        "  for i in 1 2 3 4 5; do cat $B/tree/f* | sha256sum > /dev/null; done\n"
        "  tar czf /tmp/s3.tgz -C $B tree 2>/dev/null; tar tzf /tmp/s3.tgz > /dev/null 2>&1; rm -f /tmp/s3.tgz\n"
        '  for r in 1 2 3; do for f in /usr/bin/*; do stat -c %s "$f" > /dev/null 2>&1; done; done\n'
        "  timeout 90 npm install --prefix $B/npm is-odd lodash --no-audit --no-fund --loglevel=error > /dev/null 2>&1"
        ' || echo "NPMFAIL $c" >> $LOG\n'
        '  echo "CYCLE $c $EPOCHREALTIME" >> $LOG\n'
        "done\n"
        'echo "S3DONE $(date +%s) $EPOCHREALTIME" >> $LOG\n'
    )
    out = sc.env_read(
        f"cat > /root/s3-run.sh <<'EOS'\n{script}EOS\nchmod +x /root/s3-run.sh; echo WROTE\n", timeout=90)
    return "WROTE" in out


def cycle_count():
    out = sc.env_read(f"grep -c '^CYCLE' {LOG} 2>/dev/null || echo 0\n", timeout=60).strip()
    try:
        return int(out.splitlines()[-1])
    except (IndexError, ValueError):
        return 0


def battery_temp():
    out = sc.shell("dumpsys", "battery")
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("temperature:"):
            return int(line.split(":")[1].strip())
    return None


def parse_cycles(text):
    cycles = []
    for line in text.splitlines():
        parts = line.split()
        if len(parts) >= 3 and parts[0] == "CYCLE":
            try:
                cycles.append(float(parts[2]))
            except ValueError:
                pass
    return cycles


def analyze(timeline_text, t0_ms, t1_ms):
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
    types = {}
    for e in win:
        types[e.get("type", "?")] = types.get(e.get("type", "?"), 0) + 1
    batt = [e for e in win if e.get("type") == "battery"]
    cpu = [e for e in win if e.get("type") == "cpu_sample"]
    return {
        "event_counts": types,
        "screen_off": any(e.get("type") == "screen_off" for e in win),
        "charging_observed_in_window": any(e.get("charging") for e in batt),
        "battery_events": len(batt),
        "cpu_samples_in_window": len(cpu),
        "cpu_samples_raw": cpu,  # 频率/温度字段按原始记录全量带出
        "l1_alerts": types.get("l1_alert", 0),
    }


def main():
    minutes = int(sys.argv[1]) if len(sys.argv) > 1 else 45
    if not sc.package_installed():
        sys.exit("app 未安装（先跑 s0-provision）")
    if not sc.main_session():
        sys.exit("main 会话不在注册表（先跑 s0-provision）")

    batt = sc.battery()
    temp0 = battery_temp()
    print(f"设备：{json.dumps(sc.device_identity(), ensure_ascii=False)}")
    print(f"电池：{json.dumps(batt)}  温度：{temp0}（0.1°C 单位）")
    if int(batt.get("level", 0)) < 30:
        sys.exit("电量 <30%，先充电再采样")

    wl = sc.shell("dumpsys", "deviceidle", "whitelist")
    if "drydock" not in wl:
        sys.exit("省电白名单无 drydock（D22 前置，需用户在手机上设置「无限制」）")

    print(f"\n== 落负载脚本（时限 {minutes + 10} 分钟）")
    sc.env_read("pkill -f s3-run.sh 2>/dev/null; rm -f /root/s3-heat.log; echo CLEAN\n", timeout=60)
    if not write_runner(minutes):
        sys.exit("负载脚本写入失败（env-run 通道）")

    print("\n== 终端会话（已在终端页则复用）与 CDP 注入启动")
    if sc.wait_focus_activity("TerminalActivity", 5):
        print("   TerminalActivity 已在前台，复用")
    elif not sc.open_terminal_session():
        sys.exit("终端会话打开失败")
    if not sc.cdp_forward():
        sys.exit("CDP forward 失败")
    sc.run(["uv", "run", "--with", "websockets", sc.repo("cdp-eval.py"),
            "term.focus(); document.activeElement.className"], timeout=30)
    if not sc.cdp_type("\\n"):  # 清掉误触可能留下的半行
        sys.exit("CDP 注入失败（清行）")
    if not sc.cdp_type("bash /root/s3-run.sh\\n"):
        sys.exit("CDP 注入失败")

    print("== 验证负载在转（轮询 150s：首圈含建树+npm 首拉，可能 ~90s）")
    n1 = cycle_count()
    grew = False
    deadline = time.time() + 150
    while time.time() < deadline:
        time.sleep(15)
        n2 = cycle_count()
        if n2 > n1:
            grew = True
            break
    print(f"   CYCLE 计数：{n1} → {n2}")
    if not grew:
        sys.exit("负载未运转（计数不增长），中止布防")

    serial = sc.resolved_serial()
    t0 = wait_unplug(serial)
    if t0 is None:
        sys.exit("等待拔线超时")
    print(f">> 电池供电采样 {minutes} 分钟（预计 {time.strftime('%H:%M:%S', time.localtime(t0 + minutes * 60))} 结束）", flush=True)
    time.sleep(minutes * 60)
    if not wait_replug(serial):
        sys.exit("等待回线超时（可手动重跑取证段）")
    t1 = time.time()

    print("\n== 取证")
    heat_log = sc.env_read(f"cat {LOG} 2>/dev/null\n", timeout=120)
    open(os.path.join(sc.DRAFT, "s3-heat.log"), "w").write(heat_log)
    timeline = sc.pull_timeline()
    open(os.path.join(sc.DRAFT, "s3-timeline.jsonl"), "w").write(timeline)
    temp1 = battery_temp()

    cycles = parse_cycles(heat_log)
    win_cycles = [c for c in cycles if t0 <= c <= t1 + 60]
    gaps = [b - a for a, b in zip(win_cycles, win_cycles[1:]) if b - a > 300]
    tl = analyze(timeline, int(t0 * 1000), int(t1 * 1000))
    verdict = {
        "name": "s3_heat",
        "minutes": minutes,
        "unplug_ts": t0,
        "replug_ts": t1,
        "cycles_total": len(cycles),
        "cycles_in_window": len(win_cycles),
        "cycle_gaps_over_300s": gaps,
        "temp_tenths_c": {"before": temp0, "after": temp1},
        "timeline": tl,
        "user_assessment": "",  # 采样后用户口述补录
    }
    path = os.path.join(sc.DRAFT, "s3-heat-verdict.json")
    with open(path, "w") as f:
        json.dump(verdict, f, ensure_ascii=False, indent=2)
    print(json.dumps({k: v for k, v in verdict.items() if k != "timeline"},
                     ensure_ascii=False, indent=2))
    print(f"\nverdict：{path}")
    print("提醒：请口述发热评估（热点部位/程度/是否影响持握），补录进 user_assessment。")


if __name__ == "__main__":
    main()
