#!/usr/bin/env python3
"""R4 备份恢复（导入）判据剧本（2026-10-08）：AVD 全 UI 路径驱动 + 命令行断言。

  j1  导出 → 改环境（增/删/改/断链）→ 恢复（SAF 选文件 + 双重确认 + 同名全部覆盖）
      → 逐文件 sha256 diff 回到导出时点；合并语义：导出后新增的文件保留
  j2  篡改 tar（../ 逃逸条目 / 绝对路径条目）→ 整体拒绝、环境零改动（快照对比）
  j3  pm clear 模拟全新未部署环境 → 恢复入口自动部署 → 恢复成功（marker 回到导出时点）

用法：ANDROID_SERIAL=emulator-5554 python3 scripts/r4-restore.py [j1 j2 j3]
输出：draft/r4-restore-verdict.json（每阶段后落盘，崩溃安全）；截图存 draft/。
只动模拟器（AVD 可写 /sdcard、pm clear）；真机纪律见 AGENTS.md。
"""
import gzip
import io
import json
import os
import re
import subprocess
import sys
import tarfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scommon as sc
from adbdev import adb_prefix

PKG = sc.PKG
HOME = sc.MAIN_ACTIVITY
DRAFT = sc.DRAFT
VERDICT = os.path.join(DRAFT, "r4-restore-verdict.json")
REMOTE_DIR = "/sdcard/Download/Drydock"
REMOTE_TAR = f"{REMOTE_DIR}/drydock-env-export.tar.gz"

report = {"started": time.strftime("%Y-%m-%d %H:%M:%S"), "device": sc.device_identity(), "stages": {}}


def save():
    report["finished"] = time.strftime("%Y-%m-%d %H:%M:%S")
    json.dump(report, open(VERDICT, "w"), ensure_ascii=False, indent=2)


def stage(name):
    def deco(fn):
        def wrapped():
            print(f"\n===== {name} =====", flush=True)
            r = {"name": name}
            report["stages"][name] = r
            try:
                fn(r)
            except Exception as e:
                import traceback
                r["error"] = f"{type(e).__name__}: {e}"
                r["trace"] = traceback.format_exc()[-600:]
            r["done"] = time.strftime("%H:%M:%S")
            save()
            print(json.dumps(r, ensure_ascii=False, indent=2), flush=True)
        return wrapped
    return deco


def shell_q(cmd, timeout=30):
    """含引号/括号路径的整串 shell 命令（adb shell 多参数会被设备端 shell 重切）。"""
    return subprocess.run(adb_prefix() + ["shell", cmd], capture_output=True, text=True,
                          timeout=timeout).stdout


def screencap(name):
    path = os.path.join(DRAFT, name)
    p = subprocess.run(adb_prefix() + ["exec-out", "screencap", "-p"], capture_output=True, timeout=60)
    open(path, "wb").write(p.stdout)
    return path


