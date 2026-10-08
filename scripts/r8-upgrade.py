#!/usr/bin/env python3
"""R8 rootfs 升级路径判据剧本（2026-10-08）：AVD 全 UI 路径驱动 + 命令行断言。

  p0  装新 APK → pm clear → 首建会话（部署旧版 24.04.5 + 终端层）→ 从干净树构造
      「新版 24.04.6-r8test」镜像 tar + 本地更新索引 → 环境内装 Node 与自装包底料
  j1  造用户态（工作区/.drydock/.ssh/.npmrc/改 .bashrc/apt sl htop/npm left-pad +
      黑名单项 .npm/.cache/AndroidDownload/*.sock/.l2s*）→ 设置页「检查更新」升级 →
      黑名单外文件 sha256 全一致、.bashrc 保用户版、排除项确未迁、自装包报告 +
      一键重装命令真实执行（判据 1、4）
  j2  新版内新增/改文件 → 一键回滚 → 新改动随反向迁移仍在、版本回旧、系统层回退（判据 2）
  j3  构造 sha256 不符的「新版」→ 拒绝切换、报错明确、旧环境能建会话跑命令（判据 3）
  j5  升级与回滚各走一遍后：新建会话 → 提示符渲染 → 运行命令（判据 5）

用法：ANDROID_SERIAL=emulator-5554 python3 scripts/r8-upgrade.py [p0 j1 j2 j3 j5]
输出：draft/r8-upgrade-verdict.json（每阶段后落盘，崩溃安全）；截图存 draft/。
只动模拟器（AVD 可 pm clear / 写 /data/local/tmp）；真机纪律见 AGENTS.md。
"""
import hashlib
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
DRAFT = sc.DRAFT
VERDICT = os.path.join(DRAFT, "r8-upgrade-verdict.json")
APK = os.path.join(sc.ROOT, "app/build/outputs/apk/debug/app-debug.apk")

NEW_VER = "24.04.6-r8test"
OLD_VER = "24.04.5"
BAD_VER = "24.04.7-r8bad"
NEW_TAR = "r8-new-rootfs.tar.gz"
BAD_TAR = "r8-bad-rootfs.tar.gz"
MASTER_DIR = "files/r8-master"  # 设备侧主副本（j1 幂等重跑用，不走 adb 长流）
NODE_VER = "v22.20.0"
NODE_SHA = "4181609e03dcb9880e7e5bf956061ecc0503c77a480c6631d868cb1f65a2c7dd"  # AgentManager 同款 pin

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
                ensure_device()
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


ADB_BIN = os.environ.get("ADB", os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))


def ensure_device():
    """adb 闪断兜底（run1 实锤：exec-out 长流中途 transport 掉线）：先等目标 serial
    回到 device 态再放行；adb_prefix 自身的在线校验仍在。"""
    serial = os.environ.get("ANDROID_SERIAL", "").strip()
    if serial:
        subprocess.run([ADB_BIN, "-s", serial, "wait-for-device"],
                       capture_output=True, timeout=300)
    time.sleep(1)


def shell_q(cmd, timeout=30):
    """含引号/空格路径的整串 shell 命令（adb shell 多参数会被设备端 shell 重切）。"""
    return subprocess.run(adb_prefix() + ["shell", cmd], capture_output=True, text=True,
                          timeout=timeout).stdout


def screencap(name):
    path = os.path.join(DRAFT, name)
    p = subprocess.run(adb_prefix() + ["exec-out", "screencap", "-p"], capture_output=True, timeout=60)
    open(path, "wb").write(p.stdout)
    return path


def focus():
    out = sc.shell("dumpsys", "window")
    for line in out.splitlines():
        if "mCurrentFocus=" in line:
            return line.strip()
    return out[:120]


# ---------- rootfs 只读探针（run-as 直接看 app 私有目录，不依赖 proot） ----------

def manifest_version():
    txt = sc.run_as_cat("files/ubuntu-rootfs/.drydock-manifest")
    m = re.search(r"version=(\S+)", txt)
    return m.group(1) if m else ""


def rootfs_read(rel, binary=False):
    """rootfs 内相对路径 → 内容（cat / ls 失败原样返回，供 No such file 断言）。"""
    out = subprocess.run(adb_prefix() + ["exec-out", "run-as", PKG, "cat",
                                         f"files/ubuntu-rootfs/{rel}"],
                         capture_output=True, timeout=60)
    return out.stdout if binary else out.stdout.decode("utf-8", "replace")


def rootfs_readlink(rel):
    return subprocess.run(
        adb_prefix() + ["exec-out", "run-as", PKG, "readlink", f"files/ubuntu-rootfs/{rel}"],
        capture_output=True, timeout=60).stdout.decode().strip()


def env_pids():
    """环境进程树 pid 集（proot/ttyd/dtach）：升级停会话/重建断言用。"""
    out = sc.shell("ps", "-A", "-o", "PID,ARGS", timeout=30)
    return {l.split()[0] for l in out.splitlines()
            if "ttyd" in l or "dtach" in l or "libproot" in l or "dtach-" in l}


def rootfs_sha(rel):
    out = sc.shell("run-as", PKG, "sha256sum", f"files/ubuntu-rootfs/{rel}", timeout=120).strip()
    m = re.match(r"([0-9a-f]{64})", out)
    return m.group(1) if m else None


def rootfs_exists(rel):
    out = sc.shell("run-as", PKG, "ls", f"files/ubuntu-rootfs/{rel}", timeout=30).strip()
    return "No such file" not in out and out != ""


