import os
#!/usr/bin/env python3
"""Verify same-signature cover upgrade, exact record preservation and real loaded UI on the owned AVD."""
import argparse, hashlib, json, os, re, subprocess, sys, time
from pathlib import Path
from android_redesign_acceptance import fingerprint

ROOT=Path(__file__).resolve().parents[1]
ADB=Path(os.environ.get("ANDROID_HOME", "C:/Android/sdk")) / "platform-tools" / "adb.exe"
TOOLS=Path(os.environ.get("ANDROID_HOME", "C:/Android/sdk")) / "build-tools" / "36.0.0"
sys.stdout.reconfigure(encoding="utf-8",errors="replace")
def run(command):
    p=subprocess.run(list(map(str,command)),capture_output=True,timeout=180)
    if p.returncode: raise RuntimeError(p.stderr.decode("utf-8","replace")[:800])
    return p.stdout.decode("utf-8","replace")
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--serial",required=True)
    parser.add_argument("--adb-port",default=5043,type=int)
    parser.add_argument("--old-apk",type=Path,required=True)
    args=parser.parse_args()
    new=ROOT/"app/build/outputs/apk/release/app-release.apk"
    test=ROOT/"app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    def adb(*parts): return run([ADB,"-P",args.adb_port,"-s",args.serial,*parts])
    if adb("shell","getprop","ro.boot.qemu.avd_name").strip()!="Jiaoban_Redesign_API36": raise SystemExit("Wrong AVD.")
    def signing(path):
        raw=run([TOOLS/"apksigner.bat","verify","--print-certs",path])
        return re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)",raw).group(1)
    def package(path):
        raw=run([TOOLS/"aapt2.exe","dump","badging",path])
        m=re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",raw)
        return {"package":m.group(1),"versionCode":int(m.group(2)),"versionName":m.group(3)}
    old_meta,new_meta=package(args.old_apk),package(new)
    out=ROOT/("verification/upgrade-"+old_meta["versionName"]+"-to-"+new_meta["versionName"])
    out.mkdir(parents=True,exist_ok=True)
    old_cert,new_cert=signing(args.old_apk),signing(new)
    if old_cert!=new_cert or old_meta["package"]!=new_meta["package"] or old_meta["versionCode"]>=new_meta["versionCode"]:
        raise SystemExit("Upgrade identity/signature/version gate failed.")
    expected=args.old_apk.with_name(args.old_apk.name.replace("universal.apk","SHA256.txt"))
    if expected.is_file() and expected.read_text(encoding="utf-8").split()[0]!=sha(args.old_apk): raise SystemExit("Old release hash mismatch.")
    # A downgrade is limited to this disposable fixture; no uninstall or clear-data path is used.
    for apk in (args.old_apk,test):
        if "Success" not in adb("install","-r","-d",apk): raise SystemExit("Fixture install failed.")
    adb("shell","am","force-stop","cn.jiaoban")
    fixture="seedLegacy" if old_meta["versionCode"] <= 8 else "seedFixture"
    first=adb("shell","am","instrument","-w","-e","dataOnly","true","-e",fixture,"true","cn.jiaoban.test/cn.jiaoban.RedesignRegressionProbe")
    before=next((s.strip() for s in first.splitlines() if "DATA_SHA256:" in s),"")
    if not before: raise SystemExit("Legacy fixture creation failed.")
    adb("shell","am","start","-W","-n","cn.jiaoban/.MainActivity")
    time.sleep(1)
    def dump_ready(name):
        for attempt in range(12):
            adb("shell","uiautomator","dump","/sdcard/jiaoban-upgrade.xml")
            xml=adb("exec-out","cat","/sdcard/jiaoban-upgrade.xml")
            if "今日周报" in xml:
                (out/(name+".xml")).write_text(xml,encoding="utf-8"); break
            time.sleep(.5)
        else: raise SystemExit("Actual loaded UI not found: "+name)
        png=subprocess.check_output([str(ADB),"-P",str(args.adb_port),"-s",args.serial,"exec-out","screencap","-p"])
        (out/(name+".png")).write_bytes(png)
    dump_ready("before")
    adb("shell","am","force-stop","cn.jiaoban")
    if "Success" not in adb("install","-r",new): raise SystemExit("Cover upgrade failed.")
    adb("shell","am","start","-W","-n","cn.jiaoban/.MainActivity"); time.sleep(1)
    dump_ready("after")
    last=adb("shell","am","instrument","-w","-e","dataOnly","true","cn.jiaoban.test/cn.jiaoban.RedesignRegressionProbe")
    after=next((s.strip() for s in last.splitlines() if "DATA_SHA256:" in s),"")
    current=adb("shell","dumpsys","package","cn.jiaoban")
    passed=before==after and bool(before) and "versionName="+new_meta["versionName"] in current
    report={"passed":passed,"sourceFingerprint":fingerprint(),"serial":args.serial,"avd":"Jiaoban_Redesign_API36",
        "old":{**old_meta,"sha256":sha(args.old_apk)},"new":{**new_meta,"sha256":sha(new)},
        "certificateSha256":new_cert,"recordsBefore":before,"recordsAfter":after,
        "loadedBeforeAndAfter":True,"noUninstallOrDataClear":True}
    (out/"results.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps(report,ensure_ascii=False,indent=2),flush=True)
    return 0 if passed else 1
if __name__=="__main__": raise SystemExit(main())
