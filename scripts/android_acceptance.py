#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""交办（cn.jiaoban）安卓端真实验收。

真实路径：构建 APK -> 装到模拟器 -> 用真实入口记事情 -> 打开 App 看真实界面 ->
按用户会做的动作点击 -> 每一步截图留证。

断言只认用户看得见的东西：文字在不在、认人和认期限对不对、过期有没有退场、
杀掉进程重开数据还在不在。

用法：
    python scripts/android_acceptance.py            # 构建 + 装 + 验收
    python scripts/android_acceptance.py --no-build # 用现成 APK
"""
import argparse
import os
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
MARKER = "abc123"

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
    return run([ADB, "-P", os.environ.get("JIAOBAN_ADB_PORT", "5037"), "-s", SERIAL, *args], **kw)


def dump_ui(tag):
    adb("shell", "rm", "-f", "/sdcard/jiaoban-ui.xml")
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


def scroll_until(xml, needle):
    """按真实视口滚动到列表下方，而不是假定全部卡片同时可见。"""
    for attempt in range(6):
        if center_of(xml, needle) is not None:
            return xml
        root = ET.fromstring(xml)
        bounds = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", next((n for n in root.iter() if n.get("scrollable") == "true" and n.get("class") != "android.widget.EditText"), root[0]).get("bounds", ""))
        if not bounds:
            return xml
        left, top, right, bottom = map(int, bounds.groups())
        x = (left + right) // 2
        height = bottom - top
        adb("shell", "input", "swipe", str(x), str(top + height * 7 // 10),
            str(x), str(top + height * 3 // 10), "450")
        time.sleep(.5)
        xml = dump_ui("scroll-%s-%d" % (needle, attempt))
    return xml


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
    """等待输入焦点稳定后，整段注入已知 ASCII 测试输入。"""
    time.sleep(1.5)
    adb("shell", "input", "text", text)
    time.sleep(.5)


def clear_keys(times=30):
    for _ in range(times):
        adb("shell", "input", "keyevent", "KEYCODE_DEL")
    time.sleep(0.4)


def wait_for_app(timeout=45.0):
    """等到 App 真的画出来为止。
    只看到「交办」不算：读盘时标题栏也在，要等输入条出现（说明已经读完盘）。
    模拟器在内存紧张时会变慢，所以超时给宽一点，避免把「慢」判成「坏」。"""
    deadline = time.time() + timeout
    xml = ""
    while time.time() < deadline:
        xml = dump_ui("wait")
        seen = visible(xml)
        if any("谁交代了什么" in t for t in seen):
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


def share_text(text):
    """模拟从微信分享一句话进来。"""
    adb(
        "shell", "am", "start",
        "-a", "android.intent.action.SEND",
        "-t", "text/plain",
        "--es", "android.intent.extra.TEXT", text,
        "-n", ACTIVITY,
    )
    time.sleep(3.0)


def seed_records():
    # 种子文件只能在旧进程退出后写，否则旧的后台落盘会盖回旧数据。
    adb("shell", "am", "force-stop", PKG)
    for _ in range(30):
        if not adb("shell", "pidof", PKG).strip():
            break
        time.sleep(.2)
    today = dt.date.today()
    now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")

    def day(offset):
        return (today + dt.timedelta(days=offset)).isoformat()

    items = [
        {"id": "seed-today", "text": "今天要交的周报", "from": "王总", "due": day(0), "state": "OPEN",
         "createdAt": now, "closedAt": None},
        {"id": "seed-week", "text": "下周的预算表", "from": "李姐", "due": day(5), "state": "OPEN",
         "createdAt": now, "closedAt": None},
        {"id": "seed-overdue", "text": "早就过期的报销单", "from": "王总", "due": day(-4), "state": "OPEN",
         "createdAt": now, "closedAt": None},
        {"id": "seed-ascii", "text": "ABC-123 的临时事", "from": "系统", "due": day(2), "state": "OPEN",
         "createdAt": now, "closedAt": None},
        {"id": "seed-nodue", "text": "没定期限的杂事", "from": "张工", "due": None, "state": "OPEN",
         "createdAt": now, "closedAt": None},
        {"id": "seed-done", "text": "已经办完的旧事", "from": "王总", "due": day(-1), "state": "DONE",
         "createdAt": now, "closedAt": now},
    ]
    payload = json.dumps(items, ensure_ascii=False).encode("utf-8")
    adb("shell", "run-as %s sh -c 'cat > files/tasks.json'" % PKG, stdin_bytes=payload)


def build_apk():
    print("== 构建 APK ==", flush=True)
    out = run([str(ROOT / "gradlew.bat"), "-p", str(ROOT), ":app:assembleDebug", "--console=plain"],
              timeout=2400)
    print(out[-2000:], flush=True)
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

    if adb("shell", "getprop", "ro.boot.qemu.avd_name").strip() != "Jiaoban_Codex_API36":
        parser.error("Only use the task's disposable Jiaoban_Codex_API36 emulator; this test replaces fixture records.")
    install = adb("install", "-r", str(APK))
    check("安装到设备", "Success" in install, install.strip().splitlines()[-1][:120])

    # ---------- 空状态（W7） ----------
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "run-as %s rm -f files/tasks.json files/tasks.json.bak" % PKG)
    xml = launch()
    screen = visible(xml)
    check("W7 空状态有说明、不是白屏", "还没有人交代事" in screen, "，".join(sorted(screen))[:200])
    check("截图 00-空状态", shot("00-空状态.png"))

    # ---------- W1/W2/W3 一句话进来，人和期限自己认 ----------
    share_text("王总说明天要周报")
    xml = wait_for_app()
    screen = visible(xml)
    check("W1 分享进来的那句话记下了", "王总说明天要周报" in screen,
          "，".join(sorted(screen))[:240])
    check("W2 认出了交代人", any(t.startswith("王总 · ") for t in screen),
          "，".join(sorted(t for t in screen if "·" in t)))
    check("W3 认出了期限（明天）", any("明天要" in t for t in screen),
          "，".join(sorted(t for t in screen if "·" in t)))
    check("截图 11-分享进来的一句话", shot("11-分享进来的一句话.png"))

    # 长消息的尾巴和末尾期限不能静默消失（Issue #4）。
    long_text = "李姐说" + "活动规则与客户要求，" * 45 + "尾巴记号ZZZ，明天要提交汇总"
    share_text(long_text)
    deadline = time.monotonic() + 10
    saved_long = []
    while time.monotonic() < deadline:
        payload = adb("exec-out", "run-as", PKG, "cat", "files/tasks.json")
        try:
            saved_long = [item for item in json.loads(payload) if "尾巴记号ZZZ" in item.get("text", "")]
        except json.JSONDecodeError:
            saved_long = []
        if saved_long:
            break
        time.sleep(.3)
    check("W13 长分享全文和尾巴完整落盘", len(saved_long) == 1 and saved_long[0]["text"] == long_text)
    check("W13 长分享仍识别人和末尾期限", bool(saved_long) and saved_long[0]["from"] == "李姐" and
          saved_long[0]["due"] == (dt.date.today() + dt.timedelta(days=1)).isoformat())
    launch()
    payload = adb("exec-out", "run-as", PKG, "cat", "files/tasks.json")
    check("W13 重启后长分享仍完整", any(item.get("text") == long_text for item in json.loads(payload)))

    # ---------- W1/W3 输入框记一条，没提期限走默认 ----------
    xml = dump_ui("12-输入框前")
    tap_text(xml, "谁交代了什么") or tap_at((540, 300))
    time.sleep(0.8)
    type_keys(MARKER)
    time.sleep(0.8)
    xml = dump_ui("12b-输入框填好")
    saved = tap_text(xml, "记下")
    time.sleep(1.5)
    xml = wait_for_app()
    screen = visible(xml)
    check("W1 输入框里记的一条进了流水", MARKER in screen, "点了记下：" + str(saved))
    check("W3 没提期限就走默认 3 天", any("还有 3 天" in t for t in screen),
          "，".join(sorted(t for t in screen if "还有" in t)))
    check("截图 12-输入框记一条", shot("12-输入框记一条.png"))

    # ---------- W5 持久化（趁还没塞种子数据，验自己记的两条） ----------
    xml = launch()
    screen = visible(xml)
    check("W5 杀掉进程重开，自己记下的还在",
          "王总说明天要周报" in screen and MARKER in screen,
          "，".join(sorted(screen))[:240])
    check("截图 06-重开之后", shot("06-重开之后.png"))

    # ---------- W4/W5 有效期与退场 ----------
    seed_records()
    xml = launch()
    screen = visible(xml)
    check("W4 今天要交的单独拎在最上面", "今天要交的" in screen, "，".join(sorted(screen))[:240])
    active_screen = visible(scroll_until(xml, "没定期限的杂事"))
    check("W4 有效期内的事都在流水里", "下周的预算表" in screen and "没定期限的杂事" in active_screen)
    check("W5 过期的不占主流水", "早就过期的报销单" not in screen)
    check("W5 办完的不占主流水", "已经办完的旧事" not in screen)
    xml = scroll_until(dump_ui("before-overdue"), "已过期")
    screen = visible(xml)
    check("W5 底部有「已过期 1 件」", any("已过期" in t and "1" in t for t in screen),
          "，".join(sorted(t for t in screen if "过期" in t)))
    check("截图 01-流水", shot("01-流水.png"))

    point = center_of(xml, "已过期")
    if point:
        tap_at(point)
        time.sleep(1.2)
        xml2 = dump_ui("02-过期区")
        screen2 = visible(xml2)
        check("W5 点开后看得到过期的事", "早就过期的报销单" in screen2)
        check("截图 02-过期区", shot("02-过期区.png"))
    else:
        check("W5 点开后看得到过期的事", False, "没找到「已过期」入口")

    # ---------- W6 不删除、能翻回 ----------
    if tap_text(xml, "历史") is not None:
        time.sleep(1.2)
        xml = dump_ui("07-历史")
        check("W6 历史里有办完的事", "已经办完的旧事" in visible(xml))
        check("截图 07-历史", shot("07-历史.png"))
        back = center_of(xml, "回到清单")
        if back:
            tap_at(back)
            time.sleep(1.0)
    else:
        check("W6 历史里有办完的事", False, "没找到历史入口")

    # ---------- W12 按人看 ----------
    xml = launch()
    if tap_text(xml, "更多") is not None:
        time.sleep(1.0)
        xml = dump_ui("13-更多")
        if tap_text(xml, "按人看") is not None:
            time.sleep(1.4)
            xml = dump_ui("14-按人看")
            screen = visible(xml)
            check("W12 按人看列出人名和件数",
                  any("王总" in t for t in screen) and any("共" in t and "件" in t for t in screen),
                  "，".join(sorted(screen))[:220])
            check("截图 14-按人看", shot("14-按人看.png"))
            if tap_text(xml, "王总") is not None:
                time.sleep(1.4)
                xml = dump_ui("15-某个人")
                screen2 = visible(xml)
                check("W12 点进某个人看得到他交代的事",
                      any("王总" in t for t in screen2) and any("共" in t and "件" in t for t in screen2),
                      "，".join(sorted(screen2))[:220])
                check("截图 15-某个人", shot("15-某个人.png"))
            else:
                check("W12 点进某个人看得到他交代的事", False, "人名点不动")
        else:
            check("W12 按人看列出人名和件数", False, "更多菜单里没有按人看")
    else:
        check("W12 按人看列出人名和件数", False, "没找到更多菜单")

    # 从按人看退回清单，再测搜索
    xml = dump_ui("08-回到流水")
    for _ in range(3):
        if any("搜索" in t for t in visible(xml)):
            break
        back = center_of(xml, "回到清单") or center_of(xml, "换个人")
        if back is None:
            break
        tap_at(back)
        time.sleep(1.2)
        xml = dump_ui("08-回到流水")
    if tap_text(xml, "搜索") is not None:
        time.sleep(1.5)
        type_keys("ABC-123")
        time.sleep(1.2)
        xml = dump_ui("09-搜索")
        check("截图 09-搜索", shot("09-搜索.png"))
        hits = visible(xml)
        check("W6 搜索能翻出记录（输入 ABC-123）", any("ABC-123" in t for t in hits),
              "，".join(sorted(hits))[:200])

        clear_keys()
        type_keys("zzz")
        time.sleep(1.2)
        xml = dump_ui("10-搜不到")
        hits = visible(xml)
        check("W6 搜不到时给的是「没找到」", any("没找到" in t for t in hits),
              "，".join(sorted(hits))[:200])
        check("截图 10-搜不到", shot("10-搜不到.png"))
    else:
        check("W6 搜索入口在", False)

    failed = [r for r in RESULTS if not r[1]]
    print("\n==== 汇总：%d 项，失败 %d 项 ====" % (len(RESULTS), len(failed)), flush=True)
    for name, _ok, detail in failed:
        print("  FAIL " + name + ((" | " + detail) if detail else ""), flush=True)
    import hashlib
    (OUT / "core-results.json").write_text(json.dumps({
        "serial": SERIAL, "apkSha256": hashlib.sha256(APK.read_bytes()).hexdigest(),
        "results": [{"name": name, "passed": ok, "detail": detail} for name, ok, detail in RESULTS]
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