def push_to_files(local, dst_rel):
    """宿主文件 → app 私有目录（push /data/local/tmp + run-as cp，白名单写法）。"""
    tmp = "/data/local/tmp/" + os.path.basename(dst_rel)
    p = subprocess.run(adb_prefix() + ["push", local, tmp], capture_output=True, text=True, timeout=300)
    assert p.returncode == 0, f"push {local} 失败：{p.stderr[:150]}"
    sc.shell("run-as", PKG, "cp", tmp, dst_rel, timeout=60)
    sc.shell("rm", "-f", tmp)


def write_index(releases):
    idx = json.dumps({"releases": releases})
    open("/tmp/r8-index.json", "w").write(idx)
    push_to_files("/tmp/r8-index.json", "files/rootfs-updates.json")


# ---------- UI 驱动 ----------

def tap_bottom_settings():
    """底部导航「设置」（已在 HomeActivity）。"""
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


def nav_settings_update():
    """冷进主页 → 底部设置 → 系统更新二级页（重启 app，会话随之全停）。"""
    sc.shell("input", "keyevent", "KEYCODE_WAKEUP")
    sc.shell("am", "start", "-S", "-n", HOME)
    assert sc.wait_text("新建会话", 60), "HomeActivity 未出现"
    tap_bottom_settings()
    assert sc.wait_text("系统更新", 20), "设置主页面未出现"
    assert sc.tap_text("系统更新", 20), "点不到「系统更新」入口"
    assert sc.wait_text("检查更新", 20), "系统更新页未出现"


def open_update_page_from_home():
    """已在 HomeActivity（活跃会话在跑）→ 设置 → 系统更新（不重启 app 不杀会话）。"""
    assert sc.wait_text("新建会话", 30), "HomeActivity 未在前台"
    tap_bottom_settings()
    assert sc.wait_text("系统更新", 20), "设置主页面未出现"
    assert sc.tap_text("系统更新", 20), "点不到「系统更新」入口"
    assert sc.wait_text("检查更新", 20), "系统更新页未出现"


def wait_msg(keywords, timeout_s=900):
    """等页面上出现 ✓/✗ 结果行（多轮扫描，返回首条命中文案）。"""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        xml = sc.ui_dump()
        for m in re.finditer(r'text="([✓✗][^"]*)"', xml):
            if any(k in m.group(1) for k in keywords):
                return m.group(1)
        time.sleep(3)
    return None


# ---------- 终端实证（CDP：ws 输入帧不达 PTY 是 AV2 已知教训，输入走 WebView xterm） ----------

def open_terminal(name=None):
    """TerminalActivity 前台化（WebView/xterm 在场是 CDP 的前提）。"""
    if name:
        sc.shell("am", "start", "-n", f"{PKG}/.TerminalActivity", "--es", "session", name)
    deadline = time.time() + 90
    while time.time() < deadline:
        if "TerminalActivity" in focus():
            return True
        time.sleep(2)
    return False


def term_eval(js):
    r = sc.run(["uv", "run", "--with", "websockets", sc.repo("cdp-eval.py"), js], timeout=90)
    try:
        return json.loads(r.stdout)
    except Exception:
        return None


def term_text():
    v = term_eval("(document.querySelector('.terminal')||{innerText:''}).innerText || ''")
    return v if isinstance(v, str) else ""


def term_run(cmd, mark, timeout_s=40):
    """CDP 实证输入：focus xterm → 打字（字面 \\n 为回车）→ 轮询 DOM 文本出 mark。"""
    term_eval("typeof term !== 'undefined' && term.focus()")
    sc.cdp_type(cmd + "\\n")
    deadline = time.time() + timeout_s
    t = ""
    while time.time() < deadline:
        t = term_text()
        if mark in t:
            break
        time.sleep(2)
    return t


# ---------- p0 基线：部署旧版 + 构造新版镜像与索引 ----------

