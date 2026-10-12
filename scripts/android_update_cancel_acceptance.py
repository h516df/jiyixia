#!/usr/bin/env python3
"""Real update-page cancellation regression. Uses an explicitly selected test emulator."""
import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ROOT = Path(__file__).resolve().parents[1]
ADB = r"D:\Apps\Android\sdk\platform-tools\adb.exe"
PKG = "cn.jiaoban"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb-port", default=5037, type=int)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--expect-broken", action="store_true")
    args = parser.parse_args()
    out = ROOT / "verification" / ("cancel-before" if args.expect_broken else "cancel-after")
    out.mkdir(parents=True, exist_ok=True)
    results = []

    def adb(*parts, binary=False):
        p = subprocess.run([ADB, "-P", str(args.adb_port), "-s", args.serial, *parts], capture_output=True, timeout=90)
        if p.returncode:
            raise RuntimeError(p.stderr.decode("utf-8", "replace")[:200])
        return p.stdout if binary else p.stdout.decode("utf-8", "replace")

    def dump(tag):
        try:
            adb("shell", "uiautomator", "dump", "/sdcard/jiaoban-cancel-ui.xml")
        except RuntimeError:
            return ET.Element("loading")
        xml = adb("exec-out", "cat", "/sdcard/jiaoban-cancel-ui.xml")
        # Authorization codes are transient login data, so keep them out of artifacts.
        saved = re.sub(r"[A-Z0-9]{4}-[A-Z0-9]{4}", "[AUTH_CODE]", xml)
        (out / (tag + ".xml")).write_text(saved, encoding="utf-8")
        try:
            return ET.fromstring(xml)
        except ET.ParseError:
            return ET.Element("loading")

    def texts(root):
        return {n.get(k, "") for n in root.iter() for k in ("text", "content-desc")}

    def wait(tag, predicate, timeout=35):
        deadline = time.monotonic() + timeout
        while True:
            root = dump(tag)
            if predicate(texts(root)) or time.monotonic() >= deadline:
                return root
            time.sleep(.5)

    def tap(root, label):
        for n in root.iter():
            if n.get("text") == label or n.get("content-desc") == label:
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
                adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
                return
        raise AssertionError("Missing button: " + label)

    def check(name, ok):
        results.append({"name": name, "passed": bool(ok)})
        print(("PASS " if ok else "FAIL ") + name, flush=True)
        return bool(ok)

    def has_code(screen):
        return any(re.fullmatch(r"[A-Z0-9]{4}-[A-Z0-9]{4}", t) for t in screen)

    if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        parser.error("Use an isolated emulator; this test clears its update authorization.")
    if args.apk:
        check("Install test APK", "Success" in adb("install", "-r", str(args.apk)))
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", PKG + "/.MainActivity")
    root = wait("home", lambda s: "记一件" in s or any("谁交代了什么" in t for t in s))
    if "设置" in texts(root):
        tap(root, "设置")
    else:
        tap(root, "更多")
    root = wait("menu", lambda s: "检查更新" in s)
    tap(root, "检查更新")
    root = wait("update", lambda s: any("当前版本" in t for t in s))
    check("Current version and offline notes are visible", any("当前版本" in t for t in texts(root)) and any("本版说明" in t for t in texts(root)))
    (out / "update.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    if "清掉授权" in texts(root):
        tap(root, "清掉授权")
        root = wait("forgotten", lambda s: "检查更新" in s)
    tap(root, "检查更新")
    root = wait("auth-first", has_code)
    if not check("First check reaches real GitHub device authorization", has_code(texts(root))):
        (out / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
        return 1
    pid = adb("shell", "pidof", PKG).strip()
    tap(root, "取消")
    root = wait("cancelled", lambda s: "检查更新" in s and "取消" not in s)
    check("Cancel returns to check button", "检查更新" in texts(root) and "取消" not in texts(root))
    tap(root, "检查更新")
    root = wait("auth-second", has_code, timeout=35)
    second = has_code(texts(root))
    check("Broken baseline reproduced" if args.expect_broken else "Retry reaches device authorization again", not second if args.expect_broken else second)
    check("Retry uses the same live app process", adb("shell", "pidof", PKG).strip() == pid)
    if second:
        tap(root, "取消")
        root = wait("cancelled-twice", lambda s: "检查更新" in s and "取消" not in s)
        tap(root, "检查更新")
        root = wait("auth-third", has_code)
        check("Repeated cancel and retry still work", has_code(texts(root)))
        if has_code(texts(root)):
            tap(root, "取消")
            wait("final-idle", lambda s: "检查更新" in s and "取消" not in s)
    report = {"serial": args.serial, "processUnchanged": adb("shell", "pidof", PKG).strip() == pid, "results": results}
    (out / "results.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    return 0 if all(r["passed"] for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
