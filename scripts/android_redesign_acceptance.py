#!/usr/bin/env python3
"""Real UI regression on the task's disposable AVD; exact source/APK binding and optional evidence reuse."""
import argparse, hashlib, json, os, subprocess, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADB = Path(r"D:\Apps\Android\sdk\platform-tools\adb.exe")
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def fingerprint():
    paths = list((ROOT / "app/src/main").rglob("*"))
    paths += [ROOT / p for p in ("app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/wrapper/gradle-wrapper.properties")]
    h = hashlib.sha256()
    for p in sorted(p for p in paths if p.is_file()):
        content=p.read_bytes()
        if p.suffix in {".kt",".java",".kts",".xml",".json",".properties"}:
            content=content.replace(b"\r\n",b"\n")
        h.update(p.relative_to(ROOT).as_posix().encode()); h.update(b"\0"); h.update(content); h.update(b"\0")
    return h.hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb-port", type=int, default=5043)
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--reuse-evidence", action="store_true")
    args = parser.parse_args()
    test_apk = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    mode = "release" if args.apk.parent.name == "release" else "debug"
    out = ROOT / "verification" / ("redesign-" + mode)
    out.mkdir(parents=True, exist_ok=True)
    def adb(*parts, binary=False, timeout=120):
        p = subprocess.run([str(ADB), "-P", str(args.adb_port), "-s", args.serial, *map(str,parts)],
            capture_output=True, timeout=timeout)
        if p.returncode: raise RuntimeError(p.stderr.decode("utf-8","replace")[:400])
        return p.stdout if binary else p.stdout.decode("utf-8","replace")
    identity = adb("shell","getprop","ro.boot.qemu.avd_name").strip()
    if identity != "Jiaoban_Redesign_API36": raise SystemExit("Only the task's disposable AVD is allowed.")
    adb("shell","cmd","alarm","set-timezone","Asia/Shanghai")
    bound = {"sourceFingerprint":fingerprint(), "apkSha256":digest(args.apk), "probeSha256":digest(test_apk),
        "serial":args.serial,"avd":identity,"sdk":adb("shell","getprop","ro.build.version.sdk").strip(),
        "abi":adb("shell","getprop","ro.product.cpu.abi").strip(),
        "timezone":adb("shell","getprop","persist.sys.timezone").strip()}
    record_path = out / "bound-results.json"
    if args.reuse_evidence and record_path.is_file():
        old=json.loads(record_path.read_text(encoding="utf-8"))
        if old.get("binding")==bound and old.get("passed"):
            installed=adb("shell","pm","path","cn.jiaoban").strip().splitlines()[0].removeprefix("package:")
            if adb("shell","sha256sum",installed).split()[0]!=bound["apkSha256"]:
                raise SystemExit("Current installed APK differs; evidence cannot be reused.")
            print("Reused matching, passed source/APK/probe/environment evidence."); return 0
        raise SystemExit("Evidence does not match current inputs; rerun without --reuse-evidence.")
    for apk in (args.apk,test_apk):
        install=adb("install","-r",apk)
        if "Success" not in install: raise RuntimeError(install)
    installed=adb("shell","pm","path","cn.jiaoban").strip().splitlines()[0].removeprefix("package:")
    actual=adb("shell","sha256sum",installed).split()[0]
    if actual!=bound["apkSha256"]: raise SystemExit("Installed package differs from tested APK.")
    adb("shell","am","force-stop","cn.jiaoban")
    print("Testing "+mode+" APK "+actual,flush=True)
    command = [str(ADB),"-P",str(args.adb_port),"-s",args.serial,"shell","am","instrument","-w",
        "-e","sourceFingerprint",bound["sourceFingerprint"],"cn.jiaoban.test/cn.jiaoban.RedesignRegressionProbe"]
    p=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    lines=[]
    for raw in p.stdout:
        line=raw.decode("utf-8","replace"); lines.append(line); print(line.rstrip(),flush=True)
    p.wait()
    (out/"instrument.log").write_text("".join(lines),encoding="utf-8")
    adb("pull","/sdcard/Android/data/cn.jiaoban/files/redesign-qa/.",out)
    probe=json.loads((out/"results.json").read_text(encoding="utf-8"))
    after=fingerprint()
    passed=p.returncode==0 and "REDESIGN_PASS" in "".join(lines) and probe["passed"] and after==bound["sourceFingerprint"]
    if passed:
        snap1=adb("shell","am","instrument","-w","-e","dataOnly","true","cn.jiaoban.test/cn.jiaoban.RedesignRegressionProbe")
        adb("shell","am","force-stop","cn.jiaoban")
        adb("shell","am","start","-W","-n","cn.jiaoban/.MainActivity")
        time.sleep(1)
        snap2=adb("shell","am","instrument","-w","-e","dataOnly","true","cn.jiaoban.test/cn.jiaoban.RedesignRegressionProbe")
        before=next((s.strip() for s in snap1.splitlines() if "DATA_SHA256:" in s),"")
        reopened=next((s.strip() for s in snap2.splitlines() if "DATA_SHA256:" in s),"")
        passed=bool(before) and before==reopened
        probe["restartPreservedRecords"]=passed
        probe["recordsSnapshot"]=before
        print(("PASS " if passed else "FAIL ")+"Restart preserves exact records",flush=True)
    report={"binding":bound,"passed":passed,"probe":probe}
    record_path.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    return 0 if passed else 1
if __name__=="__main__": raise SystemExit(main())
