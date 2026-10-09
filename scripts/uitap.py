#!/usr/bin/env python3
"""按锚点找 UI 元素并点击：scripts/uitap.py <关键字> | --res <tag> [--dump-only]
文本模式：text 精确 > text 包含 > content-desc（uiautomator dump，模拟器/调试设备）。
--res 模式（i18n 批 1）：按 resource-id 精确定位（Compose testTag / View setId 映射），
语言无关——文案迁移（i18n 批 3+）不影响定位。退出码 0=已点击，2=未找到。
多设备在线时须 ANDROID_SERIAL=<serial> 指定目标（真机纪律，经 scripts/adbdev.py）。"""
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from adbdev import adb_prefix

# dump 落 /data/local/tmp（真机纪律：不写 /sdcard）
DUMP = "/data/local/tmp/uitap-ui.xml"

PKG = "dev.drydock.prototype"


def sh(*args, **kw):
    return subprocess.run(args, capture_output=True, text=True, **kw)


def dump():
    sh(*adb_prefix(), "shell", "uiautomator", "dump", DUMP)
    return sh(*adb_prefix(), "shell", "cat", DUMP).stdout


def tap_res(tag):
    xml = dump()
    # Compose testTag 映射为裸 tag、View setId 为全限定 pkg:id/（双形态都认）
    pat = re.compile(
        r'<node\b[^>]*?resource-id="(?:' + re.escape(PKG) + r':id/)?' + re.escape(tag) +
        r'"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    hit = pat.search(xml)
    if not hit:
        print(f"not found by res: {tag}", file=sys.stderr)
        return False
    x = (int(hit.group(1)) + int(hit.group(3))) // 2
    y = (int(hit.group(2)) + int(hit.group(4))) // 2
    print(f"tap res {tag} @ {x},{y}")
    sh(*adb_prefix(), "shell", "input", "tap", str(x), str(y))
    return True


def main():
    args = sys.argv[1:]
    if args and args[0] == "--res":
        sys.exit(0 if tap_res(args[1]) else 2)
    key = args[0]
    xml = dump()
    pat = re.compile(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    desc = re.compile(r'content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    exact = None
    contains = None
    for m in pat.finditer(xml):
        if key == m.group(1) and exact is None:
            exact = m
        if key in m.group(1) and contains is None:
            contains = m
    # 2026-10-08 浮钮合并后图标化（无 text 只有 content-desc）：desc 作为兜底，
    # text 命中优先（正文文案可能只「包含」关键词且点文案才有效，见下）
    for m in desc.finditer(xml):
        if key == m.group(1) and exact is None:
            exact = m
        if key in m.group(1) and contains is None:
            contains = m
    # 精确匹配优先：正文文案可能只「包含」关键词（如教育文案含「新建会话」、
    # 会话卡片副标题含「本地端口」），点文案无效果（2026-10-07 t1 实测踩中）
    hit = exact or contains
    if hit:
        x = (int(hit.group(2)) + int(hit.group(4))) // 2
        y = (int(hit.group(3)) + int(hit.group(5))) // 2
        print(f"tap '{hit.group(1)}' @ {x},{y} ({'exact' if hit is exact else 'contains'})")
        sh(*adb_prefix(), "shell", "input", "tap", str(x), str(y))
        sys.exit(0)
    print(f"not found: {key}", file=sys.stderr)
    # 打印可见文本帮助诊断
    for m in pat.finditer(xml):
        if m.group(1).strip():
            print(f"  visible: {m.group(1)[:60]} @{m.group(2)},{m.group(3)}", file=sys.stderr)
    sys.exit(2)


if __name__ == "__main__":
    main()
