#!/usr/bin/env python3
"""Run the native date probe on the task's disposable emulator."""
import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ROOT = Path(__file__).resolve().parents[1]
ADB = r"D:\Apps\Android\sdk\platform-tools\adb.exe"

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb-port", type=int, default=5037)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--expect-broken", action="store_true")
    args = parser.parse_args()
    test_apk = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    out = ROOT / "verification" / ("date-before" if args.expect_broken else "date-after")
    out.mkdir(parents=True, exist_ok=True)
    def adb(*parts):
        p = subprocess.run([ADB, "-P", str(args.adb_port), "-s", args.serial, *map(str, parts)],
                           capture_output=True, timeout=240)
        output = (p.stdout+p.stderr).decode("utf-8", "replace")
        if p.returncode:
            raise RuntimeError(output[:300])
        return output
    if adb("shell", "getprop", "ro.boot.qemu.avd_name").strip() != "Jiaoban_Codex_API36":
        parser.error("Only use the task's disposable Jiaoban_Codex_API36 emulator.")
    if not args.apk.is_file() or not test_apk.is_file():
        parser.error("Build the app APK and :app:assembleDebugAndroidTest first.")
    for apk in [args.apk, test_apk]:
        if "Success" not in adb("install", "-r", apk):
            raise RuntimeError("Install failed: " + apk.name)
    options = ["-e", "expectBroken", "true"] if args.expect_broken else []
    output = adb("shell", "am", "instrument", "-w", *options, "cn.jiaoban.test/cn.jiaoban.DateRegressionProbe")
    print(output, flush=True)
    (out / "native-probe.log").write_text(output, encoding="utf-8")
    passed = "DATE_REGRESSION_PASS" in output and "DATE_REGRESSION_FAIL" not in output
    report = {"serial": args.serial, "passed": passed, "expectedBroken": args.expect_broken,
              "apkSha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
              "probeSha256": hashlib.sha256(test_apk.read_bytes()).hexdigest()}
    (out / "results.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    if passed and not args.expect_broken:
        adb("pull", "/sdcard/Android/data/cn.jiaoban/files/date-probe-nextday.png", out / "next-day.png")
    return 0 if passed else 1

if __name__ == "__main__":
    sys.exit(main())