@stage("p0_baseline_and_new_image")
def p0(r):
    # 装新 APK + 清数据（AVD 专用；真机纪律禁 pm clear）→ 干净基线
    assert os.path.exists(APK), f"APK 不存在：{APK}（先 ./gradlew assembleDebug）"
    p = subprocess.run(adb_prefix() + ["install", "-r", APK], capture_output=True, text=True, timeout=180)
    assert p.returncode == 0, f"APK 安装失败：{p.stdout[-200:]}{p.stderr[-200:]}"
    sc.shell("pm", "clear", PKG)
    r["fresh_installed"] = True

    # 首建会话 = 部署 24.04.5 + 终端层（UI 全链路；预算盖过部署+apt，见 night-b t1 教训）
    sc.shell("input", "keyevent", "KEYCODE_WAKEUP")
    sc.shell("am", "start", "-S", "-n", HOME)
    assert sc.wait_text("新建会话", 60), "HomeActivity 未出现"
    assert sc.tap_text("新建会话", 30), "点不到「新建会话」"
    assert sc.wait_text("会话名称", 30), "新建对话框未出现"
    assert sc.tap_text("创建", 30), "点不到「创建」"
    deadline = time.time() + 600
    while time.time() < deadline:
        if "TerminalActivity" in focus():
            break
        time.sleep(3)
    assert "TerminalActivity" in focus(), "首建会话（含部署）超时"
    r["deployed"] = manifest_version()
    assert r["deployed"] == OLD_VER, f"基线版本异常：{r['deployed']}"

    # 终端层齐备后才能动树（force-stop 前提：apt 事务不被腰斩）
    probe = ("miss=0; for b in ttyd dtach git rg fd curl wget zip unzip xz bzip2 jq file ps ssh less; do "
             "command -v $b >/dev/null 2>&1 || miss=1; done; echo LAYER_MISS=$miss\n")
    deadline = time.time() + 300
    while time.time() < deadline:
        if "LAYER_MISS=0" in sc.env_read(probe, timeout=120):
            break
        time.sleep(5)
    r["layer_ready"] = "LAYER_MISS=0" in sc.env_read(probe, timeout=120)
    assert r["layer_ready"], "终端层未就绪"

    # CA bundle 显式就位（run4 实锤：ca-certificates 的 dpkg 触发器在首个 apt 事务里
    # 未落出 ca-certificates.crt，紧随其后的 https curl 报 77；触发器在下一次 dpkg
    # 事务才补建）。放在 tar 之前 → 新版镜像自带 CA，升级后的 ensureNodeLayer 不踩同坑
    ca = sc.env_read(
        "[ -f /etc/ssl/certs/ca-certificates.crt ] || update-ca-certificates 2>&1 | tail -1; "
        "ls -l /etc/ssl/certs/ca-certificates.crt 2>&1 | head -1\n", timeout=300)
    r["ca_ready"] = "root root" in ca or "rw" in ca
    assert r["ca_ready"] and "No such" not in ca, f"CA bundle 未就位：{ca[-200:]}"

    # 构造「新版」镜像：树内种新版标记与模板 → 停 app → run-as toybox tar 整树 → 还原标记
    out = sc.env_read(
        "echo drydock-r8-new > /etc/r8-release; "
        "printf '# r8 new template\\nexport R8_TEMPLATE=1\\n' > /root/.bashrc; "
        "printf 'new-template-only\\n' > /root/.r8-new-template.txt; "
        "rm -f /root/main.sock; echo SEEDED\n", timeout=120)
    assert "SEEDED" in out, f"种标记失败：{out[-200:]}"
    sc.shell("am", "force-stop", PKG)
    time.sleep(2)
    sc.shell("run-as", PKG, "rm", "-f", "files/ubuntu-rootfs/root/main.sock", timeout=30)
    t0 = time.time()
    sc.shell("run-as", PKG, "tar", "-czf", f"cache/{NEW_TAR}",
             "-C", "files/ubuntu-rootfs", ".", timeout=900)
    r["tar_seconds"] = int(time.time() - t0)
    # 还原树（旧版基线不该带新版标记）
    sc.shell("run-as", PKG, "rm", "-f",
             "files/ubuntu-rootfs/etc/r8-release",
             "files/ubuntu-rootfs/root/.r8-new-template.txt",
             "files/ubuntu-rootfs/root/.bashrc", timeout=30)
    r["marker_reverted"] = not rootfs_exists("etc/r8-release")

    size_s = sc.shell("run-as", PKG, "stat", "-c", "%s", f"cache/{NEW_TAR}", timeout=60).strip()
    sha_s = sc.shell("run-as", PKG, "sha256sum", f"cache/{NEW_TAR}", timeout=300).strip()
    size = int(re.search(r"(\d+)", size_s).group(1))
    sha = re.search(r"([0-9a-f]{64})", sha_s).group(1)
    r["new_image"] = {"size": size, "sha256": sha[:16] + "…"}
    assert size > 10_000_000, f"镜像体积异常：{size}"

    # 主副本留设备侧 app 私有目录（j1 幂等重跑用：升级成功后 cache 内 tar 会被清理；
    # 不走 adb 长流——run1 实锤 exec-out 大流会撞 transport 闪断）
    sc.shell("run-as", PKG, "mkdir", "-p", MASTER_DIR, timeout=30)
    sc.shell("run-as", PKG, "cp", f"cache/{NEW_TAR}", f"{MASTER_DIR}/{NEW_TAR}", timeout=300)
    back = sc.shell("run-as", PKG, "stat", "-c", "%s", f"{MASTER_DIR}/{NEW_TAR}", timeout=60).strip()
    r["master_copied"] = re.search(r"(\d+)", back).group(1) == str(size)
    assert r["master_copied"], f"设备侧主副本体积不符：{back}"

    # 更新索引：指向 cache 内已就位的 tar（下载步按体积跳过，不依赖远端）
    write_index([{"version": NEW_VER, "fileName": NEW_TAR, "sizeBytes": size,
                  "sha256": sha, "mirrors": ["https://updates.invalid/r8/"]}])
    sc.shell("run-as", PKG, "cp", "files/rootfs-updates.json", f"{MASTER_DIR}/rootfs-updates.json", timeout=60)
    r["index_written"] = True

    # 旧版环境内装 Node 运行时 + npm 自装包底料（升级后 npm 报告与重装的素材）。
    # tarball 与 left-pad 包宿主侧下载后 push 进环境（run3-5 实锤：AVD 环境内 curl
    # 偶发连接级瞬断，与产品无关——生产 ensureNodeLayer 走 app 侧 HttpURLConnection
    # + 双镜像回退，不受影响）；tar 解包的 failure status 告警同理不设门槛
    # （AgentManager 同款），判据在功能项（node --version / left-pad 在场）。
    node_local = "/tmp/r8-node.tgz"
    subprocess.run(["curl", "-fsSL", "--retry", "3", "-o", node_local,
                    f"https://registry.npmmirror.com/-/binary/node/{NODE_VER}/"
                    f"node-{NODE_VER}-linux-arm64.tar.gz"], check=True, timeout=600)
    got = hashlib.sha256(open(node_local, "rb").read()).hexdigest()
    assert got == NODE_SHA, f"宿主侧 node tarball sha 不符：{got[:16]}"
    lp_local = "/tmp/r8-leftpad.tgz"
    subprocess.run(["curl", "-fsSL", "--retry", "3", "-o", lp_local,
                    "https://registry.npmmirror.com/left-pad/-/left-pad-1.3.0.tgz"],
                   check=True, timeout=120)
    for local in (node_local, lp_local):
        p = subprocess.run(adb_prefix() + ["push", local, "/data/local/tmp/"], capture_output=True, text=True, timeout=300)
        assert p.returncode == 0, f"push {local} 失败：{p.stderr[:120]}"
    sc.shell("run-as", PKG, "cp", "/data/local/tmp/r8-node.tgz", "files/ubuntu-rootfs/tmp/r8-node.tgz", timeout=300)
    sc.shell("run-as", PKG, "cp", "/data/local/tmp/r8-leftpad.tgz", "files/ubuntu-rootfs/tmp/r8-leftpad.tgz", timeout=60)
    sc.shell("rm", "-f", "/data/local/tmp/r8-node.tgz", "/data/local/tmp/r8-leftpad.tgz")

    node_sh = f"""cd /opt || {{ echo NO_OPT; exit 1; }}
[ -x node-{NODE_VER}-linux-arm64/bin/node ] || tar -xzf /tmp/r8-node.tgz 2>&1 | tail -1
ln -sf /opt/node-{NODE_VER}-linux-arm64/bin/node /usr/local/bin/node
ln -sf /opt/node-{NODE_VER}-linux-arm64/bin/npm /usr/local/bin/npm
ln -sf /opt/node-{NODE_VER}-linux-arm64/bin/npx /usr/local/bin/npx
npm config set prefix /usr/local
npm config set registry https://registry.npmmirror.com
npm install -g /tmp/r8-leftpad.tgz 2>&1 | tail -2
node --version
ls /usr/local/lib/node_modules/left-pad/package.json >/dev/null 2>&1 && echo LEFTPAD_OK
rm -f /tmp/r8-node.tgz /tmp/r8-leftpad.tgz
echo NODE_DONE
"""
    out = sc.env_read(node_sh, timeout=900)
    r["node_installed"] = "LEFTPAD_OK" in out and "NODE_DONE" in out and NODE_VER in out
    r["node_out_tail"] = out[-200:]
    assert r["node_installed"], f"Node/left-pad 安装失败：{out[-300:]}"
    r["pass"] = all([r["fresh_installed"], r["layer_ready"], r["marker_reverted"],
                     r["index_written"], r["node_installed"], r["deployed"] == OLD_VER])


