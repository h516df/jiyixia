#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""交办性能采集：一次短 trace 包住「冷启动 -> 打字 -> 滑动列表」，带操作时间标记。

用系统自带的 perfetto 入口采，不装东西、不上传；trace 和摘要都落在项目 verification/ 里。
用法：
    python scripts/android_perf_trace.py --name v05-before
"""
import argparse
import hashlib
import json
import subprocess
import sys
import time
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "verification"
ADB = r"D:\Apps\Android\sdk\platform-tools\adb.exe"
ANALYZER = r"D:\AI\Tools\android-performance\analyze_trace.py"
PKG = "cn.jiaoban"
ACTIVITY = "cn.jiaoban/.MainActivity"
TRACE_SECONDS = 30


def run(argv, binary=False, timeout=600):
    proc = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    return proc.stdout if binary else proc.stdout.decode("utf-8", "replace")


def adb(*args, binary=False, timeout=600):
    return run([ADB, "-s", SERIAL, *args], binary=binary, timeout=timeout)


def device_ms():
    return int(adb("shell", "date", "+%s%3N").strip())


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/perf.xml")
    return adb("exec-out", "cat", "/sdcard/perf.xml")


def seed(records=30):
    """用真实的分享入口塞一批贴近使用密度的记录：不同的人、不同期限、有今天有过期。
    走分享而不是直接写文件，所以正式版（不可调试）也能塞，而且路径跟用户一致。"""
    import random

    random.seed(7)
    people = ["王总", "李姐", "张工", "运营", "客户", "老板", ""]
    texts = ["把周报发给客户", "订会议室", "核一下价格", "改详情页主图", "跟一下物流", "整理对账单",
             "回复客户消息", "看一下竞品", "准备明天的材料", "催一下供应商"]
    due_words = ["今天", "明天", "后天", "这周五", "下周三", "月底", ""]

    adb("shell", "pm", "clear", PKG)
    time.sleep(1)
    adb("shell", "am", "force-stop", PKG)
    time.sleep(0.5)
    for i in range(records):
        who = people[i % len(people)]
        sentence = (who + "说" if who else "") + texts[i % len(texts)]
        due = due_words[i % len(due_words)]
        if due:
            sentence += "，" + due + "要"
        adb("shell", "am", "start", "-a", "android.intent.action.SEND", "-t", "text/plain",
            "--es", "android.intent.extra.TEXT", sentence, "-n", ACTIVITY)
        time.sleep(0.7)
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)
    print("已用分享入口塞入 %d 条记录" % records, flush=True)


def type_keys(text):
    for ch in text:
        adb("shell", "input", "keyevent", "KEYCODE_" + ch.upper())
        time.sleep(0.08)


def main():
    global SERIAL
    parser = argparse.ArgumentParser()
    parser.add_argument("--name", required=True, help="本轮唯一名字，例如 v05-before")
    parser.add_argument("--serial", default="")
    parser.add_argument("--no-seed", action="store_true")
    parser.add_argument("--apk", default=str(ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"),
                        help="先装这个包再测（默认正式版）")
    args = parser.parse_args()

    SERIAL = args.serial
    if not SERIAL:
        out = run([ADB, "devices"])
        for line in out.splitlines():
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device" and parts[0].startswith("emulator-"):
                SERIAL = parts[0]
                break
    if not SERIAL:
        raise SystemExit("没有可用的模拟器")

    OUT.mkdir(parents=True, exist_ok=True)
    if args.apk and Path(args.apk).is_file():
        out = adb("install", "-r", str(args.apk))
        print("装包：" + out.strip().splitlines()[-1][:80], flush=True)
    if not args.no_seed:
        seed()

    trace_device = "/data/misc/perfetto-traces/jiaoban-%s.pftrace" % args.name
    adb("shell", "rm", "-f", trace_device)
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)

    marks = {"package": PKG, "serial": SERIAL, "trace_seconds": TRACE_SECONDS}
    marks["device_version"] = adb("shell", "dumpsys", "package", PKG).split("versionName=")[-1].split()[0] \
        if "versionName=" in adb("shell", "dumpsys", "package", PKG) else "unknown"

    categories = ["sched", "freq", "idle", "am", "wm", "gfx", "view", "binder_driver", "dalvik", "input", "res"]
    proc = subprocess.Popen(
        [ADB, "-s", SERIAL, "shell", "perfetto", "-o", trace_device, "-t", "%ds" % TRACE_SECONDS,
         "-b", "32mb", *categories],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    )
    time.sleep(2)
    marks["trace_start"] = device_ms()

    marks["launch_start"] = device_ms()
    adb("shell", "am", "start", "-n", ACTIVITY)
    ready = False
    for _ in range(80):
        if "谁交代了什么" in dump():
            ready = True
            break
        time.sleep(0.3)
    marks["launch_ready"] = device_ms()
    marks["launch_ready_ok"] = ready

    marks["typing_start"] = device_ms()
    type_keys("wangzongshuomingtianyaozhoubao")
    marks["typing_end"] = device_ms()

    marks["scroll_start"] = device_ms()
    for _ in range(5):
        adb("shell", "input", "swipe", "540", "1700", "540", "700", "280")
        time.sleep(0.4)
    marks["scroll_end"] = device_ms()

    proc.wait(timeout=TRACE_SECONDS + 60)
    marks["trace_end"] = device_ms()

    local = OUT / ("perf-%s.pftrace" % args.name)
    adb("shell", "cp", trace_device, "/sdcard/jiaoban-%s.pftrace" % args.name)
    adb("pull", "/sdcard/jiaoban-%s.pftrace" % args.name, str(local))
    if not local.is_file() or local.stat().st_size < 100_000:
        raise SystemExit("trace 没拉下来或太小：%s" % local)
    marks["trace_bytes"] = local.stat().st_size
    marks["trace_sha256"] = hashlib.sha256(local.read_bytes()).hexdigest()
    (OUT / ("perf-%s-marks.json" % args.name)).write_text(
        json.dumps(marks, ensure_ascii=False, indent=2), encoding="utf-8")
    print("trace=%s bytes=%d" % (local.name, marks["trace_bytes"]), flush=True)
    print("冷启动 %.0fms，打字 %.0fms，滑动 %.0fms" % (
        marks["launch_ready"] - marks["launch_start"],
        marks["typing_end"] - marks["typing_start"],
        marks["scroll_end"] - marks["scroll_start"],
    ), flush=True)

    summary = OUT / ("perf-%s-summary.json" % args.name)
    out = run([sys.executable, "-X", "utf8", ANALYZER, "--trace", str(local),
               "--package", PKG, "--out", str(summary)])
    print(out[-2000:], flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
