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
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scommon as sc

HOME = f"{sc.PKG}/.HomeActivity"
VERDICT = os.path.join(sc.DRAFT, "i18n-sample-verdict.json")

# 每页族至少一个代表键（tag → 各 locale 期望渲染文本）
# HOME = 主页直接可见；DLG = 新建对话框内；SETTINGS = 设置页（进 nav_settings 后）
SAMPLES = {
    "en": {
        "HOME": {"nav_sessions": "Sessions", "nav_files": "Files", "nav_settings": "Settings",
                 "home_new_session": "New session"},
        "DLG": {"dlg_create": "Create"},
        "SETTINGS": {"settings_appearance": "Appearance", "theme_light": "Light",
                     "theme_system": "Follow system"},
        "WIZARD": {"wizard_step_welcome": "Welcome to Drydock", "wizard_start": "Start setup"},
        "FILES": {"file_page_title": "Files"},
    },
    "zh": {
        "HOME": {"nav_sessions": "会话", "nav_files": "文件", "nav_settings": "设置",
                 "home_new_session": "新建会话"},
        "DLG": {"dlg_create": "创建"},
        "SETTINGS": {"settings_appearance": "外观", "theme_light": "浅色",
                     "theme_system": "跟随系统"},
        "WIZARD": {"wizard_step_welcome": "欢迎使用 Drydock", "wizard_start": "开始配置"},
        "FILES": {"file_page_title": "文件"},
    },
}


def main():
    locale = sys.argv[1] if len(sys.argv) > 1 else ""
    if locale not in SAMPLES:
        sys.exit("用法：i18n-sample.py en|zh（locale 由运行者先经 cmd locale 设置）")
    expect = SAMPLES[locale]
    r = {"locale": locale, "device": sc.device_identity(), "checks": {}}

    sc.shell("am", "start", "-S", "-n", HOME)
    if not sc.wait_res("home_new_session", 60):
        sys.exit("主页未出现（home_new_session）")

    def check(tag, layer):
        exp = expect[layer][tag]
        got = sc.res_text(tag)
        r["checks"][tag] = {"expect": exp, "got": got}
        print(f"  {tag}: expect={exp!r} got={got!r}")

    # 主页层
    for tag in expect["HOME"]:
        check(tag, "HOME")

    # 新建对话框层（打开→断言→收起）
    if sc.tap_res("home_new_session", 15) and sc.wait_res("dlg_create", 15):
        for tag in expect["DLG"]:
            check(tag, "DLG")
        sc.shell("input", "keyevent", "KEYCODE_BACK")
    else:
        r["checks"]["dlg_create"] = {"expect": expect["DLG"].get("dlg_create"), "got": "对话框未打开"}

    # 设置页层（外观行 → 外观二级页选项）
    if sc.tap_res("nav_settings", 15) and sc.wait_res("settings_appearance", 15):
        if "settings_appearance" in expect["SETTINGS"]:
            check("settings_appearance", "SETTINGS")
        if sc.tap_res("settings_appearance", 15) and sc.wait_res("theme_light", 15):
            for tag in expect["SETTINGS"]:
                if tag != "settings_appearance":
                    check(tag, "SETTINGS")
            sc.shell("input", "keyevent", "KEYCODE_BACK")
            time.sleep(1)

    # 绑定页层（设置页入口 → bind_switch 在场即页可达；开关状态依环境不判值）
    if sc.res_hit("settings_bind") or (sc.tap_res("nav_settings", 10) and sc.wait_res("settings_bind", 15)):
        if sc.tap_res("settings_bind", 15):
            r["checks"]["bind_page"] = {"expect": "present",
                                        "got": "present" if sc.wait_res("bind_switch", 10) else "absent"}
            print(f"  bind_page: {r['checks']['bind_page']['got']}")
            sc.shell("input", "keyevent", "KEYCODE_BACK")
            time.sleep(1)

    # 文件页层（批 8 样本：进页靠 nav_files tag，页标题文案断言走 wait_text——
    # 本层检验的正是渲染文本本身）
    if "FILES" in expect and sc.tap_res("nav_files", 15):
        r["checks"]["file_page_title"] = {"expect": expect["FILES"]["file_page_title"],
                                          "got": expect["FILES"]["file_page_title"] if sc.wait_text(expect["FILES"]["file_page_title"], 10) else None}
        print(f"  file_page_title: {r['checks']['file_page_title']}")
        sc.tap_res("nav_sessions", 10)

    # 向导层（设置页入口 → 欢迎页 → 左上返回退出）
    if "WIZARD" in expect:
        if not sc.res_hit("settings_wizard") and sc.tap_res("nav_settings", 10):
            sc.wait_res("settings_wizard", 15)
        if sc.tap_res("settings_wizard", 15) and sc.wait_res("wizard_step_welcome", 20):
            for tag in expect["WIZARD"]:
                check(tag, "WIZARD")
            sc.tap_res("wizard_back", 10)
            time.sleep(1)

    r["pass"] = all(c["got"] == c["expect"] for c in r["checks"].values())
    os.makedirs(sc.DRAFT, exist_ok=True)
    json.dump(r, open(VERDICT, "w"), ensure_ascii=False, indent=2)
    print(f"====> sample[{locale}] pass={r['pass']}，verdict: {VERDICT}")
    sys.exit(0 if r["pass"] else 1)


if __name__ == "__main__":
    main()
