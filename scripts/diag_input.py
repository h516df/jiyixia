#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""临时诊断：看输入框记一条之后，行到底有没有被渲染出来。"""
import sys, time, subprocess, re
from pathlib import Path
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ADB = r"D:\Apps\Android\sdk\platform-tools\adb.exe"
PKG = "cn.jiaoban"


def adb(*args, **kw):
    p = subprocess.run([ADB, *args], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120, **kw)
    return p.stdout.decode("utf-8", "replace")


def dump(tag):
    adb("shell", "uiautomator", "dump", "/sdcard/d.xml")
    xml = adb("exec-out", "cat", "/sdcard/d.xml")
    Path(r"D:\Projects\Jiaoban\verification\diag-%s.xml" % tag).write_text(xml, encoding="utf-8")
    return xml


def nodes(xml):
    out = []
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return out
    for n in root.iter():
        t = (n.get("text") or "").strip()
        d = (n.get("content-desc") or "").strip()
        if t or d:
            out.append("%-22s %s" % ((t or ("[" + d + "]"))[:22], n.get("bounds")))
    return out


def type_keys(text):
    for ch in text:
        key = "KEYCODE_" + (ch.upper() if ch.isalpha() else ch)
        adb("shell", "input", "keyevent", key)
        time.sleep(0.15)


print("== 清数据重开 ==", flush=True)
adb("shell", "run-as %s rm -f files/tasks.json files/tasks.json.bak" % PKG)
adb("shell", "am", "force-stop", PKG)
time.sleep(1)
adb("shell", "am", "start", "-n", "cn.jiaoban/.MainActivity")
time.sleep(5)
print("\n".join(nodes(dump("a-空"))), flush=True)

print("\n== 分享一句 ==", flush=True)
adb("shell", "am", "start", "-a", "android.intent.action.SEND", "-t", "text/plain",
    "--es", "android.intent.extra.TEXT", "王总说明天要周报", "-n", "cn.jiaoban/.MainActivity")
time.sleep(4)
print("\n".join(nodes(dump("b-分享后"))), flush=True)

print("\n== 点输入框，打 abc123 ==", flush=True)
xml = dump("c-输入框")
m = re.search(r'text="谁交代了什么[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    x1, y1, x2, y2 = (int(v) for v in m.groups())
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(1)
type_keys("abc123")
time.sleep(1)
print("\n".join(nodes(dump("d-打完"))), flush=True)

print("\n== 点记下 ==", flush=True)
xml = dump("e-点前")
m = re.search(r'text="记下"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    x1, y1, x2, y2 = (int(v) for v in m.groups())
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
time.sleep(3)
print("\n".join(nodes(dump("f-记下后"))), flush=True)

print("\n== 设备上的数据 ==", flush=True)
print(adb("shell", "run-as %s cat files/tasks.json" % PKG)[:600], flush=True)