# ---------- j1 升级保数据 + 自装包报告（判据 1、4） ----------

KEEP_FILES = [  # 黑名单外必须逐字节存活的用户文件（.npmrc 例外：Node 基础层恢复的
    # `npm config set prefix` 会在迁移后重写它——npm 自身行为，非迁移丢失，另立存活断言）
    "root/r8-ws/feature.txt",
    "root/r8-ws/deep/a.b.txt",
    "root/r8-ws/tarlink",  # 符号链接：比 readlink 目标
    "root/.drydock/env.sh",
    "root/.ssh/id_ed25519",
    "root/.pi/config.toml",
    "root/.config/app.conf",
    "root/.local/lib/pyver",
]
EXCLUDED = [  # 黑名单项：升级后不得出现在新环境
    "root/.npm/_cacache", "root/.cache/blob", "root/AndroidDownload/note.txt",
    "root/r8-session.sock", "root/.l2s.fake",
]


@stage("j1_upgrade_keeps_data")
def j1(r):
    # 幂等回填（重跑场景）：升级成功会清 cache 包；索引可能被 j5 收尾撤走
    have = sc.shell("run-as", PKG, "stat", "-c", "%s", f"cache/{NEW_TAR}", timeout=60).strip()
    if not re.match(r"^\d+$", have):
        sc.shell("run-as", PKG, "cp", f"{MASTER_DIR}/{NEW_TAR}", f"cache/{NEW_TAR}", timeout=300)
    idx = sc.shell("run-as", PKG, "stat", "-c", "%s", "files/rootfs-updates.json", timeout=60).strip()
    if not re.match(r"^\d+$", idx):
        sc.shell("run-as", PKG, "cp", f"{MASTER_DIR}/rootfs-updates.json", "files/rootfs-updates.json", timeout=60)

    plant = (
        "mkdir -p /root/r8-ws/deep /root/.drydock /root/.ssh /root/.pi /root/.config/app.d /root/.local/lib; "
        "printf 'feature-line-1\\n' > /root/r8-ws/feature.txt; "
        "printf 'deep-data\\n' > /root/r8-ws/deep/a.b.txt; "
        "ln -sf /usr/bin/tar /root/r8-ws/tarlink; "
        "printf '# USER bashrc\\nexport R8_USER=1\\n' > /root/.bashrc; "
        "printf 'export R8_ENDPOINT=https://u.example\\n' > /root/.drydock/env.sh; "
        "printf 'sshkey-data\\n' > /root/.ssh/id_ed25519; chmod 600 /root/.ssh/id_ed25519; "
        "printf 'npmrc-user\\n' > /root/.npmrc; "
        "printf '[pi]\\n' > /root/.pi/config.toml; "
        "printf 'cfg=1\\n' > /root/.config/app.conf; "
        "printf 'sub=2\\n' > /root/.config/app.d/x.conf; "
        "printf 'py-user\\n' > /root/.local/lib/pyver; "
        "mkdir -p /root/.npm /root/.cache /root/AndroidDownload; "
        "printf 'c' > /root/.npm/_cacache; printf 'b' > /root/.cache/blob; printf 'n' > /root/AndroidDownload/note.txt; "
        "printf 'sock-data' > /root/r8-session.sock; ln -sf /usr/bin/git /root/.l2s.fake; "
        "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends sl htop >/dev/null 2>&1; "
        "echo SL=$(ls /usr/games/sl 2>/dev/null) HTOP=$(command -v htop)\n"
    )
    out = sc.env_read(plant, timeout=300)
    assert "SL=/usr/games/sl" in out and "HTOP=/usr/bin/htop" in out, f"自装包/用户态植入失败：{out[-300:]}"

    ref_sha = {f: rootfs_sha(f) for f in KEEP_FILES if not f.endswith("tarlink")}
    ref_link = rootfs_readlink("root/r8-ws/tarlink")
    r["ref_files"] = len(ref_sha) + 1
    assert all(ref_sha.values()) and ref_link == "/usr/bin/tar", \
        f"sha/link 采集失败：{[k for k, v in ref_sha.items() if not v]} link={ref_link}"

    # 升级时有活跃会话（切换期处理断言的素材）：打开主会话 → 记环境进程 pid 集
    sc.shell("input", "keyevent", "KEYCODE_WAKEUP")
    sc.shell("am", "start", "-S", "-n", HOME)
    assert sc.wait_text("本地端口", 60), "会话卡片未出现"
    assert sc.tap_text("本地端口", 30), "点不到主会话卡片"
    deadline = time.time() + 180
    while time.time() < deadline and "TerminalActivity" not in focus():
        time.sleep(3)
    assert "TerminalActivity" in focus(), "主会话未打开"
    pids_before = env_pids()
    r["sessions_running_before"] = sorted(pids_before)
    assert pids_before, "升级前活跃会话进程未见"
    sc.shell("input", "keyevent", "KEYCODE_BACK")  # 回主页（EnvService 仍持有会话）
    assert sc.wait_text("新建会话", 30), "BACK 后未回主页"

    open_update_page_from_home()
    r["screenshot_page"] = screencap("r8-j1-page.png")
    assert sc.tap_text("检查更新", 30), "点不到「检查更新」"
    found = None
    deadline = time.time() + 60
    while time.time() < deadline:
        m = re.search(r'text="发现新版本 ([^\s（]+)', sc.ui_dump())
        if m:
            found = m.group(1)
            break
        time.sleep(2)
    r["found_version"] = found
    assert found == NEW_VER, f"未发现新版本 {NEW_VER}：{found}"
    assert sc.tap_text(f"升级到 {NEW_VER}", 20), "点不到「升级」按钮"
    assert sc.wait_text("升级 rootfs？", 15), "升级确认框未出现"
    assert sc.tap_text("开始升级", 15), "点不到「开始升级」"
    r["screenshot_upgrading"] = screencap("r8-j1-upgrading.png")

    result = wait_msg(["升级完成", "升级失败"], 900)
    r["result"] = result
    xml = sc.ui_dump().replace("&amp;", "&").replace("&#10;", "\n").replace("&#13;", "")
    r["screenshot_report"] = screencap("r8-j1-report.png")
    assert result and "升级完成" in result, f"升级未完成：{result}"
    assert manifest_version() == NEW_VER, f"升级后版本异常：{manifest_version()}"

    # 判据 1 断言先行（文件层最关键，别被会话探针偶发挡住证据）
    mismatch = []
    for f, h in ref_sha.items():
        if rootfs_sha(f) != h:
            mismatch.append(f)
    if rootfs_readlink("root/r8-ws/tarlink") != "/usr/bin/tar":
        mismatch.append("tarlink")
    r["sha_checked"] = len(ref_sha) + 1
    r["sha_mismatch"] = mismatch
    assert not mismatch, f"用户文件迁移丢失/走样：{mismatch[:5]}"

    # .npmrc：迁移存活断言（内容允许被 npm config 重写，见 KEEP_FILES 注释）
    npmrc = rootfs_read("root/.npmrc")
    r["npmrc_survived"] = rootfs_exists("root/.npmrc") and (
        "prefix=" in npmrc or "npmrc-user" in npmrc)
    assert r["npmrc_survived"], f".npmrc 未随迁移存活：{npmrc[:60]}"

    # 切换期会话处理断言：升级前 pid 全消失，切换后按注册表重建（新 pid 服务新版）
    pids_after = set()
    deadline = time.time() + 120
    while time.time() < deadline:
        pids_after = env_pids()
        if pids_after:
            break
        time.sleep(3)
    r["sessions_rebuilt_after"] = sorted(pids_after)
    r["old_pids_gone"] = not (pids_before & pids_after)
    assert pids_after and r["old_pids_gone"], \
        f"会话未重建或 pid 未换：before={sorted(pids_before)} after={sorted(pids_after)}"

    bashrc = rootfs_read("root/.bashrc")
    r["bashrc_is_user_version"] = "R8_USER=1" in bashrc and "R8_TEMPLATE" not in bashrc
    assert r["bashrc_is_user_version"], f".bashrc 未保用户版：{bashrc[:80]}"
    r["new_template_landed"] = rootfs_read("root/.r8-new-template.txt").strip() == "new-template-only"
    r["new_release_marker"] = rootfs_read("etc/r8-release").strip() == "drydock-r8-new"
    assert r["new_template_landed"] and r["new_release_marker"], "新版模板/标记未落位"
    r["excluded_not_migrated"] = {f: not rootfs_exists(f) for f in EXCLUDED}
    assert all(r["excluded_not_migrated"].values()), f"黑名单项被迁移：{r['excluded_not_migrated']}"

    # 判据 4：自装包报告——含 sl/htop/left-pad，不含基础层与配方包；一键重装真实执行
    m_apt = re.search(r'自装 apt 包：([^"<\n]*)', xml)
    m_npm = re.search(r'自装 npm 包：([^"<\n]*)', xml)
    r["report_apt"] = m_apt.group(1).strip() if m_apt else None
    r["report_npm"] = m_npm.group(1).strip() if m_npm else None
    apt_list = set((r["report_apt"] or "").split())
    npm_list = set((r["report_npm"] or "").split())
    pkg_shape = re.compile(r"^[a-z0-9@/][a-z0-9.@/+-]*$")
    r["apt_has_self_pkgs"] = {"sl", "htop"} <= apt_list
    r["apt_all_pkg_shape"] = all(pkg_shape.match(t) for t in apt_list)
    r["npm_exact"] = npm_list == {"left-pad"}
    r["no_layer_pkgs"] = not (apt_list & {"ttyd", "dtach", "git", "ripgrep", "fd-find", "curl",
                                          "wget", "zip", "unzip", "xz-utils", "bzip2", "jq",
                                          "file", "procps", "openssh-client", "ca-certificates", "less"})
    assert r["apt_has_self_pkgs"] and r["apt_all_pkg_shape"] and r["npm_exact"] and r["no_layer_pkgs"], \
        f"自装包报告不精确：apt={apt_list} npm={npm_list}"
    cmd_apt = re.search(r'text="(apt-get update[^"<\n]*)"', xml)
    cmd_npm = re.search(r'text="(npm install -g[^"<\n]*)"', xml)
    r["cmd_apt"] = cmd_apt.group(1) if cmd_apt else None
    r["cmd_npm"] = cmd_npm.group(1) if cmd_npm else None
    assert r["cmd_apt"] and "sl" in r["cmd_apt"] and "htop" in r["cmd_apt"], f"apt 重装命令缺失：{r['cmd_apt']}"
    assert r["cmd_npm"] and "left-pad" in r["cmd_npm"], f"npm 重装命令缺失：{r['cmd_npm']}"

    # 升级换掉了系统层：sl/htop 随旧树退场（自装包不自动重装，D33）；Node 是宿主
    # pin 基础层（D26 重走装机判据）应已自动恢复
    gone = sc.env_read("ls /usr/games/sl 2>&1 | head -1; command -v htop; "
                       "command -v node; echo DONE\n", timeout=120)
    r["self_pkgs_gone"] = gone.count("No such file") >= 1 and "htop" not in gone
    r["node_restored_by_upgrade"] = "/usr/local/bin/node" in gone
    assert r["self_pkgs_gone"], f"自装包不该自动重装：{gone[:120]}"
    assert r["node_restored_by_upgrade"], "Node 基础层未随升级恢复（D26 重走装机判据）"

    # 一键重装命令逐字执行（报告原文，在环境内跑）：npm 先配镜像源（用户侧配置，
    # 与报告命令本身无关）；AVD 环境网络偶发瞬断，同一命令重试一轮
    def reinstall():
        return sc.env_read(
            "npm config set registry https://registry.npmmirror.com; "
            + r["cmd_apt"] + " >/dev/null 2>&1; echo APT_RC=$?; "
            + r["cmd_npm"] + " >/dev/null 2>&1; echo NPM_RC=$?; "
            "ls /usr/games/sl >/dev/null 2>&1 && echo SL_BACK; "
            "command -v htop >/dev/null && echo HTOP_BACK; "
            "node -e \"require('/usr/local/lib/node_modules/left-pad')\" 2>/dev/null && echo LP_BACK\n",
            timeout=600)

    out = reinstall()
    r["reinstall_retried"] = False
    if not ("APT_RC=0" in out and "NPM_RC=0" in out and "SL_BACK" in out and "LP_BACK" in out):
        time.sleep(10)
        out2 = reinstall()
        r["reinstall_retried"] = True
        out = out + "\n--RETRY--\n" + out2
    r["reinstall_out_tail"] = out[-260:]
    r["apt_reinstall_ok"] = "APT_RC=0" in out and "SL_BACK" in out and "HTOP_BACK" in out
    r["npm_reinstall_ok"] = "NPM_RC=0" in out and "LP_BACK" in out
    assert r["apt_reinstall_ok"], f"apt 一键重装失败：{out[-300:]}"
    assert r["npm_reinstall_ok"], f"npm 一键重装失败：{out[-300:]}"

    # 重建会话确实服务新版环境（CDP 实证输入路径）
    assert open_terminal("main"), "升级后打不开主会话终端页"
    assert sc.cdp_forward(), "CDP forward 失败"
    txt = term_run("echo R8_NEWENV && cat /etc/r8-release", "R8_NEWENV")
    r["newenv_session_cmd"] = txt[-160:]
    r["newenv_session_ok"] = "R8_NEWENV" in txt and "drydock-r8-new" in txt
    assert r["newenv_session_ok"], f"重建会话未运行在新版环境：{r['newenv_session_cmd']}"

    r["pass"] = all([not mismatch, r["npmrc_survived"], r["bashrc_is_user_version"],
                     r["new_template_landed"],
                     r["new_release_marker"], all(r["excluded_not_migrated"].values()),
                     r["apt_has_self_pkgs"], r["apt_all_pkg_shape"], r["npm_exact"],
                     r["no_layer_pkgs"], r["apt_reinstall_ok"], r["npm_reinstall_ok"],
                     r["self_pkgs_gone"], r["node_restored_by_upgrade"],
                     pids_after and r["old_pids_gone"], r["newenv_session_ok"]])


