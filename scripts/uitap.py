#!/usr/bin/env python3
"""按文本找 UI 元素并点击：scripts/uitap.py <关键字> [--dump-only]
依赖 uiautomator dump（模拟器/调试设备）。退出码 0=已点击，2=未找到。"""
import re
import subprocess
import sys
import time

ADB = "/Users/scliang/Library/Android/sdk/platform-tools/adb"


def sh(*args, **kw):
    return subprocess.run(args, capture_output=True, text=True, **kw)


def dump():
    sh(f"{ADB}", "shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return sh(f"{ADB}", "shell", "cat", "/sdcard/ui.xml").stdout


def main():
    key = sys.argv[1]
    xml = dump()
    pat = re.compile(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    for m in pat.finditer(xml):
        if key in m.group(1):
            x = (int(m.group(2)) + int(m.group(4))) // 2
            y = (int(m.group(3)) + int(m.group(5))) // 2
            print(f"tap '{m.group(1)}' @ {x},{y}")
            sh(f"{ADB}", "shell", "input", "tap", str(x), str(y))
            sys.exit(0)
    print(f"not found: {key}", file=sys.stderr)
    # 打印可见文本帮助诊断
    for m in pat.finditer(xml):
        if m.group(1).strip():
            print(f"  visible: {m.group(1)[:60]} @{m.group(2)},{m.group(3)}", file=sys.stderr)
    sys.exit(2)


if __name__ == "__main__":
    main()
