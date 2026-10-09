#!/usr/bin/env python3
"""i18n 抽样剧本（批 3 建）：验证本地化键在当前 app locale 下渲染正确。

i18n 批次的第三层验收（CI lint 保键集、night-b 保逻辑、本剧本保渲染文本）：
按 resource-id 锚点取节点 text，与当前 locale 的期望对照——它抓的正是键集齐了
但代码漏改 stringResource 的漏网（英文界面看到中文即现形）。

不切 locale（由运行者控制：cmd locale set-app-locales <pkg> --locales zh-CN /
--locales empty 即回落系统语言）；只断言"当前 locale 下渲染 == 期望"。
文案迁移批次往 SAMPLES 增行即可（每页族至少一个代表键）。

用法：ANDROID_SERIAL=emulator-5554 python3 scripts/i18n-sample.py en|zh
输出：draft/i18n-sample-verdict.json。只动模拟器（真机纪律同 night-b）。
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scommon as sc

HOME = f"{sc.PKG}/.HomeActivity"
VERDICT = os.path.join(sc.DRAFT, "i18n-sample-verdict.json")

# 每页族至少一个代表键（tag → 各 locale 期望渲染文本）
SAMPLES = {
    "en": {
        "nav_sessions": "Sessions",
        "nav_files": "Files",
        "nav_settings": "Settings",
        "home_new_session": "New session",
        # 对话框层（批 2 公共键 + 批 3 会话键）
        "dlg_create": "Create",
    },
    "zh": {
        "nav_sessions": "会话",
        "nav_files": "文件",
        "nav_settings": "设置",
        "home_new_session": "新建会话",
        "dlg_create": "创建",
    },
}


def main():
    locale = sys.argv[1] if len(sys.argv) > 1 else ""
    if locale not in SAMPLES:
        sys.exit("用法：i18n-sample.py en|zh（locale 由运行者先经 cmd locale 设置）")
    expect = SAMPLES[locale]
    r = {"locale": locale, "device": sc.device_identity(), "checks": {}, "app_locale": sc.device_identity()["locale"]}

    sc.shell("am", "start", "-S", "-n", HOME)
    if not sc.wait_res("home_new_session", 60):
        sys.exit("主页未出现（home_new_session）")

    # 主页层样本
    for tag in ("nav_sessions", "nav_files", "nav_settings", "home_new_session"):
        got = sc.res_text(tag)
        r["checks"][tag] = {"expect": expect.get(tag), "got": got}
        print(f"  {tag}: expect={expect.get(tag)!r} got={got!r}")

    # 新建对话框层样本（打开→断言→收起）
    if sc.tap_res("home_new_session", 15) and sc.wait_res("dlg_create", 15):
        for tag in ("dlg_create",):
            got = sc.res_text(tag)
            r["checks"][tag] = {"expect": expect[tag], "got": got}
            print(f"  {tag}: expect={expect[tag]!r} got={got!r}")
        sc.shell("input", "keyevent", "KEYCODE_BACK")
    else:
        r["checks"]["dlg_create"] = {"expect": expect["dlg_create"], "got": "对话框未打开"}

    r["pass"] = all(c["got"] == c["expect"] for c in r["checks"].values())
    os.makedirs(sc.DRAFT, exist_ok=True)
    json.dump(r, open(VERDICT, "w"), ensure_ascii=False, indent=2)
    print(f"====> sample[{locale}] pass={r['pass']}，verdict: {VERDICT}")
    sys.exit(0 if r["pass"] else 1)


if __name__ == "__main__":
    main()
