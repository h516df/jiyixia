#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""交办（cn.jiaoban）安卓端真实验收。

真实路径：构建 APK -> 装到模拟器 -> 塞入几条真记录 -> 打开 App 看真实界面 ->
按用户会做的动作点击 -> 每一步截图留证。

断言只认用户看得见的东西：文字在不在、分档对不对、杀掉进程重开数据还在不在。
用法：
    python scripts/android_acceptance.py            # 构建 + 装 + 验收
    python scripts/android_acceptance.py --no-build # 用现成 APK
"""
import argparse
import datetime as dt
import json
import re
import subprocess
import sys
import time
from pathlib import Path
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "verification"
APK = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
ADB = r"D:\Apps\Android\sdk\platform-tools\adb.exe"
PKG = "cn.jiaoban"
ACTIVITY = "cn.jiaoban/.MainActivity"
MARKER = "uiadd1"

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    line = ("PASS  " if ok else "FAIL  ") + name
    if detail:
        line += " | " + detail
    print(line, flush=True)


def run(argv, stdin_bytes=None, binary=False, timeout=600):
    proc = subprocess.run(
        argv, input=stdin_bytes, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout
    )
    return proc.stdout if binary else proc.stdout.decode("utf-8", "replace")


def adb(*args, **kw):
    return run([ADB, "-s", SERIAL, *args], **kw)


def dump_ui(tag):
    adb("shell", "uiautomator", "dump", "/sdcard/jiaoban-ui.xml")
    xml = adb("exec-out", "cat", "/sdcard/jiaoban-ui.xml")
    (OUT / ("ui-%s.xml" % tag)).write_text(xml, encoding="utf-8")
    return xml


def visible(xml):
    """界面上所有可见文字和内容描述。"""
    out = set()
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return out
    for node in root.iter():
        for key in ("text", "content-desc"):
            value = (node.get(key) or "").strip()
            if value:
                out.add(value)
    return out


def center_of(xml, needle):
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return None
    for node in root.iter():
        for key in ("text", "content-desc"):
            if needle in (node.get(key) or ""):
                m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
                if m:
                    x1, y1, x2, y2 = (int(v) for v in m.groups())
                    return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def tap_at(point):
    adb("shell", "input", "tap", str(point[0]), str(point[1]))
    return point


def tap_text(xml, needle):
    point = center_of(xml, needle)
    if point is None:
        return None
    return tap_at(point)


def shot(name):
    data = adb("exec-out", "screencap", "-p", binary=True)
    (OUT / name).write_bytes(data)
    return len(data) > 20000


def type_keys(text):
    """用按键事件输入，绕开模拟器输入法偶发的首字符重复。"""
    for ch in text:
        if ch == "-":
            key = "KEYCODE_MINUS"
        elif ch.isdigit():
            key = "KEYCODE_" + ch
        elif ch.isalpha():
            key = "KEYCODE_" + ch.upper()
        else:
            continue
        adb("shell", "input", "keyevent", key)
        time.sleep(0.12)


def clear_keys(times=30):
    for _ in range(times):
        adb("shell", "input", "keyevent", "KEYCODE_DEL")
    time.sleep(0.4)


def wait_for_app(timeout=20.0):
    """等到 App 真的画出来为止：dump 里能看到标题「交办」才算。"""
    deadline = time.time() + timeout
    xml = ""
    while time.time() < deadline:
        xml = dump_ui("wait")
        if "交办" in visible(xml):
            return xml
        time.sleep(0.8)
    return xml


def launch():
    """彻底杀掉进程再开，等界面真的画出来。模拟器上 force-stop 是异步的，
    不等它生效就会把上一个进程的界面当成新界面。"""
    adb("shell", "am", "force-stop", PKG)
    for _ in range(20):
        if not adb("shell", "pidof", PKG).strip():
            break
        time.sleep(0.3)
    adb("shell", "am", "start", "-n", ACTIVITY)
    time.sleep(1.5)
    return wait_for_app()


def seed_records():
    today = dt.date.today()
    now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")

    def day(offset):
        return (today + dt.timedelta(days=offset)).isoformat()

    items = [
        {"id": "seed-today", "text": "今天要交的周报", "from": "王总", "due": day(0), "state": "OPEN",
         "createdAt": now, "closedAt": None},
        {"id": "seed-week", "text": "下周的预算表", "from": "李姐", "due": day(5), "state": "OPEN",
         "createdAt": now, "closedAt": None},
        {"id": "seed-ascii", "text": "ABC-123 的临时事", "from": "系统", "due": day(2), "state": "OPEN",
         "createdAt": now, "closedAt": None},
        {"id": "seed-overdue", "text": "早就过期的报销单", "from": "王总", "due": day(-4), "state": "OPEN",
         "createdAt": now, "closedAt": None},
        {"id": "seed-nodue", "text": "没定期限的杂事", "from": "张工", "due": None, "state": "OPEN",
         "createdAt": now, "closedAt": None},
        {"id": "seed-done", "text": "已经办完的旧事", "from": "王总", "due": day(-1), "state": "DONE",
         "createdAt": now, "closedAt": now},
    ]
    payload = json.dumps(items, ensure_ascii=False).encode("utf-8")
    adb("shell", "run-as %s sh -c 'cat > files/tasks.json'" % PKG, stdin_bytes=payload)
    return {item["text"] for item in items}


def build_apk():
    print("== 构建 APK ==", flush=True)
    out = run([str(ROOT / "gradlew.bat"), "-p", str(ROOT), ":app:assembleDebug", "--console=plain"],
              timeout=2400)
    print(out[-3000:], flush=True)
    return APK.is_file()


def pick_serial():
    out = run([ADB, "devices"])
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device" and parts[0].startswith("emulator-"):
            return parts[0]
    return None


def main():
    global SERIAL
    parser = argparse.ArgumentParser()
    parser.add_argument("--no-build", action="store_true", help="用现成 APK，不重新构建")
    parser.add_argument("--serial", default="")
    args = parser.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)

    if not args.no_build:
        if not build_apk():
            check("构建 APK", False, "assembleDebug 没产出 " + str(APK))
            return 1
    check("构建 APK", APK.is_file(), str(APK))

    SERIAL = args.serial or pick_serial()
    if not SERIAL:
        check("找到模拟器", False, "adb 里没有 emulator-* 设备")
        return 1
    check("找到模拟器", True, SERIAL)

    install = adb("install", "-r", str(APK))
    check("安装到设备", "Success" in install, install.strip().splitlines()[-1][:120])

    # 空状态（W7）：先把记录清掉再打开
    adb("shell", "run-as %s rm -f files/tasks.json files/tasks.json.bak" % PKG)
    xml = launch()
    screen = visible(xml)
    check("W7 空状态有说明、不是白屏", "还没有人交代事" in screen,
          "，".join(sorted(screen))[:200])
    check("截图 00-空状态", shot("00-空状态.png"))

    seeded = seed_records()
    xml = launch()
    screen = visible(xml)
    check("App 真的在前台（标题在）", "交办" in screen, "看到：" + "，".join(sorted(screen))[:200])
    check("截图 01-清单", shot("01-清单.png"))

    check("A1 今天要的事在主列表", "今天要交的周报" in screen)
    check("A1 这周的事在主列表", "下周的预算表" in screen)
    check("A1 没定期限的事在主列表", "没定期限的杂事" in screen)
    check("A2 过期的事不在主档里", "早就过期的报销单" not in screen)
    check("A2 已办的事不在主档里", "已经办完的旧事" not in screen)
    check("A2 底部有「已过期 1 件」", any("已过期" in t and "1" in t for t in screen),
          "，".join(sorted(t for t in screen if "过期" in t)))

    point = center_of(xml, "已过期")
    if point:
        tap_at(point)
        time.sleep(1.2)
        xml2 = dump_ui("02-过期区")
        screen2 = visible(xml2)
        check("A3 点开后看得到过期的事", "早就过期的报销单" in screen2)
        check("截图 02-过期区", shot("02-过期区.png"))
    else:
        check("A3 点开后看得到过期的事", False, "没找到「已过期」入口")

    xml = launch()
    fab = center_of(xml, "记一条")
    if fab is None:
        check("A4 找得到「记一条」按钮", False)
    else:
        check("A4 找得到「记一条」按钮", True)
        tap_at(fab)
        time.sleep(1.8)
        xml = dump_ui("04-记一条")
        if "什么事" not in visible(xml):
            # 偶发：点在动画中间没吃上，再点一次
            again = center_of(xml, "记一条") or fab
            tap_at(again)
            time.sleep(1.8)
            xml = dump_ui("04-记一条")
        check("截图 04-记一条", shot("04-记一条.png"))
        check("A4 记一条的表单打开了", "什么事" in visible(xml), "，".join(sorted(visible(xml)))[:200])
        type_keys(MARKER)
        time.sleep(1.2)
        # 键盘弹起会把「记下来」往上顶，必须用弹起之后的坐标再点
        xml = dump_ui("04b-填好了")
        saved = tap_text(xml, "记下来")
        time.sleep(2.0)
        xml = dump_ui("05-记好之后")
        check("A4 新记的一条出现在列表里", MARKER in visible(xml), "点了保存：" + str(saved))
        check("截图 05-记好之后", shot("05-记好之后.png"))

    xml = launch()
    check("A5 杀掉进程重开，新记的还在", MARKER in visible(xml))
    check("A5 重开后原有记录也还在", "今天要交的周报" in visible(xml))
    check("截图 06-重开之后", shot("06-重开之后.png"))

    if tap_text(xml, "历史") is not None:
        time.sleep(1.2)
        xml = dump_ui("07-历史")
        check("A6 历史里有办完的事", "已经办完的旧事" in visible(xml))
        check("截图 07-历史", shot("07-历史.png"))
        back = center_of(xml, "回到清单")
        if back:
            tap_at(back)
            time.sleep(1.0)
    else:
        check("A6 历史里有办完的事", False, "没找到历史入口")

    xml = dump_ui("08-回到清单")
    if tap_text(xml, "搜索") is not None:
        time.sleep(1.5)
        type_keys("ABC-123")
        time.sleep(1.2)
        xml = dump_ui("09-搜索")
        check("截图 09-搜索", shot("09-搜索.png"))
        hits = visible(xml)
        check("A7 搜索能翻出记录（输入 ABC-123）", "ABC-123 的临时事" in hits,
              "，".join(sorted(hits))[:200])

        clear_keys()
        type_keys("zzz")
        time.sleep(1.2)
        xml = dump_ui("10-搜不到")
        hits = visible(xml)
        check("A7 搜不到时给的是「没找到」", any("没找到" in t for t in hits),
              "，".join(sorted(hits))[:200])
        check("截图 10-搜不到", shot("10-搜不到.png"))
    else:
        check("A7 搜索入口在", False)

    failed = [r for r in RESULTS if not r[1]]
    print("\n==== 汇总：%d 项，失败 %d 项 ====" % (len(RESULTS), len(failed)), flush=True)
    for name, _ok, detail in failed:
        print("  FAIL " + name + ((" | " + detail) if detail else ""), flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