# ---------- j2 回滚不丢新改动（判据 2） ----------

@stage("j2_rollback_keeps_new_changes")
def j2(r):
    out = sc.env_read(
        "printf 'post-upgrade-note\\n' > /root/r8-post.txt; "
        "printf 'feature-line-2\\n' >> /root/r8-ws/feature.txt; "
        "printf 'k=v\\n' > /root/.config/app.d/new-key.conf; "
        "printf 'ws2-data\\n' > /root/r8-ws/second.txt; echo PLANTED2\n", timeout=120)
    assert "PLANTED2" in out, f"升级后新改动植入失败：{out[-200:]}"

    nav_settings_update()
    assert sc.tap_text("回滚到上一版", 30), "点不到「回滚」按钮"
    assert sc.wait_text("回滚到上一版？", 15), "回滚确认框未出现"
    assert sc.tap_text("开始回滚", 15), "点不到「开始回滚」"
    result = wait_msg(["已回滚", "✗"], 600)
    r["result"] = result
    r["screenshot_result"] = screencap("r8-j2-result.png")
    assert result and "已回滚" in result, f"回滚未完成：{result}"
    r["version_back"] = manifest_version() == OLD_VER
    assert r["version_back"], f"回滚后版本异常：{manifest_version()}"

    # 反向迁移带回升级后新改动（判据 2）；同名文件（feature.txt）保用户的新版本
    feat = rootfs_read("root/r8-ws/feature.txt")
    r["feature_merged_user_wins"] = "feature-line-1" in feat and "feature-line-2" in feat
    r["post_file_kept"] = rootfs_read("root/r8-post.txt").strip() == "post-upgrade-note"
    r["new_conf_kept"] = rootfs_read("root/.config/app.d/new-key.conf").strip() == "k=v"
    r["second_kept"] = rootfs_read("root/r8-ws/second.txt").strip() == "ws2-data"
    bashrc = rootfs_read("root/.bashrc")
    r["bashrc_still_user"] = "R8_USER=1" in bashrc
    assert r["feature_merged_user_wins"] and r["post_file_kept"] and r["new_conf_kept"], \
        f"升级后新改动丢失：feat={feat[:60]} post={r['post_file_kept']} conf={r['new_conf_kept']}"
    assert r["second_kept"] and r["bashrc_still_user"], "回滚迁移不全"

    # 系统层随旧树回退：新版标记消失、sl/htop（升级前自装在旧树里）随旧树回来
    r["new_marker_gone"] = not rootfs_exists("etc/r8-release")
    back = sc.env_read("ls /usr/games/sl >/dev/null 2>&1 && echo SL_BACK; "
                       "command -v htop >/dev/null && echo HTOP_BACK; "
                       "command -v node >/dev/null && echo NODE_BACK; echo E2\n", timeout=120)
    r["old_pkgs_back"] = "SL_BACK" in back and "HTOP_BACK" in back
    r["node_back"] = "NODE_BACK" in back
    assert r["new_marker_gone"] and r["old_pkgs_back"] and r["node_back"], \
        f"系统层未随旧树回退：{back[:150]}"
    r["prev_is_new_ver"] = True
    prev = subprocess.run(adb_prefix() + ["exec-out", "run-as", PKG, "cat",
                                          "files/ubuntu-rootfs-prev/.drydock-manifest"],
                          capture_output=True, timeout=60).stdout.decode()
    r["prev_manifest"] = prev.strip().replace("\n", " ")[:60]
    assert f"version={NEW_VER}" in prev, f"prev 应为新版：{prev[:60]}"

    r["pass"] = all([r["version_back"], r["feature_merged_user_wins"], r["post_file_kept"],
                     r["new_conf_kept"], r["second_kept"], r["bashrc_still_user"],
                     r["new_marker_gone"], r["old_pkgs_back"], r["node_back"],
                     f"version={NEW_VER}" in prev])


