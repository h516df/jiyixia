"""交办专用模拟器上核对正式 0.4 -> 0.8 覆盖安装与旧记录字节保留。"""
import argparse
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get("ANDROID_HOME", "C:/Android/sdk"))
parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--adb-port', default='5037')
parser.add_argument('--baseline', type=Path, required=True)
parser.add_argument('--candidate', type=Path, required=True)
args = parser.parse_args()
out = ROOT / 'verification/upgrade-v04-v08'
out.mkdir(parents=True, exist_ok=True)

def run(parts):
    p = subprocess.run(list(map(str, parts)), capture_output=True, timeout=180)
    result = (p.stdout + p.stderr).decode('utf-8', 'replace')
    if p.returncode:
        raise RuntimeError(result[:800])
    return result

def adb(*parts):
    return run([SDK / 'platform-tools/adb.exe', '-P', args.adb_port, '-s', args.serial, *parts])

def cert(apk):
    result = run([Path(os.environ['JAVA_HOME']) / 'bin/java.exe', '-jar',
                  SDK / 'build-tools/36.0.0/lib/apksigner.jar', 'verify', '--print-certs', apk])
    return re.search(r'certificate SHA-256 digest: (\w+)', result).group(1)

def records():
    result = adb('shell', 'am', 'instrument', '-w', '-e', 'dataProbe', 'true',
                 'cn.jiaoban.test/cn.jiaoban.DateRegressionProbe')
    found = re.search(r'DATA_RECORDS_SHA256: (\w+) count=(\d+)', result)
    if not found or 'DATA_RECORDS_FAIL' in result:
        raise RuntimeError(result)
    return {'sha256': found.group(1), 'count': int(found.group(2))}

def install(apk, downgrade=False):
    result = adb('install', '-r', *(['-d'] if downgrade else []), apk)
    if 'Success' not in result:
        raise RuntimeError(result)

def launch_shot(name):
    adb('shell', 'am', 'start', '-n', 'cn.jiaoban/.MainActivity')
    import android_acceptance as ui
    os.environ["JIAOBAN_ADB_PORT"] = args.adb_port
    ui.SERIAL = args.serial
    xml = ui.wait_for_app()
    labels = ui.visible(xml)
    assert "交办" in labels and any("手上" in label for label in labels), "Installed app did not render existing fixture records."

    adb('shell', 'screencap', '-p', '/sdcard/jiaoban-upgrade.png')
    adb('pull', '/sdcard/jiaoban-upgrade.png', out / name)

if adb('shell', 'getprop', 'ro.boot.qemu.avd_name').strip() != 'Jiaoban_Codex_API36':
    parser.error('Only use the disposable Jiaoban test emulator.')
old_cert, new_cert = cert(args.baseline), cert(args.candidate)
assert old_cert == new_cert, 'Original and candidate signing certificates differ.'
print('PASS 正式 0.4 与候选 0.8 签名相同', flush=True)
# 只在已核对身份的测试模拟器中用同签名调试包允许回到旧版本；不卸载、不清记录。
install(ROOT / 'app/build/outputs/apk/debug/app-debug.apk')
install(args.baseline, downgrade=True)
install(ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
assert 'versionName=0.4' in adb('shell', 'dumpsys', 'package', 'cn.jiaoban')
before = records()
assert before['count'] > 0, 'Upgrade test requires existing fixture records.'
launch_shot('before-v04.png')
install(args.candidate)
assert 'versionName=0.8' in adb('shell', 'dumpsys', 'package', 'cn.jiaoban')
after = records()
assert before == after, 'Upgrade changed existing record bytes.'
launch_shot('after-v08.png')
report = {'serial': args.serial, 'certificateSha256': new_cert,
          'baselineApkSha256': hashlib.sha256(args.baseline.read_bytes()).hexdigest(),
          'candidateApkSha256': hashlib.sha256(args.candidate.read_bytes()).hexdigest(),
          'before': before, 'after': after, 'passed': True}
(out / 'results.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(f"PASS 正式 0.4 -> 0.8 覆盖安装，{before['count']} 条记录 SHA256 完全一致", flush=True)
