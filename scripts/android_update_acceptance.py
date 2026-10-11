#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""更新功能的真机验收（能做到哪一步就验到哪一步）。

能自己验的：
  U1 「检查更新」入口在，页面显示当前版本和本版中文说明（离线可看）
  U2 点检查更新会走到 GitHub 设备授权：出现真实的 8 位码和授权页链接
  U3 授权页链接指向 github.com/login/device

验不了的（需要用户本人的 GitHub 登录，我不碰）：在浏览器里输入码并勾选仓库之后，
真正的下载、校验和系统安装。那一段要靠用户手机上过一次。
"""
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

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print((("PASS  " if ok else "FAIL  ") + name + ((" | " + detail) if detail else "")), flush=True)


def adb(*args, binary=False):
    p = subprocess.run([ADB, *args], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=300)
    return p.stdout if binary else p.stdout.decode("utf-8", "replace")


def dump(tag):
    adb("shell", "uiautomator", "dump", "/sdcard/u.xml")
    xml = adb("exec-out", "cat", "/sdcard/u.xml")
    (OUT / ("update-%s.xml" % tag)).write_text(xml, encoding="utf-8")
    return xml


def texts(xml):
    return set(m.group(1).strip() for m in re.finditer(r'text="([^"]+)"', xml))


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


def tap(xml, needle):
    point = center_of(xml, needle)
    if point is None:
        return False
    adb("shell", "input", "tap", str(point[0]), str(point[1]))
    return True


def shot(name):
    data = adb("exec-out", "screencap", "-p", binary=True)
    (OUT / name).write_bytes(data)
    return len(data) > 20000


def wait_for(xml_getter, needle, timeout=20.0):
    deadline = time.time() + timeout
    xml = ""
    while time.time() < deadline:
        xml = xml_getter()
        if needle in texts(xml):
            return xml
        time.sleep(0.8)
    return xml


def main():
    install = adb("install", "-r", str(APK))
    check("安装调试版", "Success" in install, install.strip().splitlines()[-1][:100])

    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)
    adb("shell", "am", "start", "-n", "cn.jiaoban/.MainActivity")
    xml = wait_for(lambda: dump("home"), "谁交代了什么")
    check("首页起来了", "交办" in texts(xml), "，".join(sorted(texts(xml)))[:160])

    # U1 入口 -> 更新页
    tap(xml, "更多")
    time.sleep(1.2)
    xml = dump("menu")
    check("「更多」里有检查更新", "检查更新" in texts(xml), "，".join(sorted(texts(xml)))[:160])
    tap(xml, "检查更新")
    xml = wait_for(lambda: dump("updates"), "当前版本")
    screen = texts(xml)
    check("U1 更新页显示当前版本", any("当前版本" in t for t in screen), "，".join(sorted(screen))[:200])
    check("U1 离线也能看本版中文说明", any("本版说明" in t for t in screen), "，".join(sorted(screen))[:200])
    check("U1 说明里有新增/改进分节", "新增" in screen and "改进" in screen)
    check("截图 30-更新页", shot("30-更新页.png"))

    # U2 检查更新 -> 设备授权
    tap(xml, "检查更新")
    xml = wait_for(lambda: dump("auth"), "授权", timeout=30.0)
    screen = texts(xml)
    code = None
    for t in screen:
        m = re.fullmatch(r"[A-Z0-9]{4}-[A-Z0-9]{4}", t.strip())
        if m:
            code = t.strip()
    check("U2 出现了 GitHub 设备授权码", code is not None, "，".join(sorted(screen))[:240])
    check("U2 页面提示要勾选仓库", any("勾选" in t for t in screen), "，".join(sorted(screen))[:200])
    check("U3 有去浏览器授权的按钮", any("浏览器" in t for t in screen))
    check("截图 31-设备授权", shot("31-设备授权.png"))
    if code:
        print("   本次真机上拿到的授权码：%s" % code, flush=True)

    failed = [r for r in RESULTS if not r[1]]
    print("\n==== 汇总：%d 项，失败 %d 项 ====" % (len(RESULTS), len(failed)), flush=True)
    for name, _ok, detail in failed:
        print("  FAIL " + name + ((" | " + detail) if detail else ""), flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
