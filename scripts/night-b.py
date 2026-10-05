#!/usr/bin/env python3
"""B 类测试清账（2026-10-04 夜批）：已实现但未在 AVD 验过的分支，逐项脚本化。

覆盖：
  t1  HomeActivity 主链路（打开终端 → TerminalActivity；新建会话 → 第二会话）
  t2  文件页 ACTION_VIEW（logcat START 行判据）+ DocumentsProvider query/read
  t3  上下文窗口字段端到端（drydock_endpoint 第四段 → .drydock-endpoint context 记录；opencode
      limit 仅在有 context 出处时写；D30 起 provider 段用真名/envVar 前缀）
  t4  浅色主题（prefs 断言 + 截图留证；观感留白天视觉批次）
  t5  pi 配方全链 + npm 假源→npmjs 回退（真代码路径；aptTools/符号链接/models.json）

用法：ANDROID_SERIAL=emulator-5554 python3 scripts/night-b.py [t1 t2 ...]（缺省全部）
输出：draft/night-b-verdict.json（每阶段后落盘，崩溃安全）。
只动模拟器；真机纪律：run-as 自家目录、/data/local/tmp、logcat、screencap，全为允许面。
"""
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scommon as sc
from adbdev import adb_prefix

PKG = sc.PKG
HOME = f"{PKG}/.HomeActivity"
VERDICT = os.path.join(sc.DRAFT, "night-b-verdict.json")

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
                r["error"] = f"{type(e).__name__}: {e}"
            r["done"] = time.strftime("%H:%M:%S")
            save()
            print(json.dumps(r, ensure_ascii=False, indent=2), flush=True)
        return wrapped
    return deco


def focus():
    out = sc.shell("dumpsys", "window")
    for line in out.splitlines():
        if "mCurrentFocus=" in line:
            return line.strip()
    return out[:120]


def wait_focus(who, timeout_s):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        f = focus()
        if who in f:
            return f
        time.sleep(2)
    return None


def screencap(name):
    path = os.path.join(sc.DRAFT, name)
    r = subprocess.run(adb_prefix() + ["exec-out", "screencap", "-p"], capture_output=True, timeout=60)
    open(path, "wb").write(r.stdout)
    return path


def logcat(tag, extra=()):
    return sc.shell("logcat", "-d", "-s", tag, *extra, timeout=30)


def logcat_clear():
    sc.shell("logcat", "-c")