# ---------- j3 sha256 拒绝（判据 3） ----------

@stage("j3_sha256_rejected_env_alive")
def j3(r):
    # 构造「体积对、校验和错」的新版：下载步按体积跳过 → 校验步拒绝
    blob = os.urandom(65536)
    open("/tmp/r8-bad.tar.gz", "wb").write(blob)
    push_to_files("/tmp/r8-bad.tar.gz", f"cache/{BAD_TAR}")
    wrong_sha = hashlib.sha256(blob).hexdigest()
    wrong_sha = ("0" if wrong_sha[0] != "0" else "1") + wrong_sha[1:]
    write_index([{"version": BAD_VER, "fileName": BAD_TAR, "sizeBytes": len(blob),
                  "sha256": wrong_sha, "mirrors": ["https://updates.invalid/r8/"]}])
    r["bad_index_written"] = True

    nav_settings_update()
    assert sc.tap_text("检查更新", 30), "点不到「检查更新」"
    assert sc.wait_text(f"发现新版本 {BAD_VER}", 60), "未发现构造的坏版本"
    assert sc.tap_text(f"升级到 {BAD_VER}", 20), "点不到「升级」按钮"
    assert sc.tap_text("开始升级", 15), "点不到「开始升级」"
    result = wait_msg(["sha256", "失败"], 180)
    r["result"] = result
    r["screenshot_reject"] = screencap("r8-j3-reject.png")
    assert result and "sha256 不符" in result and "拒绝" in result, f"未按 sha256 拒绝：{result}"
    r["version_unchanged"] = manifest_version() == OLD_VER
    # 坏包应被清出 cache（拒绝路径删 tarball；错误走 stderr，须 2>&1 才可见）
    ls = shell_q(f"run-as {PKG} ls cache/{BAD_TAR} 2>&1")
    r["bad_tar_deleted"] = "No such file" in ls
    assert r["version_unchanged"] and r["bad_tar_deleted"], \
        f"拒绝后状态异常：ver={manifest_version()} tar={ls.strip()[:60]}"

    # 旧环境继续可用：环境内命令可跑 + 真能新建会话并跑命令（判据 3）
    r["env_alive_probe"] = "OK" in sc.env_read(
        "command -v ttyd >/dev/null && command -v bash >/dev/null && echo OK; echo V=$(head -1 /.drydock-manifest)\n",
        timeout=120)
    assert r["env_alive_probe"], "拒绝后环境内命令不可用"

    sc.shell("am", "start", "-S", "-n", HOME)
    assert sc.wait_text("新建会话", 60), "HomeActivity 未出现"
    assert sc.tap_text("新建会话", 30), "点不到「新建会话」"
    assert sc.wait_text("会话名称", 30), "新建对话框未出现"
    assert sc.tap_text("创建", 30), "点不到「创建」"
    deadline = time.time() + 240
    while time.time() < deadline:
        if "TerminalActivity" in focus():
            break
        time.sleep(3)
    r["new_session_focus"] = "TerminalActivity" in focus()
    assert r["new_session_focus"], "拒绝后建不了新会话"
    assert sc.cdp_forward(), "CDP forward 失败"
    txt = term_run("echo R8_ALIVE_OK && uname -m", "R8_ALIVE_OK")
    r["alive_cmd_tail"] = txt[-160:]
    r["command_ran"] = "R8_ALIVE_OK" in txt and "aarch64" in txt
    assert r["command_ran"], f"会话内命令未执行：{r['alive_cmd_tail']}"

    r["pass"] = all([r["version_unchanged"], r["bad_tar_deleted"], r["env_alive_probe"],
                     r["new_session_focus"], r["command_ran"]])


