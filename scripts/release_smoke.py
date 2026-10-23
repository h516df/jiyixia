import os
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""交办 v0.2 正式版冒烟 + 卡顿实测。

做四件事：
  1. 先量一遍调试版的帧数据（用户抱怨过卡顿，得有前后对比）
  2. 把正式版覆盖安装上去（同一个签名），验证已经记的东西没丢
  3. 正式版里再走一次「分享一句话 -> 认人认期限」，证明 R8 压缩没弄坏解析
  4. 量正式版的帧数据，和调试版对比

用法：python scripts/release_smoke.py
"""
import datetime as dt
import re
import subprocess
import sys
import time
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "verification"
DEBUG_APK = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
RELEASE_APK = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
ADB = os.path.join(os.environ.get("ANDROID_HOME", "C:/Android/sdk"), "platform-tools", "adb.exe")
PKG = "cn.jiaoban"

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print((("PASS  " if ok else "FAIL  ") + name + ((" | " + detail) if detail else "")), flush=True)


def run(argv, binary=False, timeout=600):
    p = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    return p.stdout if binary else p.stdout.decode("utf-8", "replace")


def adb(*args, **kw):
    return run([ADB, *args], **kw)


def dump_ui(tag):
    adb("shell", "uiautomator", "dump", "/sdcard/rs.xml")
    xml = adb("exec-out", "cat", "/sdcard/rs.xml")
    (OUT / ("release-%s.xml" % tag)).write_text(xml, encoding="utf-8")
    return xml


def texts(xml):
    return set(m.group(1).strip() for m in re.finditer(r'text="([^"]+)"', xml))


def center_of(xml, needle):
    m = re.search(r'text="[^"]*' + re.escape(needle) + r'[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        return None
    x1, y1, x2, y2 = (int(v) for v in m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def launch():
    adb("shell", "am", "force-stop", PKG)
    for _ in range(20):
        if not adb("shell", "pidof", PKG).strip():
            break
        time.sleep(0.3)
    adb("shell", "am", "start", "-n", "cn.jiaoban/.MainActivity")
    time.sleep(3.0)


def typing_session(chars=14):
    """在输入框里打一串字，制造真实的界面帧。"""
    xml = dump_ui("typing")
    point = center_of(xml, "谁交代了什么")
    if point:
        adb("shell", "input", "tap", str(point[0]), str(point[1]))
        time.sleep(0.6)
    for i in range(chars):
        adb("shell", "input", "keyevent", "KEYCODE_" + "ABCDEFGHIJKLMNOP"[i % 16])
        time.sleep(0.1)
    for _ in range(chars + 4):
        adb("shell", "input", "keyevent", "KEYCODE_DEL")
    time.sleep(0.5)


def frame_stats():
    out = adb("shell", "dumpsys", "gfxinfo", PKG)
    stats = {}
    for key, pattern in (
        ("total", r"Total frames rendered:\s*(\d+)"),
        ("janky", r"Janky frames:\s*(\d+)"),
        ("janky_pct", r"Janky frames:\s*\d+\s*\(([\d.]+)%\)"),
        ("p50", r"50th percentile:\s*(\d+)ms"),
        ("p90", r"90th percentile:\s*(\d+)ms"),
        ("p95", r"95th percentile:\s*(\d+)ms"),
    ):
        m = re.search(pattern, out)
        stats[key] = m.group(1) if m else "?"
    return stats


def measure(label):
    adb("shell", "dumpsys", "gfxinfo", PKG, "reset")
    launch()
    typing_session()
    stats = frame_stats()
    print("== %s 帧数据：总帧 %s，卡顿 %s 帧（%s%%），90分位 %sms，95分位 %sms =="
          % (label, stats["total"], stats["janky"], stats["janky_pct"], stats["p90"], stats["p95"]), flush=True)
    return stats


def shot(name):
    data = adb("exec-out", "screencap", "-p", binary=True)
    (OUT / name).write_bytes(data)
    return len(data) > 20000


def main():
    if not RELEASE_APK.is_file():
        print("== 先构建正式版 ==", flush=True)
        out = run([str(ROOT / "gradlew.bat"), "-p", str(ROOT), ":app:assembleRelease", "--console=plain"], timeout=2400)
        print(out[-1500:], flush=True)
    check("正式版 APK 存在", RELEASE_APK.is_file(), str(RELEASE_APK))

    # 1. 调试版基线
    if not adb("shell", "pm", "list", "packages", PKG).strip():
        check("设备上已经装着 App", False, "先跑一次 android_acceptance.py")
        return 1
    debug_stats = measure("调试版")

    # 2. 升级前先记一条带标记的，等会儿验证它没丢
    marker = "升级保留测试A1"
    adb("shell", "am", "start", "-a", "android.intent.action.SEND", "-t", "text/plain",
        "--es", "android.intent.extra.TEXT", marker, "-n", "cn.jiaoban/.MainActivity")
    time.sleep(4)
    before = texts(dump_ui("before-upgrade"))
    check("升级前先记下一条", marker in before, "，".join(sorted(before))[:160])

    out = adb("install", "-r", str(RELEASE_APK))
    check("正式版覆盖安装成功（同一签名）", "Success" in out, out.strip().splitlines()[-1][:120])

    launch()
    xml = dump_ui("after-upgrade")
    screen = texts(xml)
    check("覆盖安装后原来的记录还在", marker in screen,
          "，".join(sorted(screen))[:200])
    check("截图 20-正式版覆盖安装后", shot("20-正式版覆盖安装后.png"))

    # 3. 正式版里再验一次认人认期限（R8 压缩后仍要能跑）
    adb("shell", "am", "start", "-a", "android.intent.action.SEND", "-t", "text/plain",
        "--es", "android.intent.extra.TEXT", "李姐说下周三之前给她", "-n", "cn.jiaoban/.MainActivity")
    time.sleep(4)
    xml = dump_ui("release-share")
    screen = texts(xml)
    # 下周三 = 下周（下周一算起）的那个周三；按当天算，不写死日期
    today = dt.date.today()
    next_monday = today + dt.timedelta(days=7 - today.weekday())
    next_wednesday = next_monday + dt.timedelta(days=2)
    expected = "%d月%d日" % (next_wednesday.month, next_wednesday.day)
    check("正式版里认出了人（李姐）", any(t.startswith("李姐 · ") for t in screen),
          "，".join(sorted(t for t in screen if "·" in t)))
    check("正式版里认出了期限（下周三 = %s）" % expected,
          any("李姐" in t and expected in t for t in screen),
          "，".join(sorted(t for t in screen if "·" in t)))
    check("截图 21-正式版认人认期限", shot("21-正式版认人认期限.png"))

    # 4. 正式版帧数据。
    # 模拟器是软件渲染，卡顿帧占比的噪声比版本差异还大，所以这里只把两组数字摆出来，
    # 不拿它当通过/不通过的门槛；能当门槛的是上面那几条确定性检查。
    release_stats = measure("正式版")
    measured = release_stats["total"] not in ("?", "0") and debug_stats["total"] not in ("?", "0")
    check(
        "正式版帧数据已量到（两组数字见上面两行）",
        measured,
        "调试版 %s帧/%s%%/p90 %sms -> 正式版 %s帧/%s%%/p90 %sms" % (
            debug_stats["total"], debug_stats["janky_pct"], debug_stats["p90"],
            release_stats["total"], release_stats["janky_pct"], release_stats["p90"],
        ),
    )

    failed = [r for r in RESULTS if not r[1]]
    print("\n==== 汇总：%d 项，失败 %d 项 ====" % (len(RESULTS), len(failed)), flush=True)
    for name, _ok, detail in failed:
        print("  FAIL " + name + ((" | " + detail) if detail else ""), flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