def nav_tap(label):
    """点底部导航：label 会撞页面正文同词（如教育文案含「打开终端」），只认
    屏幕底部 15% 区域内含该词的节点（NavigationBar 固定在底部）。"""
    m = re.search(r"(\d+)x(\d+)", sc.shell("wm", "size"))
    h = int(m.group(2)) if m else 2400
    xml = sc.ui_dump()
    best = None
    for mm in re.finditer(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if label in mm.group(1):
            y = (int(mm.group(3)) + int(mm.group(5))) // 2
            if y > h * 0.85 and (best is None or y > best[1]):
                best = (((int(mm.group(2)) + int(mm.group(4))) // 2), y)
    if not best:
        return False
    sc.shell("input", "tap", str(best[0]), str(best[1]))
    return True


def prefs_xml():
    return sc.run_as_cat("shared_prefs/drydock.xml")


# ---------- t1 主链路 ----------

@stage("t1_main_flow")
def t1(r):
    sc.shell("input", "keyevent", "KEYCODE_WAKEUP")
    sc.shell("am", "start", "-S", "-n", HOME)
    if not sc.wait_text("终端会话", 60):
        raise RuntimeError("HomeActivity 未出现「终端会话」")
    r["home_shown"] = True

    if not sc.tap_text("打开终端（agent 在这里）", 30):
        raise RuntimeError("找不到「打开终端」按钮（教育文案含同词，需全名匹配）")
    f = wait_focus("TerminalActivity", 120)
    r["open_terminal_focus"] = f or "TIMEOUT"
    assert f, "打开终端后 TerminalActivity 未前台"

    sessions = {s["name"] for s in sc.registry_sessions()}
    r["sessions_after_open"] = sorted(sessions)
    assert "main" in sessions, "registry 无 main"

    # 已知缺陷（本次夜批顺带修）：回主页后会话列表不刷新 → 用 -S 重建取 fresh 状态
    sc.shell("am", "start", "-S", "-n", HOME)
    if not sc.wait_text("新建会话", 60):
        r["new_session_button"] = "未出现（会话列表未刷新缺陷，见夜报）"
        raise RuntimeError("新建会话按钮未出现")
    if not sc.tap_text("新建会话", 30):
        raise RuntimeError("点不到「新建会话」")
    f = wait_focus("TerminalActivity", 120)
    r["new_session_focus"] = f or "TIMEOUT"
    assert f, "新建会话后 TerminalActivity 未前台"

    sessions = {s["name"] for s in sc.registry_sessions()}
    r["sessions_final"] = sorted(sessions)
    assert len(sessions) >= 2, f"会话数不足：{sessions}"
    r["pass"] = True


# ---------- t2 ACTION_VIEW + provider ----------

@stage("t2_action_view_provider")
def t2(r):
    # 探针文件先行（env-run 直达环境，不经 UI）
    out = sc.env_read("printf 'night-b provider read ok\\n' > /root/night-probe.txt && echo WROTE\n", timeout=90)
    assert "WROTE" in out, f"探针文件写入失败：{out[-200:]}"
    # -S：上一阶段可能把 TerminalActivity 留在栈顶，非 -S 只把任务带前台揭不开主页
    sc.shell("am", "start", "-S", "-n", HOME)
    sc.wait_text("终端会话", 30)
    if not nav_tap("文件"):
        raise RuntimeError("进不了文件页（底部导航无「文件」）")
    if not sc.wait_text("night-probe.txt", 30):
        raise RuntimeError("文件页列表没有 night-probe.txt")

    logcat_clear()
    if not sc.tap_text("night-probe.txt", 15):
        raise RuntimeError("点不到 night-probe.txt 行")
    time.sleep(4)
    start_lines = [l for l in logcat("ActivityTaskManager").splitlines() if "START u0" in l and "drydock.documents" in l]
    r["action_view_start"] = start_lines[:2] or "无组件消费（Toast 兜底路径）"
    r["action_view_resolved"] = bool(start_lines)
    sc.shell("input", "keyevent", "KEYCODE_BACK")

    # provider 直读（content CLI）：provider 以 MANAGE_DOCUMENTS 守门，shell 无 grant
    # 被 SecurityException 拒——权限模型符合设计；read/write 判据走块 2 的 app 侧自测通道
    read1 = sc.shell("content", "read", "--uri", "content://dev.drydock.documents/root/%2Fnight-probe.txt")
    r["provider_read_shell"] = read1.strip()[:80] or "(空：SecurityException 预期，见夜报)"
    q = sc.shell("content", "query", "--uri", "content://dev.drydock.documents/root/%2F/children",
                 "--projection", "display_name", timeout=30)
    r["provider_children_has_probe"] = bool(q.strip())
    r["pass"] = r["action_view_resolved"]


# ---------- t3 context 字段端到端 ----------

@stage("t3_context_e2e")
def t3(r):
    logcat_clear()
    # 值含 |：设备端 shell 会当管道符，必须单引号包裹（exec64 通道同理的转义教训）
    sc.shell("am", "start", "-S", "-n", HOME,
             "--es", "drydock_endpoint", "'CHAT_COMPLETIONS|https://night.test/v4|night-model|131072'",
             "--es", "drydock_recipe", "OPENCODE")
    deadline = time.time() + 240
    cfg = ""
    while time.time() < deadline:
        cfg = logcat("DrydockRecipe")
        if "cfg <" in cfg and "CFG_RC" in cfg:
            break
        time.sleep(5)
    r["cfg_log_tail"] = cfg[-400:] if cfg else "无 DrydockRecipe cfg 日志"
    assert "CFG_RC" in cfg or "cfg <" in cfg, "applyEndpointConfig 日志未出现"

    oc = sc.env_read("cat /root/.config/opencode/opencode.json 2>/dev/null; echo ---; cat /root/.drydock-endpoint 2>/dev/null; echo ---; cat /etc/profile.d/drydock-env.sh 2>/dev/null\n", timeout=90)
    r["opencode_no_limit"] = '"limit"' not in oc  # limit 缺 output 会被 opencode 整体拒绝，不再写入
    r["endpoint_info_context"] = "context=131072" in oc
    r["drydock_env_baseurl"] = "DRYDOCK_API_KEY_BASE_URL='https://night.test/v4'" in oc  # D30: envVar 前缀
    r["files_tail"] = oc[-600:]
    r["pass"] = r["opencode_no_limit"] and r["endpoint_info_context"] and r["drydock_env_baseurl"]


# ---------- t4 浅色主题 ----------

@stage("t4_theme_light")
def t4(r):
    sc.shell("am", "start", "-S", "-n", HOME)
    sc.wait_text("终端会话", 30)
    if not nav_tap("设置"):
        raise RuntimeError("进不了设置页（底部导航无「设置」）")
    if not sc.wait_text("镜像源", 30):
        raise RuntimeError("设置页未出现")
    if not sc.tap_text("浅色", 15):
        raise RuntimeError("点不到「浅色」")
    r["pref_light"] = ">LIGHT</string>" in prefs_xml()
    # recreate() 后 tab 状态不保留、回到会话页：等重建完成再进设置截图
    time.sleep(1)
    sc.wait_text("终端会话", 20)
    if not nav_tap("设置") and not nav_tap("设置"):
        raise RuntimeError("recreate 后进不了设置页")
    sc.wait_text("镜像源", 30)
    r["screenshot"] = screencap("night-light-home.png")  # recreate 后的浅色实况
    r["theme_recreate_fix"] = True  # 观感判断留白天视觉批次
    if not sc.tap_text("跟随系统", 15):
        xml = sc.ui_dump()
        r["restore_dump_has_label"] = "跟随系统" in xml
        raise RuntimeError(f"点不到「跟随系统」（dump 含标签：{r['restore_dump_has_label']}）")
    time.sleep(1)  # apply() 异步落盘，稍候再读
    r["pref_restored"] = ">SYSTEM</string>" in prefs_xml()
    r["pass"] = r["pref_light"] and r["pref_restored"]


# ---------- t5 pi 配方 + npm 回退 ----------

@stage("t5_pi_npm_fallback")
def t5(r):
    orig = sc.env_read("cat /root/.drydock/mirrors 2>/dev/null || echo ABSENT\n", timeout=60)
    r["mirrors_before"] = orig.strip()[:200]
    out = sc.env_read(
        "mkdir -p /root/.drydock && printf 'export DRYDOCK_NPM_REGISTRY=https://registry.night-bogus.invalid\\n' > /root/.drydock/mirrors && echo WROTE_BOGUS\n",
        timeout=60)
    assert "WROTE_BOGUS" in out, "假 registry 写入失败"

    logcat_clear()
    sc.shell("am", "start", "-S", "-n", HOME, "--es", "drydock_recipe", "opencode,pi")
    print("等待 OPENCODE+PI 全量安装（假源失败 → npmjs 回退 → node 层 → apt 工具；预算 20 分钟）…", flush=True)
    deadline = time.time() + 1200
    log = ""
    while time.time() < deadline:
        log = logcat("DrydockRecipe")
        if "ensure pi:" in log:
            tail = log.split("ensure pi:")[-1]
            if "RECIPE_RC=" in tail:
                break
        time.sleep(10)
    r["npm_reg_echoed"] = sorted(set(re.findall(r"NPM_REG=(\S+)", log)))
    oc_tail = log.split("ensure opencode:")[-1][:400] if "ensure opencode:" in log else ""
    r["opencode_ensure_rc0"] = "RECIPE_RC=0" in oc_tail
    ensure_tail = log.split("ensure pi:")[-1][:2000] if "ensure pi:" in log else "无 ensure pi 日志"
    r["ensure_tail"] = ensure_tail
    r["fallback_fired"] = "换官方 npmjs 源重试" in ensure_tail
    r["recipe_rc0"] = "RECIPE_RC=0" in ensure_tail
    assert r["recipe_rc0"], "pi 安装未过（RECIPE_RC=0 未出现）"

    checks = sc.env_read(
        "command -v pi && pi --version 2>&1 | head -1; "
        "echo RG=$(rg --version 2>/dev/null | head -1); "
        "echo FD=$(fd --version 2>&1 | head -1); "
        "echo MODELS=$(grep -o '\"api\": \"[a-z-]*\"' /root/.pi/agent/models.json 2>/dev/null)\n",
        timeout=120)
    r["env_checks"] = checks.strip()[:400]
    r["pi_bin"] = "/pi" in checks or "pi " in checks
    r["rg"] = "RG=ripgrep" in checks
    r["fd_symlink"] = checks.count("fd ") > 0 or "FD=fd" in checks
    r["models_json"] = "MODELS=\"api\"" in checks
    r["prefs_pi"] = ",pi" in prefs_xml() or ">pi<" in prefs_xml()
    # .invalid 域不可能安装成功，RECIPE_RC=0 即必然走了回退；显式文案可能被 takeLast(400) 截断
    if not r["fallback_fired"] and r["recipe_rc0"]:
        r["fallback_note"] = "回退文案被日志截断，以 .invalid 必败 + RC=0 推定回退成立"
    r["pass"] = all([r["recipe_rc0"], r["pi_bin"], r["rg"], r["fd_symlink"], r["models_json"]])

    # 恢复 mirrors 原状；node 层若在假源期首次安装会写入 .npmrc=bogus，一并拨回
    if "ABSENT" in orig:
        sc.env_read("printf '# 默认回退链（覆盖已清空）\\n' > /root/.drydock/mirrors && echo RESTORED\n", timeout=60)
    else:
        sc.env_read(
            "mkdir -p /root/.drydock\ncat > /root/.drydock/mirrors <<'MEOF'\n" + orig.strip() + "\nMEOF\necho RESTORED\n",
            timeout=60)
    sc.env_read("command -v npm >/dev/null 2>&1 && npm config set registry https://registry.npmmirror.com; echo NPMRC_FIXED\n", timeout=120)
    r["mirrors_restored"] = "0" in sc.env_read("grep -c bogus /root/.drydock/mirrors\n", timeout=60).strip()


# ---------- t6 provider 全回路自测（块 2） ----------

@stage("t6_provider_selftest")
def t6(r):
    logcat_clear()
    sc.shell("am", "start", "-S", "-n", HOME, "--es", "drydock_provider_test", "1")
    deadline = time.time() + 60
    out = ""
    while time.time() < deadline:
        out = sc.run_as_cat("files/exec-out.txt")
        if "PROVIDER_TEST_RC=" in out:
            break
        time.sleep(3)
    r["out"] = out.strip()[:400]
    assert "PROVIDER_TEST_RC=" in out, "自测通道无输出"
    r["write_ok"] = "write=ok" in out
    r["read_ok"] = "read_ok=true" in out
    r["rename_ok"] = "rename_uri=true" in out
    r["children_ok"] = "children_listed=true" in out
    r["delete_ok"] = "delete_gone=true" in out
    r["rc0"] = "PROVIDER_TEST_RC=0" in out
    r["pass"] = all([r["write_ok"], r["read_ok"], r["rename_ok"], r["children_ok"], r["delete_ok"], r["rc0"]])


# ---------- t7 向导 ANTHROPIC 已知问题教育（块 3） ----------

@stage("t7_wizard_anthropic_hint")
def t7(r):
    sc.shell("dumpsys", "deviceidle", "whitelist", "+dev.drydock.prototype")  # AVD 测试条件：过保活步
    sc.shell("am", "start", "-S", "-n", HOME)
    sc.wait_text("终端会话", 30)
    if not nav_tap("设置"):
        raise RuntimeError("进不了设置页")
    # 「初始设置（…」或「重新运行初始设置」都含「初始设置」
    if not sc.tap_text("初始设置", 30):
        raise RuntimeError("找不到初始设置按钮")
    if not sc.wait_text("保活设置", 30):
        raise RuntimeError("向导未打开（保活步）")
    if not sc.tap_text("下一步", 30):
        raise RuntimeError("保活步过不去（豁免未生效？）")
    if not sc.wait_text("端点与模型", 30):
        raise RuntimeError("端点步未出现")
    if not sc.tap_text("Anthropic Messages", 20):
        raise RuntimeError("选不了 Anthropic Messages")
    time.sleep(1)
    xml = sc.ui_dump()
    r["hint_shown"] = "已知问题" in xml and "静默重试" in xml
    screencap("night-wizard-anthropic.png")
    sc.shell("input", "keyevent", "KEYCODE_BACK")
    r["pass"] = r["hint_shown"]


# ---------- t9 环境导出（块 5） ----------

@stage("t9_env_export")
def t9(r):
    # 清掉上一轮的陈旧输出（判据循环会被旧 EXPORT_* 秒过）
    sc.shell("run-as", sc.PKG, "rm", "-f", "files/exec-out.txt")
    logcat_clear()
    sc.shell("am", "start", "-S", "-n", HOME, "--es", "drydock_export", "1")
    print("等待 tar 导出（预算 5 分钟）…", flush=True)
    deadline = time.time() + 300
    out = ""
    while time.time() < deadline:
        out = sc.run_as_cat("files/exec-out.txt")
        if "EXPORT_DONE" in out or "EXPORT_FAILED" in out:
            break
        time.sleep(5)
    r["out"] = out.strip()[:300]
    assert "EXPORT_DONE" in out, "导出未完成"

    q = sc.shell("content", "query", "--uri", "content://media/external/downloads",
                 "--projection", "_display_name", "--where",
                 '"_display_name LIKE \'%drydock-env-export%\'"', timeout=30)
    r["media_row"] = q.strip()[:200]  # 仅记录（adb 引号易碎，判据以 pull 为准）
    # 只读拉回抽查：root 在、.l2s/.npm 排除（D21 约束 + 缓存不进备份）
    out_tgz = "/tmp/night-export.tar.gz"
    pull = subprocess.run(adb_prefix() + ["pull", "/sdcard/Download/Drydock/drydock-env-export.tar.gz", out_tgz],
                          capture_output=True, text=True, timeout=180)
    r["pulled"] = pull.returncode == 0
    if pull.returncode == 0:
        t = subprocess.run(["tar", "-tzf", out_tgz], capture_output=True, text=True, timeout=180).stdout
        r["tar_has_root"] = "root/" in t
        r["tar_no_l2s"] = ".l2s" not in t
        r["tar_no_npm_cache"] = "root/.npm/" not in t
        r["tar_entries"] = len(t.splitlines())
        if os.path.exists(out_tgz):
            os.remove(out_tgz)
        r["pass"] = r["tar_has_root"] and r["tar_no_l2s"] and r["tar_no_npm_cache"]
    else:
        r["pull_failed"] = pull.stderr.strip()[:120]
        r["pass"] = False


# ---------- t10 直通绑定（块 6） ----------

@stage("t10_bind_download")
def t10(r):
    # AVD 测试条件：appops 授予「所有文件访问」（真机上由用户本人在系统设置开启）
    sc.shell("appops", "set", sc.PKG, "MANAGE_EXTERNAL_STORAGE", "allow")
    # 预放一个标记文件
    sc.shell("mkdir", "-p", "/sdcard/Download", timeout=15)
    sc.shell("sh", "-c", "echo night-bind-from-host > /sdcard/Download/night-bind.txt", timeout=15)
    # 打开开关（设置页高级区在「高级」标题下）
    sc.shell("am", "start", "-S", "-n", HOME)
    sc.wait_text("终端会话", 30)
    if not nav_tap("设置"):
        raise RuntimeError("进不了设置页")
    if not sc.wait_text("高级：目录直通绑定", 60):
        raise RuntimeError("高级区不在可视区（滚动/tap_text 会自动翻）")
    # 定位开关：设置页还有镜像源单选钮也是 checkable，先按「已关闭/已开启」标签的
    # 纵向区间锁定同一行里的 Switch，避免点错单选钮
    import re as _re

    def find_switch():
        xml = sc.ui_dump()
        label = None
        for m in _re.finditer(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
            if m.group(1) in ("已关闭", "已开启（新建会话生效）", "已开启"):
                label = (int(m.group(3)), int(m.group(5)))
                break
        if label is None:
            return None
        y0, y1 = label
        for m in _re.finditer(r'checkable="true"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
            if int(m.group(2)) < y1 and int(m.group(4)) > y0:
                return ((int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2)
        return None

    sw = find_switch()
    if not sw:
        sc.swipe_up()
        sw = find_switch()
    assert sw, "找不到绑定 Switch"
    if "已关闭" in sc.ui_dump():  # 上一轮可能已开启，重复点会切回关
        sc.shell("input", "tap", str(sw[0]), str(sw[1]))
        time.sleep(2)
    r["perm_ok"] = "已获" in sc.ui_dump()
    r["pref_bind_on"] = 'name="bind_download" value="true"' in prefs_xml()

    # 主会话验证绑定（干净注册表下空状态没有「新建会话」按钮；打开终端=新 spawn 同样带 -b）
    sc.shell("am", "start", "-S", "-n", HOME)
    sc.wait_text("终端会话", 30)
    if not sc.tap_text("打开终端（agent 在这里）", 30):
        raise RuntimeError("打不开终端")
    assert wait_focus("TerminalActivity", 120), "验证会话未打开"
    # 环境侧探针走 exec64（app 进程口径）：run-as 通道的 FUSE 视角不具代表性
    # （夜批实证：run-as 下 appops 已 allow 仍 Permission denied；app 进程则绑定全通）。
    # 标记用 app 自己经绑定落（adb shell 造的 0660 跨 uid 文件读不了——如实记为该档代价）
    import base64 as _b64

    def exec64_probe(body, wait_tag, timeout_s=90):
        sc.shell("run-as", sc.PKG, "rm", "-f", "files/exec-out.txt")
        sc.shell("am", "start", "-S", "-n", HOME, "--es", "drydock_exec64",
                 _b64.b64encode(body.encode()).decode())
        deadline = time.time() + timeout_s
        out = ""
        while time.time() < deadline:
            out = sc.run_as_cat("files/exec-out.txt")
            if wait_tag in out:
                break
            time.sleep(3)
        return out

    out1 = exec64_probe(
        "echo HOST_MARKER_VIA_APP > /root/AndroidDownload/night-bind-app.txt; echo MARK_DONE\n", "MARK_DONE")
    out2 = exec64_probe(
        "cat /root/AndroidDownload/night-bind-app.txt 2>&1; "
        "echo WROTE_FROM_ENV > /root/AndroidDownload/night-bind-back.txt; echo BIND_PROBE_DONE\n", "BIND_PROBE_DONE")
    r["env_out"] = (out1.strip()[:120] + " || " + out2.strip()[:150])
    r["crossuid_note"] = "adb shell 造的 0660(u0_a202:media_rw) 标记读不了（as-is 记录）"
    r["env_sees_host_file"] = "HOST_MARKER_VIA_APP" in out2
    back = sc.shell("cat", "/sdcard/Download/night-bind-back.txt", timeout=15)
    back2 = sc.shell("cat", "/sdcard/Download/night-bind-app.txt", timeout=15)
    r["host_sees_env_file"] = back.strip() == "WROTE_FROM_ENV" and back2.strip() == "HOST_MARKER_VIA_APP"
    r["pass"] = r["pref_bind_on"] and r["env_sees_host_file"] and r["host_sees_env_file"]


# ---------- t11 终端页内菜单（块 7） ----------

@stage("t11_terminal_menu")
def t11(r):
    sc.shell("am", "start", "-S", "-n", HOME)
    sc.wait_text("终端会话", 30)
    if not sc.tap_text("打开终端（agent 在这里）", 30):
        raise RuntimeError("打不开终端")
    assert wait_focus("TerminalActivity", 120), "终端未前台"
    if not sc.tap_text("☰", 20):
        raise RuntimeError("找不到菜单按钮 ☰")
    if not sc.wait_text("回主页", 20):
        raise RuntimeError("菜单未弹出")
    xml = sc.ui_dump()
    r["menu_lists_sessions"] = "主终端" in xml
    r["menu_has_new_and_home"] = ("新建会话" in xml) and ("回主页" in xml)
    screencap("night-terminal-menu.png")

    # 切换会话：回主页键路径验证
    if not sc.tap_text("回主页", 10):
        raise RuntimeError("点不到回主页")
    time.sleep(2)
    f = focus()
    r["back_home_focus"] = f
    r["back_home_ok"] = "HomeActivity" in f
    r["pass"] = r["menu_lists_sessions"] and r["menu_has_new_and_home"] and r["back_home_ok"]


def main():
    wanted = sys.argv[1:] or ["t1", "t2", "t3", "t4", "t5", "t6", "t7", "t9", "t10", "t11"]
    runners = {"t1": t1, "t2": t2, "t3": t3, "t4": t4, "t5": t5,
               "t6": t6, "t7": t7, "t9": t9, "t10": t10, "t11": t11}
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