# ---------- j5 升级+回滚后功能不回退（判据 5） ----------

@stage("j5_session_e2e_after_all")
def j5(r):
    r["version_now"] = manifest_version()
    assert r["version_now"] == OLD_VER, f"终态版本应为 {OLD_VER}：{r['version_now']}"
    sc.shell("am", "start", "-S", "-n", HOME)
    assert sc.wait_text("新建会话", 60), "HomeActivity 未出现"
    assert sc.tap_text("新建会话", 30), "点不到「新建会话」"
    assert sc.wait_text("会话名称", 30), "新建对话框未出现"
    assert sc.tap_text("创建", 30), "点不到「创建」"
    deadline = time.time() + 240
    while time.time() < deadline:
        if "TerminalActivity" in focus():
            break
        time.sleep(3)
    r["terminal_focus"] = "TerminalActivity" in focus()
    assert r["terminal_focus"], "升级+回滚后新建会话失败"

    # 提示符渲染（xterm DOM 文本）→ CDP 打字运行命令（判据 5）
    assert sc.cdp_forward(), "CDP forward 失败"
    txt = term_text()
    r["prompt_rendered"] = "root@" in txt
    r["prompt_sample"] = txt[-160:]
    assert r["prompt_rendered"], f"提示符未渲染：{r['prompt_sample']}"
    r["screenshot_terminal"] = screencap("r8-j5-terminal.png")

    txt = term_run("echo R8FINAL && head -1 /etc/os-release", "R8FINAL")
    r["command_tail"] = txt[-200:]
    r["command_ran"] = "R8FINAL" in txt and "Ubuntu" in txt
    assert r["command_ran"], f"命令未执行：{r['command_tail']}"

    # 收尾卫生：撤掉坏索引与坏包残留 + 清掉会毒后续夜批的测试装置
    # （.l2s.fake 会让环境内 GNU tar EPERM——t9 实锤；其余为黑名单装置）
    sc.shell("run-as", PKG, "rm", "-f", "files/rootfs-updates.json", f"cache/{BAD_TAR}")
    sc.env_read("rm -f /root/.l2s.fake /root/r8-session.sock /root/AndroidDownload/note.txt "
                "/root/.cache/blob /root/.npm/_cacache; echo CLEANED\n", timeout=120)
    r["cleaned"] = True
    r["pass"] = all([r["terminal_focus"], r["prompt_rendered"], r["command_ran"], r["version_now"] == OLD_VER])


def main():
    wanted = sys.argv[1:] or ["p0", "j1", "j2", "j3", "j5"]
    runners = {"p0": p0, "j1": j1, "j2": j2, "j3": j3, "j5": j5}
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