def tap_node(pred_key, value, timeout_s=15):
    """按 text= 或 content-desc= 找节点并点击（uitap 只认 text）。"""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        xml = sc.ui_dump()
        pat = re.compile(
            r'(?:text|content-desc)="' + re.escape(value) + r'"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        m = pat.search(xml)
        if m:
            x = (int(m.group(1)) + int(m.group(3))) // 2
            y = (int(m.group(2)) + int(m.group(4))) // 2
            sc.shell("input", "tap", str(x), str(y))
            return True
        time.sleep(2)
    return False


def focus():
    out = sc.shell("dumpsys", "window")
    for line in out.splitlines():
        if "mCurrentFocus=" in line:
            return line.strip()
    return out[:120]


def nav_settings_backup():
    """主页 → 底部导航设置 → 备份与导出二级页。"""
    # 上一步可能停在系统文件选择器（独立 task，am start -S 盖不掉）：BACK 可能先
    # 收侧栏/软键盘，循环撤到选择器不在前台为止
    for _ in range(3):
        if "documentsui" not in focus():
            break
        sc.shell("input", "keyevent", "KEYCODE_BACK")
        time.sleep(1.5)
    sc.shell("am", "start", "-S", "-n", HOME)
    assert sc.wait_text("新建会话", 60), "HomeActivity 未出现"
    m = re.search(r"(\d+)x(\d+)", sc.shell("wm", "size"))
    h = int(m.group(2))
    xml = sc.ui_dump()
    best = None
    for mm in re.finditer(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if mm.group(1) == "设置":
            y = (int(mm.group(3)) + int(mm.group(5))) // 2
            if y > h * 0.85 and (best is None or y > best[1]):
                best = ((int(mm.group(2)) + int(mm.group(4))) // 2, y)
    assert best, "底部导航无「设置」"
    sc.shell("input", "tap", str(best[0]), str(best[1]))
    assert sc.wait_text("备份与导出", 20), "设置主页面未出现"
    assert sc.tap_text("备份与导出", 20), "点不到备份入口"
    assert sc.wait_text("导出工作区与配置", 20), "备份页未出现"


def pick_file(filename):
    """SAF（ACTION_OPEN_DOCUMENT）驱动选文件。DocumentsUI 会记住本 app 上次浏览
    位置：上次成功选过 Download/Drydock 里的文件时直接落在该目录（快路径）；否则
    走侧栏 Show roots → Downloads → Drydock 目录（慢路径，整体可重试两轮）。
    注意自家 provider 根「Drydock workspace」是「Drydock」的 contains 命中，
    必须等 Downloads 目录列表出现后再精确点「Drydock」目录行。"""
    assert sc.tap_text("从 tar.gz 恢复…", 20), "点不到「从 tar.gz 恢复…」"
    assert sc.wait_text("Show roots", 20), "系统文件选择器未打开"
    if sc.wait_text(filename, 6):
        assert sc.tap_text(filename, 15), f"点不到文件 {filename}"
        time.sleep(2)
        return
    for attempt in range(2):
        ok = (tap_node("desc", "Show roots")
              and sc.wait_text("Drydock workspace", 10)
              and sc.tap_text("Downloads", 15)
              and sc.wait_text("Files in Downloads", 20)
              and sc.tap_text("Drydock", 15)
              and sc.wait_text(filename, 15)
              and sc.tap_text(filename, 20))
        if ok:
            time.sleep(2)
            return
        visible = [t[:40] for t in re.findall(r'text="([^"]+)"', sc.ui_dump())][:12]
        print(f"  picker 慢路径第 {attempt + 1} 轮失败，可见文本：{visible}", flush=True)
    raise AssertionError(f"两轮都选不到 {filename}")


def confirm_restore():
    """确认①（说明 + 密钥提示）→ 确认②（危险操作）。"""
    xml = sc.ui_dump()
    assert "已选择" in xml and "env.sh" in xml, "确认①缺少说明/密钥提示"
    assert sc.tap_text("继续", 10), "点不到「继续」"
    time.sleep(1)
    assert sc.wait_text("确认开始恢复", 10), "确认②未出现"
    assert sc.tap_text("我明白，开始恢复", 10), "点不到「我明白，开始恢复」"


def wait_result(timeout_s=600):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        xml = sc.ui_dump()
        # 页面上可能并存多条 ✓/✗ 消息（上一次导出的成功提示在前），逐条找关键字
        for m in re.finditer(r'text="([✓✗][^"]*)"', xml):
            if any(k in m.group(1) for k in ("恢复完成", "已整体拒绝", "恢复失败", "部署失败")):
                return m.group(1)
        time.sleep(3)
    return None


def env_snapshot():
    """白名单口径（/root + /etc 片段）全文件 sha + 符号链接表，作前后对比。"""
    return sc.env_read(
        "cd / && find root etc/profile.d etc/apt/sources.list.d -type f "
        "-exec sha256sum {} + 2>/dev/null | sort; "
        "find root -type l -exec readlink {} \\; 2>/dev/null | sort\n",
        timeout=180)


# ---------- j1 导出 → 改环境 → 恢复 → diff ----------

@stage("j1_export_modify_restore")
def j1(r):
    # 清旧导出（MediaStore 给重名加 (1)，picker 里歧义）——路径含括号必须整串引用
    shell_q(f"rm -f '{REMOTE_DIR}/drydock-env-export.tar.gz' "
            f"'{REMOTE_DIR}/drydock-env-export.tar.gz (1)' '{REMOTE_DIR}/drydock-env-export.tar.gz (2)'")
    out = sc.env_read(
        "mkdir -p /root/r4-deep/a/b/c; printf 'marker-a\\n' > /root/r4-keep.txt; "
        "printf 'deep-content\\n' > /root/r4-deep/a/b/c/deepfile.txt; "
        "ln -sf /usr/bin/tar /root/r4-link; "
        "printf '#!/bin/sh\\necho r4-exec-ok\\n' > /root/r4-exec.sh; "
        "chmod 755 /root/r4-keep.txt /root/r4-exec.sh; "
        # 幂等卫生（重跑残留会毒进下一次导出）：上一轮的导出后新增文件与篡改行先清掉
        "rm -f /root/r4-added.txt; "
        "sed -i '/^# tampered$/d' /etc/profile.d/drydock-env.sh 2>/dev/null; echo PLANTED\n", timeout=120)
    assert "PLANTED" in out, f"标记写入失败：{out[-200:]}"

    nav_settings_backup()
    assert sc.tap_text("导出工作区与配置", 20), "点不到导出"
    assert sc.wait_text("✓ 已导出", 300), "导出未完成"
    r["exported"] = True

    pull = subprocess.run(adb_prefix() + ["pull", REMOTE_TAR, "/tmp/r4-j1-export.tar.gz"],
                          capture_output=True, text=True, timeout=120)
    assert pull.returncode == 0, f"拉不回导出包：{pull.stderr[:150]}"
    ref_dir = "/tmp/r4-j1-ref"
    subprocess.run(["rm", "-rf", ref_dir], check=True)
    os.makedirs(ref_dir)
    subprocess.run(["tar", "-xzf", "/tmp/r4-j1-export.tar.gz", "-C", ref_dir], check=True, timeout=120)
    ref = {}
    ref_links = {}
    for root, _, files in os.walk(ref_dir):
        for f in files:
            p = os.path.join(root, f)
            rel = os.path.relpath(p, ref_dir)
            if os.path.islink(p):
                # 符号链接不比内容（宿主与环境的 /usr/bin/tar 不是同一个二进制），
                # 目标串单独比（见 LINK 断言与 links 对比）
                ref_links[rel] = os.readlink(p)
                continue
            h = subprocess.run(["shasum", "-a", "256", p], capture_output=True, text=True).stdout.split()[0]
            ref[rel] = h
    tv = subprocess.run(["tar", "-tvf", "/tmp/r4-j1-export.tar.gz"], capture_output=True, text=True).stdout
    r["ref_files"] = len(ref)
    r["ref_has_symlink_entry"] = "root/r4-link ->" in tv
    r["ref_has_execbit_entry"] = "-rwx" in tv
    assert "root/r4-keep.txt" in tv and "root/r4-deep/a/b/c/deepfile.txt" in tv, "导出包缺标记文件"

    # 改环境：改内容 / 删文件 / 删链接 / 新增 / 篡改 /etc 片段
    out = sc.env_read(
        "printf 'marker-b\\n' > /root/r4-keep.txt; rm -f /root/r4-deep/a/b/c/deepfile.txt /root/r4-link; "
        "printf 'added-after-export\\n' > /root/r4-added.txt; "
        "printf '\\n# tampered\\n' >> /etc/profile.d/drydock-env.sh; echo MODIFIED\n", timeout=120)
    assert "MODIFIED" in out, f"改环境失败：{out[-200:]}"

    # 恢复（产品路径全走）
    pick_file("drydock-env-export.tar.gz")
    r["screenshot_confirm1"] = screencap("r4-confirm1.png")
    confirm_restore()
    assert sc.wait_text("同名文件", 240), "同名询问未出现"
    xml = sc.ui_dump()
    m = re.search(r'text="(root/[^"]+)"', xml)
    r["conflict_first"] = m.group(1) if m else None
    r["screenshot_conflict"] = screencap("r4-conflict.png")
    assert sc.tap_text("全部覆盖", 10), "点不到「全部覆盖」"
    result = wait_result()
    r["result"] = result
    assert result and "恢复完成" in result and "失败 0" in result, f"恢复未完成：{result}"
    r["counts_ok"] = bool(re.search(r"导入 \d+ · 跳过 \d+ · 失败 0", result))
    r["screenshot_result"] = screencap("r4-j1-result.png")

    # 断言：内容回到导出时点 + 合并语义（导出后新增保留）
    out = sc.env_read(
        "echo KEEP=$(cat /root/r4-keep.txt); "
        "echo DEEP=$(cat /root/r4-deep/a/b/c/deepfile.txt 2>&1); "
        "echo ADDED=$(cat /root/r4-added.txt 2>&1); "
        "echo LINK=$(readlink /root/r4-link 2>&1); "
        "echo EXEC=$(stat -c %A /root/r4-keep.txt); "
        "echo ETC_TAIL=$(tail -1 /etc/profile.d/drydock-env.sh)\n", timeout=120)
    r["env_probe"] = {l.split("=", 1)[0]: l.split("=", 1)[1] for l in out.splitlines() if "=" in l}
    r["keep_restored"] = "marker-a" in out
    r["deep_restored"] = "deep-content" in out
    r["added_kept"] = "added-after-export" in out
    r["link_restored"] = "LINK=/usr/bin/tar" in out
    r["exec_bit"] = "EXEC=-rwx" in out
    r["etc_fragment_restored"] = "# tampered" not in out
    assert r["keep_restored"] and r["deep_restored"] and r["added_kept"] and r["link_restored"], out

    # 全量 diff：参考清单 vs 恢复后环境（文件比 sha；符号链接比目标串）
    paths = sorted(ref.keys())
    after = sc.env_read("cd / && sha256sum " + " ".join(f"'{p}'" for p in paths) + "\n", timeout=180)
    got = {}
    for line in after.splitlines():
        parts = line.split(None, 1)
        if len(parts) == 2 and len(parts[0]) == 64:
            got[parts[1].strip()] = parts[0]
    mismatch = [p for p in paths if got.get(p) != ref[p]]
    if ref_links:
        lout = sc.env_read("cd / && readlink " + " ".join(f"'{p}'" for p in ref_links) + "\n", timeout=120)
        lgot = dict(zip(ref_links, [l.strip() for l in lout.splitlines()]))
        mismatch += [f"{p} -> {lgot.get(p)} != {t}" for p, t in ref_links.items() if lgot.get(p) != t]
    r["diff_compared"] = len(paths) + len(ref_links)
    r["diff_mismatch"] = mismatch
    assert not mismatch, f"diff 不一致：{mismatch[:5]}"
    r["pass"] = all([r["exported"], r["counts_ok"], r["keep_restored"], r["deep_restored"],
                     r["added_kept"], r["link_restored"], r["exec_bit"], r["etc_fragment_restored"],
                     not mismatch, r["ref_has_symlink_entry"], r["ref_has_execbit_entry"]])


# ---------- j2 篡改 tar 整体拒绝 ----------

def build_evil_tar(path, arcs):
    """构造含越界条目的 tar.gz（tarfile 允许任意 arcname，重放攻击面）。"""
    payload = io.BytesIO()
    with tarfile.open(fileobj=payload, mode="w") as tf:
        data = b"evil-payload\n"
        for arc in arcs:
            ti = tarfile.TarInfo(arc)
            ti.size = len(data)
            tf.addfile(ti, io.BytesIO(data))
    with gzip.open(path, "wb") as gz:
        gz.write(payload.getvalue())


@stage("j2_malicious_tar_rejected")
def j2(r):
    # 两个毒包：../ 逃逸 与 绝对路径。各夹带一个合法 ./root 条目——整体拒绝语义下
    # 合法条目也不得落盘
    build_evil_tar("/tmp/r4-evil-dotdot.tar.gz",
                   ["./root/good.txt", "./root/../../evil-dotdot.txt"])
    build_evil_tar("/tmp/r4-evil-abs.tar.gz",
                   ["./root/good.txt", "/data/local/tmp/evil-abs.txt"])
    sc.shell("mkdir", "-p", REMOTE_DIR)
    for name in ("r4-evil-dotdot.tar.gz", "r4-evil-abs.tar.gz"):
        p = subprocess.run(adb_prefix() + ["push", f"/tmp/{name}", f"{REMOTE_DIR}/{name}"],
                           capture_output=True, text=True, timeout=60)
        assert p.returncode == 0, f"push {name} 失败：{p.stderr[:120]}"
        # adb push 不进 MediaStore 索引，DocumentsUI（经 MediaProvider）看不见；补扫
        sc.shell("am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                 "-d", f"file://{REMOTE_DIR}/{name}")

    before = env_snapshot()

    for i, name in enumerate(("r4-evil-dotdot.tar.gz", "r4-evil-abs.tar.gz")):
        nav_settings_backup()
        pick_file(name)
        confirm_restore()
        result = wait_result(120)
        r[f"reject_{i}"] = result
        r[f"screenshot_reject_{i}"] = screencap(f"r4-j2-reject-{i}.png")
        assert result and "已整体拒绝" in result and "环境未改动" in result, f"{name} 未被整体拒绝：{result}"

    after = env_snapshot()
    r["env_unchanged"] = before == after
    assert r["env_unchanged"], "拒绝后环境有变化"
    out = sc.env_read(
        "ls /root/good.txt 2>&1 | head -1; ls /root/evil-dotdot.txt 2>&1 | head -1; "
        "ls /data/local/tmp/evil-abs.txt 2>&1 | head -1; "
        "cat /root/r4-keep.txt 2>/dev/null\n", timeout=120)
    r["no_member_written"] = out.count("No such file") >= 3
    r["env_marker_intact"] = "marker-a" in out
    # 收尾：毒包撤出下载目录，免得后续剧本误选
    shell_q(f"rm -f '{REMOTE_DIR}/r4-evil-dotdot.tar.gz' '{REMOTE_DIR}/r4-evil-abs.tar.gz'")
    r["pass"] = all([r["reject_0"], r["reject_1"], r["env_unchanged"],
                     r["no_member_written"], r["env_marker_intact"]])


# ---------- j3 未部署环境自动部署后恢复 ----------

@stage("j3_fresh_deploy_and_restore")
def j3(r):
    # 模拟换机/重装：pm clear 抹掉 filesDir（rootfs 随之消失）。AVD 专用操作，
    # 真机纪律禁 pm clear——本剧本只在模拟器上跑。
    sc.shell("pm", "clear", PKG)
    deployed = sc.shell("run-as", PKG, "ls", "files/ubuntu-rootfs/bin/bash").strip()
    r["rootfs_gone"] = "No such file" in deployed or "not found" in deployed.lower() or not deployed
    assert r["rootfs_gone"], f"pm clear 后 rootfs 仍在：{deployed[:80]}"

    nav_settings_backup()
    pick_file("drydock-env-export.tar.gz")
    confirm_restore()
    # 期望：先部署（BusyBar）再灌入；全新环境无同名冲突，不该出现询问框
    xml = sc.ui_dump()
    r["conflict_dialog_absent"] = "同名文件" not in xml
    result = wait_result(600)
    r["result"] = result
    r["screenshot_result"] = screencap("r4-j3-result.png")
    assert result and "恢复完成" in result and "失败 0" in result, f"自动部署+恢复未完成：{result}"
    assert r["conflict_dialog_absent"], "全新环境不该有同名询问"

    out = sc.env_read(
        "echo KEEP=$(cat /root/r4-keep.txt); "
        "echo DEEP=$(cat /root/r4-deep/a/b/c/deepfile.txt 2>&1); "
        "echo LINK=$(readlink /root/r4-link 2>&1); "
        "echo ADDED=$(ls /root/r4-added.txt 2>&1 | head -1); "
        "echo L2S=$(ls /root/.l2s 2>&1 | head -1); "
        "echo MANIFEST=$(head -1 /.drydock-manifest 2>/dev/null)\n", timeout=120)
    probe = {}
    for line in out.splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            probe[k] = v
    r["env_probe"] = probe
    r["keep_restored"] = probe.get("KEEP") == "marker-a"
    r["deep_restored"] = probe.get("DEEP") == "deep-content"
    r["link_restored"] = probe.get("LINK") == "/usr/bin/tar"
    r["postexport_file_absent"] = "No such file" in probe.get("ADDED", "")
    r["l2s_absent"] = "No such file" in probe.get("L2S", "")  # D21：恢复不引入 .l2s
    r["manifest"] = "version=24.04" in probe.get("MANIFEST", "")
    assert r["keep_restored"] and r["deep_restored"] and r["link_restored"] and r["manifest"], out
    assert r["postexport_file_absent"], "导出后新增的文件不该随恢复出现"
    r["pass"] = all([r["keep_restored"], r["deep_restored"], r["link_restored"], r["manifest"],
                     r["conflict_dialog_absent"], r["rootfs_gone"], r["postexport_file_absent"]])


def main():
    wanted = sys.argv[1:] or ["j1", "j2", "j3"]
    runners = {"j1": j1, "j2": j2, "j3": j3}
    print(f"设备：{json.dumps(report['device'], ensure_ascii=False)}", flush=True)
    for k in wanted:
        runners[k]()
    ok = all(s.get("pass") for s in report["stages"].values())
    report["all_pass"] = ok
    save()
    print(f"\n====> 总判据 all_pass={ok}，verdict: {VERDICT}", flush=True)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
